package net.java21.data2flow.core.mfa.service;

import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.contracts.audit.AuditResult;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.core.account.domain.AppUser;
import net.java21.data2flow.core.account.domain.UserStatus;
import net.java21.data2flow.core.account.event.IamEventPublisher;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.audit.service.AuditCodes;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.mfa.dto.MfaDtos.MfaSetupResponse;
import net.java21.data2flow.core.mfa.dto.MfaDtos.MfaVerifyResponse;
import net.java21.data2flow.core.mfa.dto.MfaDtos.RecoveryCodesResponse;
import net.java21.data2flow.core.mfa.repository.RecoveryCodeRepository;
import net.java21.data2flow.core.organization.domain.OrganizationModels.SecurityPolicy;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import net.java21.data2flow.core.role.repository.RoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 2단계 인증 TOTP(IAM-02.05, BR-IAM-25·26·27). core는 비밀값·복구 코드를 보관하고 코드를 확인한다. 로그인 중 코드 입력(API-IAM-62)은
 * auth가 mfaTicket을 발급하고 내부 API-IAM-61b로 확인을 맡긴다.
 *
 * <ul>
 *   <li>등록: 5분 유효 임시 비밀을 암호화해 두고(암호문 안에 발급 시각), 올바른 코드로 확인하면 켜고 복구 코드 10개를 1회 보여 준다.</li>
 *   <li>비밀값은 AES-256-GCM 암호문({@code totp_secret_enc}, context = 사용자 ID, NFR-03.02)이다.</li>
 *   <li>로그인 확인 실패는 비밀번호 실패 횟수에 합산해 잠근다(BR-IAM-27).</li>
 * </ul>
 */
@Service
public class MfaService {

    static final String ISSUER = "data2flow";
    static final int RECOVERY_CODES = 10;

