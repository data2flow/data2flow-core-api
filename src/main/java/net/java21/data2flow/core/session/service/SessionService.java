package net.java21.data2flow.core.session.service;

import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.contracts.audit.AuditResult;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.account.domain.AppUser;
import net.java21.data2flow.core.account.domain.UserStatus;
import net.java21.data2flow.core.account.event.IamEventPublisher;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.audit.service.AuditCodes;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.organization.domain.OrganizationModels.SecurityPolicy;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import net.java21.data2flow.core.session.domain.RefreshToken;
import net.java21.data2flow.core.session.domain.RefreshToken.RevokeReason;
import net.java21.data2flow.core.session.dto.SessionDtos.CreateRefreshTokenRequest;
import net.java21.data2flow.core.session.dto.SessionDtos.RefreshTokenResponse;
import net.java21.data2flow.core.session.dto.SessionDtos.RevocationsResponse;
import net.java21.data2flow.core.session.dto.SessionDtos.RevokeAllResponse;
import net.java21.data2flow.core.session.dto.SessionDtos.RotateRefreshTokenRequest;
import net.java21.data2flow.core.session.dto.SessionDtos.RotateRefreshTokenResponse;
import net.java21.data2flow.core.session.dto.SessionDtos.SessionResponse;
import net.java21.data2flow.core.session.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Refresh 계보(IAM-07.03)·활성 로그인 목록(IAM-03.02, IAM-07.08)·강제 종료(IAM-03.03)·폐기 원천(IAM-07.05, ADR-022).
 *
 * <p>회전 판정(design/auth.md §4.1, crowfoot 준용):
 * <ul>
 *   <li>ROTATED: 정상 사용 → 새 Refresh 저장, 이전 것은 rotated</li>
 *   <li>GRACE: 회전 후 30초 안 재사용(여러 탭) → 계보의 최신 토큰으로 처리</li>
 *   <li>재사용 탐지: 그 밖의 재사용·폐기 토큰 사용 → sid 계보 전체 폐기 + bl:sid 등록 + 감사 REFRESH_REUSED + 보안 경고 → 401
 *       AUTH_SESSION_REVOKED. 폐기는 판정 트랜잭션이 끝난 뒤 별도 트랜잭션으로 커밋한다(예외 응답에도 롤백되지 않게)</li>
 * </ul>
 */
@Service
public class SessionService {

    /** API-IAM-39a 기본 조회 범위(Access 수명 60분) */
    static final Duration DEFAULT_REVOCATION_WINDOW = Duration.ofMinutes(60);
    static final Duration MAX_REVOCATION_WINDOW = Duration.ofHours(24);
    static final int MAX_REVOCATIONS = 10_000;

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final SessionRevocations revocations;
    private final RoleChecker roleChecker;
    private final IamEventPublisher events;
    private final Audits audits;
    private final CoreProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;

