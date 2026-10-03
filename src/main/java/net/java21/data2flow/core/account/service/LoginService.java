package net.java21.data2flow.core.account.service;

import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.contracts.audit.AuditResult;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.account.domain.AppUser;
import net.java21.data2flow.core.account.domain.UserStatus;
import net.java21.data2flow.core.account.dto.LoginDtos.VerifyCredentialsRequest;
import net.java21.data2flow.core.account.dto.LoginDtos.VerifyCredentialsResponse;
import net.java21.data2flow.core.account.event.IamEventPublisher;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.audit.service.AuditCodes;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.organization.domain.OrganizationModels.SecurityPolicy;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 로그인 자격 확인(API-IAM-30, design/auth.md §3.2). 비밀번호 해시는 core만 갖고 비교한다(IAM-02.01).
 *
 * <ul>
 *   <li>계정 존재를 숨긴다(BR-IAM-04): 없는 아이디·틀린 비밀번호·잠김·비활성 모두 401 {@code AUTH_INVALID_CREDENTIALS}이고,
 *       없는 아이디도 더미 해시와 비교해 응답 시간을 비슷하게 맞춘다.</li>
 *   <li>연속 실패가 정책 값(기본 5회)에 이르면 15분 잠근다(BR-IAM-05, IAM-02.03). 잠금 중에는 맞는 비밀번호도 실패다.
 *       잠금 시간이 지나면 다음 로그인 때 ACTIVE로 되돌린다.</li>
 *   <li>가입 신청 승인 대기 계정은 비밀번호가 맞을 때만 403 {@code AUTH_PENDING_APPROVAL}(BR-IAM-29).</li>
 * </ul>
 * 실패 횟수 갱신은 커밋한 뒤 예외를 던진다(실패 응답에도 횟수가 남아야 한다).
 */
@Service
public class LoginService {

    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final PasswordEncoder encoder;
    private final Audits audits;
    private final IamEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String dummyHash;

    public LoginService(UserRepository users, OrganizationRepository organizations, PasswordEncoder encoder, Audits audits,
                        IamEventPublisher events, Clock clock, PlatformTransactionManager txManager) {
        this.users = users;
        this.organizations = organizations;
        this.encoder = encoder;
        this.audits = audits;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.dummyHash = encoder.encode("data2flow-timing-equalizer");
    }