    private final UserRepository users;
    private final RoleRepository roles;
    private final OrganizationRepository organizations;
    private final RecoveryCodeRepository recoveryCodes;
    private final SecretCipher cipher;
    private final PasswordEncoder encoder;
    private final RoleChecker roleChecker;
    private final IamEventPublisher events;
    private final Audits audits;
    private final CoreProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public MfaService(UserRepository users, RoleRepository roles, OrganizationRepository organizations,
                      RecoveryCodeRepository recoveryCodes, SecretCipher cipher, PasswordEncoder encoder, RoleChecker roleChecker,
                      IamEventPublisher events, Audits audits, CoreProperties properties, Clock clock,
                      PlatformTransactionManager txManager) {
        this.users = users;
        this.roles = roles;
        this.organizations = organizations;
        this.recoveryCodes = recoveryCodes;
        this.cipher = cipher;
        this.encoder = encoder;
        this.roleChecker = roleChecker;
        this.events = events;
        this.audits = audits;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /** API-IAM-60 등록 시작. 이미 켜져 있으면 409 */
    @Transactional
    public MfaSetupResponse setup() {
        CurrentUser current = roleChecker.currentUser();
        AppUser user = lock(current);
        if (user.totpEnabled()) {
            throw new BusinessException(CoreErrorCode.USER_STATE_CONFLICT);
        }
        String secret = Totp.newSecret();
        Instant now = clock.instant();
        byte[] pending = cipher.encrypt((secret + "|" + now.getEpochSecond()).getBytes(StandardCharsets.UTF_8), context(user.id()));
        users.updateTotp(user.id(), user.organizationId(), pending, false, now);
        return new MfaSetupResponse(Totp.otpauthUri(ISSUER, user.loginId(), secret), secret);
    }

    /** API-IAM-61 등록 확인 → 켬, 복구 코드 10개(1회 표시), 감사 MFA_ENABLED(AT-IAM-14.1) */
    @Transactional
    public RecoveryCodesResponse confirm(String code) {
        CurrentUser current = roleChecker.currentUser();
        AppUser user = lock(current);
        if (user.totpEnabled() || user.totpSecretEnc() == null) {
            throw new BusinessException(CoreErrorCode.USER_STATE_CONFLICT);
        }
        String[] parts = new String(cipher.decryptBytes(user.totpSecretEnc(), context(user.id())), StandardCharsets.UTF_8).split("\\|");
        Instant now = clock.instant();
        if (parts.length != 2 || Instant.ofEpochSecond(Long.parseLong(parts[1])).plus(properties.tokens().mfaSetup()).isBefore(now)
                || !Totp.verify(parts[0], code, now)) {
            throw new BusinessException(CoreErrorCode.MFA_CODE_INVALID);
        }
        users.updateTotp(user.id(), user.organizationId(), cipher.encrypt(parts[0].getBytes(StandardCharsets.UTF_8), context(user.id())),
                true, now);
        List<String> codes = issueRecoveryCodes(user, now);
        audits.record(audits.event(user.organizationId(), AuditCodes.MFA_ENABLED).actor(current).target("USER", Long.toString(user.id())));
        return new RecoveryCodesResponse(codes);
    }

    /** API-IAM-61a 끄기(현재 비밀번호 확인). 정책상 필수 역할이면 409 */
    @Transactional
    public void disable(String currentPassword) {
        CurrentUser current = roleChecker.currentUser();
        AppUser user = lock(current);
        requirePassword(user, currentPassword);
        if (!user.totpEnabled()) {
            throw new BusinessException(CoreErrorCode.USER_STATE_CONFLICT);
        }
        if (requiredForRole(user)) {
            throw new BusinessException(CoreErrorCode.USER_STATE_CONFLICT);
        }
        clear(user);
        audits.record(audits.event(user.organizationId(), AuditCodes.MFA_DISABLED).actor(current)
                .target("USER", Long.toString(user.id())).detail("by", "SELF"));
    }

    /** API-IAM-61c 복구 코드 재발급(기존 무효) */
    @Transactional
    public RecoveryCodesResponse regenerate(String currentPassword) {
        CurrentUser current = roleChecker.currentUser();
        AppUser user = lock(current);
        requirePassword(user, currentPassword);
        if (!user.totpEnabled()) {
            throw new BusinessException(CoreErrorCode.USER_STATE_CONFLICT);
        }
        return new RecoveryCodesResponse(issueRecoveryCodes(user, clock.instant()));
    }

    /** API-IAM-63 관리자 초기화(분실). 보안 경고 MFA_RESET */
    @Transactional
    public void resetByAdmin(long userId) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser admin = roleChecker.currentUser();
        AppUser user = users.lockByIdAndOrganizationId(userId, admin.organizationId())
                .orElseThrow(() -> new BusinessException(CoreErrorCode.USER_NOT_FOUND));
        clear(user);
        audits.record(audits.event(admin.organizationId(), AuditCodes.MFA_DISABLED).actor(admin)
                .target("USER", Long.toString(userId)).detail("by", "ADMIN"));
        events.securityAlert(admin.organizationId(), "MFA_RESET", userId, null, Map.of("by", Long.toString(admin.userId())));
    }

    /**
     * API-IAM-61b 로그인 중 코드 확인(auth → core). TOTP 6자리 또는 복구 코드. 실패는 401 MFA_CODE_INVALID이고 로그인 실패 횟수에 더한다.
     * 실패 횟수는 커밋한 뒤 예외를 던진다.
     */
    public MfaVerifyResponse verifyForLogin(long userId, String code) {
        VerifyOutcome outcome = tx.execute(status -> decide(userId, code));
        if (outcome == null || outcome.response() == null) {
            throw new BusinessException(CoreErrorCode.MFA_CODE_INVALID);
        }
        return outcome.response();
    }