    public SessionService(RefreshTokenRepository tokens, UserRepository users, OrganizationRepository organizations,
                          SessionRevocations revocations, RoleChecker roleChecker, IamEventPublisher events, Audits audits,
                          CoreProperties properties, Clock clock, PlatformTransactionManager txManager) {
        this.tokens = tokens;
        this.users = users;
        this.organizations = organizations;
        this.revocations = revocations;
        this.roleChecker = roleChecker;
        this.events = events;
        this.audits = audits;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** API-IAM-36 로그인 성공 시 계보 등록. 같은 jti로 다시 오면 그대로 돌려준다(재시도 멱등) */
    @Transactional
    public RefreshTokenResponse register(CreateRefreshTokenRequest req) {
        UUID jti = UUID.fromString(req.jti());
        UUID sid = UUID.fromString(req.sid());
        long orgId = Long.parseLong(req.orgId());
        long userId = Long.parseLong(req.userId());
        var existing = tokens.findByJti(jti);
        if (existing.isPresent()) {
            RefreshToken t = existing.get();
            if (!t.sessionId().equals(sid) || t.userId() != userId || !t.tokenHash().equals(req.tokenHash())) {
                throw invalid("jti", "DUPLICATED");
            }
            return new RefreshTokenResponse(t.jti().toString(), t.sessionId().toString(), t.expiresAt());
        }
        AppUser user = users.findByIdAndOrganizationId(userId, orgId)
                .orElseThrow(() -> new BusinessException(CoreErrorCode.USER_NOT_FOUND));
        if (user.status() != UserStatus.ACTIVE) {
            throw new BusinessException(CommonErrorCode.AUTH_SESSION_REVOKED);
        }
        Instant now = clock.instant();
        Instant expires = min(req.expiresAt(), req.absoluteExpiresAt());
        tokens.insert(new RefreshToken(jti, sid, orgId, userId, req.tokenHash(), now, now, expires, req.absoluteExpiresAt(),
                null, null, null, req.ip(), req.userAgent()));
        return new RefreshTokenResponse(jti.toString(), sid.toString(), expires);
    }

    /** API-IAM-35 회전 판정 */
    public RotateRefreshTokenResponse rotate(RotateRefreshTokenRequest req) {
        UUID presentedJti = UUID.fromString(req.presentedJti());
        RotateOutcome outcome = tx.execute(status -> decide(presentedJti, req));
        if (outcome == null) {
            throw new BusinessException(CommonErrorCode.AUTH_TOKEN_INVALID);
        }
        if (outcome.reuse() != null) {
            RefreshToken reused = outcome.reuse();
            newTx.executeWithoutResult(status -> handleReuse(reused));
            throw new BusinessException(CommonErrorCode.AUTH_SESSION_REVOKED);
        }
        if (outcome.error() != null) {
            throw new BusinessException(outcome.error());
        }
        return outcome.response();
    }

    private RotateOutcome decide(UUID presentedJti, RotateRefreshTokenRequest req) {
        RefreshToken presented = tokens.lockByJti(presentedJti).orElse(null);
        if (presented == null) {
            return RotateOutcome.error(CommonErrorCode.AUTH_TOKEN_INVALID);
        }
        Instant now = clock.instant();
        AppUser user = users.findByIdAndOrganizationId(presented.userId(), presented.organizationId()).orElse(null);
        if (user == null || user.status() != UserStatus.ACTIVE) {
            return RotateOutcome.error(CommonErrorCode.AUTH_SESSION_REVOKED);
        }
        if (presented.revoked()) {
            return RotateOutcome.reuse(presented);
        }
        if (presented.rotatedAt() != null) {
            if (Duration.between(presented.rotatedAt(), now).compareTo(properties.tokens().refreshGrace()) > 0) {
                return RotateOutcome.reuse(presented);
            }
            RefreshToken latest = tokens.findLatestActiveInSession(presented.sessionId()).orElse(null);
            if (latest == null) {
                return RotateOutcome.reuse(presented);
            }
            tokens.touch(latest.jti(), now);
            return RotateOutcome.ok(response("GRACE", latest, latest.expiresAt()));
        }
        if (!presented.expiresAt().isAfter(now) || !presented.absoluteExpiresAt().isAfter(now) || idleExpired(presented, now)) {
            return RotateOutcome.error(CommonErrorCode.AUTH_SESSION_EXPIRED);
        }
        UUID nextJti = UUID.fromString(req.nextJti());
        Instant nextExpires = min(req.nextExpiresAt(), presented.absoluteExpiresAt());
        tokens.markRotated(presented.jti(), now);
        RefreshToken next = new RefreshToken(nextJti, presented.sessionId(), presented.organizationId(), presented.userId(),
                req.nextTokenHash(), now, now, nextExpires, presented.absoluteExpiresAt(), null, null, null, presented.ip(),
                presented.userAgent());
        tokens.insert(next);
        return RotateOutcome.ok(response("ROTATED", next, nextExpires));
    }

    /** BR-IAM-15 유휴 만료(조직 정책, 기본 30분) */
    private boolean idleExpired(RefreshToken token, Instant now) {
        SecurityPolicy policy = organizations.findPolicy(token.organizationId())
                .orElse(SecurityPolicy.defaults(token.organizationId()));
        return !token.lastUsedAt().plus(Duration.ofMinutes(policy.sessionIdleMinutes())).isAfter(now);
    }

    private void handleReuse(RefreshToken reused) {
        long orgId = reused.organizationId();
        revocations.revokeSession(orgId, reused.sessionId(), RevokeReason.REUSE_DETECTED, true);
        audits.record(audits.event(orgId, AuditCodes.REFRESH_REUSED)
                .actor(AuditActorType.USER, Long.toString(reused.userId()), null)
                .target("SESSION", reused.sessionId().toString()).result(AuditResult.FAILURE)
                .ip(reused.ip()).userAgent(reused.userAgent())
                .detail("jti", reused.jti().toString()).detail("alreadyRevoked", reused.revoked()));
        events.securityAlert(orgId, "REFRESH_REUSED", reused.userId(), reused.ip(),
                Map.of("sid", reused.sessionId().toString()));
    }

    /** API-IAM-37 jti 하나 폐기(로그아웃). 없어도 성공(멱등). auth가 이미 bl:sid를 등록하므로 다시 알리지 않는다 */
    @Transactional
    public void revokeJti(String jti, String reason) {
        tokens.revokeJti(parseUuid(jti, "jti"), reasonOf(reason).name(), clock.instant());
    }

    /** API-IAM-37 sid 계보 폐기(로그아웃). 없어도 성공(멱등) */
    @Transactional
    public void revokeSessionInternal(String sid, String reason) {
        tokens.revokeSession(parseUuid(sid, "sid"), reasonOf(reason).name(), clock.instant());
    }

    /** API-IAM-39a 최근 폐기 sid·jti(auth Redis 재적재, ADR-022) */
    @Transactional(readOnly = true)
    public RevocationsResponse revocationsSince(Instant since) {
        Instant now = clock.instant();
        Instant from = since == null ? now.minus(DEFAULT_REVOCATION_WINDOW) : since;
        if (from.isBefore(now.minus(MAX_REVOCATION_WINDOW))) {
            from = now.minus(MAX_REVOCATION_WINDOW);
        }
        Set<String> sids = new LinkedHashSet<>();
        Set<String> jtis = new LinkedHashSet<>();
        for (RefreshToken t : tokens.findRevokedSince(from, MAX_REVOCATIONS)) {
            sids.add(t.sessionId().toString());
            jtis.add(t.jti().toString());
        }
        return new RevocationsResponse(List.copyOf(sids), List.copyOf(jtis));
    }

    /** API-IAM-16 내 활성 로그인(계열당 한 줄). currentSid는 BFF가 보낸 X-SESSION-ID(현재 세션 표시용) */
    @Transactional(readOnly = true)
    public List<SessionResponse> mySessions(String currentSid) {
        CurrentUser user = roleChecker.currentUser();
        return tokens.listActiveSessions(user.organizationId(), user.userId(), clock.instant()).stream()
                .map(s -> new SessionResponse(s.sessionId().toString(), s.userAgent(), s.ip(), s.firstLoginAt(), s.lastUsedAt(),
                        s.sessionId().toString().equalsIgnoreCase(currentSid)))
                .toList();
    }

    /** API-IAM-17 내 세션 하나 종료(AT-IAM-08.4: 그 sid만 폐기) */
    @Transactional
    public void revokeMySession(String sid) {
        CurrentUser user = roleChecker.currentUser();
        UUID sessionId;
        try {
            sessionId = UUID.fromString(sid);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!tokens.existsSessionOfUser(user.organizationId(), user.userId(), sessionId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        revocations.revokeSession(user.organizationId(), sessionId, RevokeReason.FORCED, true);
        audits.record(audits.event(user.organizationId(), AuditCodes.SESSION_REVOKED).actor(user)
                .target("SESSION", sessionId.toString()).detail("by", "SELF"));
    }

    /** API-IAM-24 관리자가 사용자의 모든 세션 강제 종료(IAM-03.03) */
    @Transactional
    public RevokeAllResponse revokeAllByAdmin(long userId) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser admin = roleChecker.currentUser();
        AppUser target = users.findByIdAndOrganizationId(userId, admin.organizationId())
                .orElseThrow(() -> new BusinessException(CoreErrorCode.USER_NOT_FOUND));
        List<UUID> sids = revocations.revokeAll(admin.organizationId(), target.id(), RevokeReason.FORCED, null);
        audits.record(audits.event(admin.organizationId(), AuditCodes.SESSION_REVOKED).actor(admin)
                .target("USER", Long.toString(target.id())).detail("revokedSessions", sids.size()).detail("by", "ADMIN"));
        return new RevokeAllResponse(sids.size());
    }

    private static RotateRefreshTokenResponse response(String decision, RefreshToken t, Instant expiresAt) {
        return new RotateRefreshTokenResponse(decision, t.jti().toString(), t.sessionId().toString(), Long.toString(t.userId()),
                Long.toString(t.organizationId()), expiresAt);
    }

    private static RevokeReason reasonOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return RevokeReason.LOGOUT;
        }
        try {
            return RevokeReason.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalid("reason", "INVALID");
        }
    }

    private static UUID parseUuid(String raw, String field) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw invalid(field, "Pattern");
        }
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }

    private record RotateOutcome(RotateRefreshTokenResponse response, RefreshToken reuse,
                                 net.java21.data2flow.contracts.error.ErrorCode error) {
        static RotateOutcome ok(RotateRefreshTokenResponse response) {
            return new RotateOutcome(response, null, null);
        }

        static RotateOutcome reuse(RefreshToken token) {
            return new RotateOutcome(null, token, null);
        }

        static RotateOutcome error(net.java21.data2flow.contracts.error.ErrorCode code) {
            return new RotateOutcome(null, null, code);
        }
    }
}
