package net.java21.data2flow.core.passwordreset.service;

import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.account.domain.AppUser;
import net.java21.data2flow.core.account.domain.UserStatus;
import net.java21.data2flow.core.account.event.IamEventPublisher;
import net.java21.data2flow.core.account.repository.PasswordHistoryRepository;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.account.service.PasswordPolicy;
import net.java21.data2flow.core.audit.service.AuditCodes;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.AfterCommit;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.common.InMemoryRateLimiter;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.mail.service.MailLinks;
import net.java21.data2flow.core.mail.service.MailService;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.passwordreset.repository.PasswordResetTokenRepository;
import net.java21.data2flow.core.passwordreset.repository.PasswordResetTokenRepository.ResetToken;
import net.java21.data2flow.core.session.domain.RefreshToken.RevokeReason;
import net.java21.data2flow.core.session.service.SessionRevocations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * 비밀번호 재설정(IAM-02.04, BR-IAM-11·12). 요청은 계정이 있든 없든 항상 202로 같게 답하고(계정 존재 은닉), 같은 계정에는 10분에
 * 3통까지만 메일을 보낸다(AT-IAM-07.4). 링크는 30분·1회용이고 새 요청이 오면 이전 링크는 무효다. 재설정하면 모든 세션을 끊는다.
 * IP당 분당 20회를 넘으면 429 AUTH_RATE_LIMITED(파드 단위 보조 한도, 클러스터 전체 한도는 gateway).
 */
@Service
public class PasswordResetService {

    static final int MAX_MAILS = 3;
    static final Duration MAIL_WINDOW = Duration.ofMinutes(10);

    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final DeploymentOrganization deployment;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordHistoryRepository history;
    private final PasswordPolicy policy;
    private final PasswordEncoder encoder;
    private final SessionRevocations revocations;
    private final MailService mail;
    private final MailLinks links;
    private final IamEventPublisher events;
    private final Audits audits;
    private final CoreProperties properties;
    private final Clock clock;
    private final InMemoryRateLimiter ipLimiter;

    public PasswordResetService(UserRepository users, OrganizationRepository organizations, DeploymentOrganization deployment, PasswordResetTokenRepository resetTokens,
                                PasswordHistoryRepository history, PasswordPolicy policy, PasswordEncoder encoder,
                                SessionRevocations revocations, MailService mail, MailLinks links, IamEventPublisher events,
                                Audits audits, CoreProperties properties, Clock clock) {
        this.users = users;
        this.organizations = organizations;
        this.deployment = deployment;
        this.resetTokens = resetTokens;
        this.history = history;
        this.policy = policy;
        this.encoder = encoder;
        this.revocations = revocations;
        this.mail = mail;
        this.links = links;
        this.events = events;
        this.audits = audits;
        this.properties = properties;
        this.clock = clock;
        this.ipLimiter = new InMemoryRateLimiter(20, Duration.ofMinutes(1), clock);
    }

    /** API-IAM-13 재설정 요청. 결과와 관계없이 202 */
    @Transactional
    public void request(String loginIdOrEmail, String ip) {
        OptionalLong retry = ipLimiter.tryAcquire(ip);
        if (retry.isPresent()) {
            throw new BusinessException(CommonErrorCode.AUTH_RATE_LIMITED, retry.getAsLong())
                    .withHeader("Retry-After", Long.toString(retry.getAsLong()));
        }
        var org = deployment.current().orElse(null);
        if (org == null) {
            return;
        }
        AppUser user = users.findByLoginIdOrEmail(org.id(), loginIdOrEmail.strip()).orElse(null);
        if (user == null || (user.status() != UserStatus.ACTIVE && user.status() != UserStatus.LOCKED)) {
            return;
        }
        Instant now = clock.instant();
        if (resetTokens.countCreatedSince(org.id(), user.id(), now.minus(MAIL_WINDOW)) >= MAX_MAILS) {
            return;
        }
        resetTokens.invalidateUnused(org.id(), user.id(), now);
        String token = Tokens.newToken();
        Instant expiresAt = now.plus(properties.tokens().passwordReset());
        resetTokens.insert(org.id(), user.id(), Tokens.sha256Hex(token), expiresAt, ip, now);
        audits.record(audits.event(org.id(), AuditCodes.PASSWORD_RESET_REQUESTED)
                .actor(AuditActorType.USER, Long.toString(user.id()), null).target("USER", Long.toString(user.id())).ip(ip));
        var locale = MailLinks.locale(user.locale());
        String link = links.link("/password-reset/" + token, locale);
        String expires = MailLinks.format(expiresAt, user.timezone());
        String email = user.email();
        long orgId = org.id();
        AfterCommit.run("재설정 메일", () -> mail.send(orgId, email, "mail.password-reset", locale, user.name(), link, expires));
    }

    /** API-IAM-14 새 비밀번호 설정. 만료·사용된 링크는 410 RESET_TOKEN_INVALID */
    @Transactional
    public void confirm(String token, String newPassword) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            throw new BusinessException(CoreErrorCode.RESET_TOKEN_INVALID);
        }
        Instant now = clock.instant();
        ResetToken reset = resetTokens.lockByTokenHash(Tokens.sha256Hex(token))
                .filter(t -> t.usable(now))
                .orElseThrow(() -> new BusinessException(CoreErrorCode.RESET_TOKEN_INVALID));
        AppUser user = users.lockByIdAndOrganizationId(reset.userId(), reset.organizationId())
                .filter(u -> u.status() == UserStatus.ACTIVE || u.status() == UserStatus.LOCKED)
                .orElseThrow(() -> new BusinessException(CoreErrorCode.RESET_TOKEN_INVALID));
        List<String> recent = new ArrayList<>(history.findRecentHashes(user.organizationId(), user.id()));
        if (user.passwordHash() != null) {
            recent.add(user.passwordHash());
        }
        policy.check("newPassword", newPassword, user.loginId(), user.email(), recent);
        String hash = encoder.encode(newPassword);
        users.updatePassword(user.id(), user.organizationId(), hash, false, now);
        history.push(user.organizationId(), user.id(), hash, now);
        resetTokens.markUsed(reset.id(), reset.organizationId(), now);
        if (user.status() == UserStatus.LOCKED) {
            users.updateStatus(user.id(), user.organizationId(), UserStatus.ACTIVE, now);
            events.userStateChanged(user.organizationId(), user.id(), UserStatus.LOCKED.name(), UserStatus.ACTIVE.name(), "PASSWORD_RESET");
        }
        List<?> sids = revocations.revokeAll(user.organizationId(), user.id(), RevokeReason.PASSWORD_CHANGED, null);
        audits.record(audits.event(user.organizationId(), AuditCodes.PASSWORD_RESET_COMPLETED)
                .actor(AuditActorType.USER, Long.toString(user.id()), null).target("USER", Long.toString(user.id()))
                .detail("revokedSessions", sids.size()));
    }
}