    private VerifyOutcome decide(long userId, String rawCode) {
        AppUser user = users.lockForInternal(userId).orElse(null);
        if (user == null || user.status() != UserStatus.ACTIVE || !user.totpEnabled() || user.totpSecretEnc() == null) {
            return new VerifyOutcome(null);
        }
        Instant now = clock.instant();
        String code = rawCode == null ? "" : rawCode.strip();
        boolean recovery = false;
        boolean ok;
        if (code.matches("\\d{6}")) {
            String secret = new String(cipher.decryptBytes(user.totpSecretEnc(), context(user.id())), StandardCharsets.UTF_8);
            ok = Totp.verify(secret, code, now);
        } else {
            recovery = true;
            ok = recoveryCodes.useCode(user.organizationId(), user.id(), hashRecovery(code), now);
        }
        if (!ok) {
            SecurityPolicy policy = organizations.findPolicy(user.organizationId()).orElse(SecurityPolicy.defaults(user.organizationId()));
            int count = user.failedLoginCount() + 1;
            Instant lockUntil = count >= policy.loginMaxFailures() ? now.plus(Duration.ofMinutes(policy.lockoutMinutes())) : null;
            users.updateLoginFailure(user.id(), user.organizationId(), count, lockUntil, now);
            audits.record(audits.event(user.organizationId(), AuditCodes.USER_LOGIN_FAILED)
                    .actor(AuditActorType.USER, Long.toString(user.id()), null).target("USER", Long.toString(user.id()))
                    .result(AuditResult.FAILURE).detail("reason", "MFA_CODE_INVALID"));
            if (lockUntil != null) {
                audits.record(audits.event(user.organizationId(), AuditCodes.USER_LOCKED).actor(AuditActorType.SYSTEM, null, null)
                        .target("USER", Long.toString(user.id())).detail("failedCount", count).detail("reason", "MFA"));
                events.userStateChanged(user.organizationId(), user.id(), "ACTIVE", "LOCKED", "MFA_FAILURES");
                events.securityAlert(user.organizationId(), "BRUTE_FORCE", user.id(), null, Map.of("failedCount", count, "factor", "TOTP"));
            }
            return new VerifyOutcome(null);
        }
        users.resetFailures(user.id(), user.organizationId());
        if (recovery) {
            audits.record(audits.event(user.organizationId(), AuditCodes.MFA_RECOVERY_USED)
                    .actor(AuditActorType.USER, Long.toString(user.id()), null).target("USER", Long.toString(user.id())));
        }
        return new VerifyOutcome(new MfaVerifyResponse(true, recovery, recoveryCodes.countUnused(user.organizationId(), user.id())));
    }

    private List<String> issueRecoveryCodes(AppUser user, Instant now) {
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String raw = Tokens.newCode(10);
            String display = raw.substring(0, 5) + "-" + raw.substring(5);
            codes.add(display);
            hashes.add(hashRecovery(display));
        }
        recoveryCodes.replaceAll(user.organizationId(), user.id(), hashes, now);
        return codes;
    }

    private void clear(AppUser user) {
        users.updateTotp(user.id(), user.organizationId(), null, false, clock.instant());
        recoveryCodes.deleteAll(user.organizationId(), user.id());
    }

    private boolean requiredForRole(AppUser user) {
        String role = roles.findUserRole(user.organizationId(), user.id()).map(r -> r.role()).orElse(null);
        SecurityPolicy policy = organizations.findPolicy(user.organizationId()).orElse(SecurityPolicy.defaults(user.organizationId()));
        return role != null && policy.mfaRequiredRoles().contains(role);
    }

    private void requirePassword(AppUser user, String currentPassword) {
        if (user.passwordHash() == null || currentPassword == null || !encoder.matches(currentPassword, user.passwordHash())) {
            throw new BusinessException(CoreErrorCode.PASSWORD_CURRENT_MISMATCH);
        }
    }

    private AppUser lock(CurrentUser current) {
        roleChecker.requireInteractive();
        return users.lockByIdAndOrganizationId(current.userId(), current.organizationId())
                .orElseThrow(() -> new BusinessException(CoreErrorCode.USER_NOT_FOUND));
    }

    /** 복구 코드 해시: 대소문자·하이픈을 무시한다 */
    static String hashRecovery(String code) {
        return Tokens.sha256Hex(code.replace("-", "").strip().toUpperCase(Locale.ROOT));
    }

    static String context(long userId) {
        return "data2flow_core.app_users.totp_secret_enc:" + userId;
    }

    private record VerifyOutcome(MfaVerifyResponse response) {
    }
}