    public VerifyCredentialsResponse verify(VerifyCredentialsRequest req) {
        String loginId = req.loginId().strip().toLowerCase(Locale.ROOT);
        Outcome outcome = tx.execute(status -> decide(loginId, req));
        if (outcome == null || outcome.code() == Outcome.Code.FAILED) {
            throw new BusinessException(CommonErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (outcome.code() == Outcome.Code.PENDING_APPROVAL) {
            throw new BusinessException(CoreErrorCode.AUTH_PENDING_APPROVAL);
        }
        return outcome.response();
    }

    private Outcome decide(String loginId, VerifyCredentialsRequest req) {
        List<AppUser> candidates = users.lockByLoginId(loginId);
        if (candidates.size() != 1) {
            encoder.matches(req.password(), dummyHash);
            organizations.findSingleActive().ifPresent(org -> recordFailure(org.id(), null, loginId, req, "UNKNOWN_LOGIN_ID"));
            return Outcome.failed();
        }
        AppUser user = candidates.get(0);
        Instant now = clock.instant();
        UserStatus status = user.status();
        int failures = user.failedLoginCount();
        if (status == UserStatus.LOCKED && user.lockExpired(now)) {
            users.updateStatus(user.id(), user.organizationId(), UserStatus.ACTIVE, now);
            audits.record(audits.event(user.organizationId(), AuditCodes.USER_UNLOCKED)
                    .actor(AuditActorType.SYSTEM, null, null).target("USER", Long.toString(user.id()))
                    .detail("reason", "LOCK_EXPIRED"));
            events.userStateChanged(user.organizationId(), user.id(), UserStatus.LOCKED.name(), UserStatus.ACTIVE.name(), "LOCK_EXPIRED");
            status = UserStatus.ACTIVE;
            failures = 0;
        }
        boolean matches = encoder.matches(req.password(), user.passwordHash() == null ? dummyHash : user.passwordHash())
                && user.passwordHash() != null;
        switch (status) {
            case ACTIVE -> {
                if (matches) {
                    return success(user, req, now);
                }
                registerFailure(user, failures + 1, req, now);
                return Outcome.failed();
            }
            case PENDING_APPROVAL -> {
                if (matches) {
                    return Outcome.pending();
                }
                recordFailure(user.organizationId(), user.id(), loginId, req, "BAD_PASSWORD");
                return Outcome.failed();
            }
            default -> {
                recordFailure(user.organizationId(), user.id(), loginId, req, status.name());
                return Outcome.failed();
            }
        }
    }

    private Outcome success(AppUser user, VerifyCredentialsRequest req, Instant now) {
        users.updateLoginSuccess(user.id(), user.organizationId(), req.ip(), now);
        if (encoder.upgradeEncoding(user.passwordHash())) {
            users.updatePasswordHashOnly(user.id(), user.organizationId(), encoder.encode(req.password()));
        }
        return Outcome.ok(new VerifyCredentialsResponse(Long.toString(user.id()), Long.toString(user.organizationId()),
                user.mustChangePassword(), user.totpEnabled()));
    }

    /** 실패 1회 기록. 정책 횟수에 이르면 잠근다(USER_LOCKED, 보안 경고 BRUTE_FORCE) */
    void registerFailure(AppUser user, int count, VerifyCredentialsRequest req, Instant now) {
        SecurityPolicy policy = organizations.findPolicy(user.organizationId()).orElse(SecurityPolicy.defaults(user.organizationId()));
        Instant lockUntil = count >= policy.loginMaxFailures() ? now.plus(Duration.ofMinutes(policy.lockoutMinutes())) : null;
        users.updateLoginFailure(user.id(), user.organizationId(), count, lockUntil, now);
        recordFailure(user.organizationId(), user.id(), user.loginId(), req, "BAD_PASSWORD");
        if (lockUntil != null) {
            audits.record(audits.event(user.organizationId(), AuditCodes.USER_LOCKED)
                    .actor(AuditActorType.SYSTEM, null, null).target("USER", Long.toString(user.id()))
                    .ip(req.ip()).userAgent(req.userAgent())
                    .detail("failedCount", count).detail("lockedUntil", lockUntil.toString()));
            events.userStateChanged(user.organizationId(), user.id(), UserStatus.ACTIVE.name(), UserStatus.LOCKED.name(), "LOGIN_FAILURES");
            events.securityAlert(user.organizationId(), "BRUTE_FORCE", user.id(), req.ip(), Map.of("failedCount", count));
        }
    }

    private void recordFailure(long orgId, Long userId, String loginId, VerifyCredentialsRequest req, String reason) {
        audits.record(audits.event(orgId, AuditCodes.USER_LOGIN_FAILED)
                .actor(AuditActorType.USER, userId == null ? null : Long.toString(userId), userId == null ? loginId : null)
                .target("USER", userId == null ? null : Long.toString(userId))
                .result(AuditResult.FAILURE).ip(req.ip()).userAgent(req.userAgent())
                .detail("reason", reason));
    }

    private record Outcome(Code code, VerifyCredentialsResponse response) {
        enum Code { OK, FAILED, PENDING_APPROVAL }

        static Outcome ok(VerifyCredentialsResponse response) {
            return new Outcome(Code.OK, response);
        }

        static Outcome failed() {
            return new Outcome(Code.FAILED, null);
        }

        static Outcome pending() {
            return new Outcome(Code.PENDING_APPROVAL, null);
        }
    }
}
