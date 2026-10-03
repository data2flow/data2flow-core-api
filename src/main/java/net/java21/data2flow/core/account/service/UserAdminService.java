package net.java21.data2flow.core.account.service;

import net.java21.data2flow.contracts.audit.AuditEvent;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.contracts.web.SortParams;
import net.java21.data2flow.core.account.domain.AppUser;
import net.java21.data2flow.core.account.domain.UserStatus;
import net.java21.data2flow.core.account.dto.AccountDtos.ChangeRoleRequest;
import net.java21.data2flow.core.account.dto.AccountDtos.ChangeRoleResponse;
import net.java21.data2flow.core.account.dto.AccountDtos.CreateUserRequest;
import net.java21.data2flow.core.account.dto.AccountDtos.CreateUserResponse;
import net.java21.data2flow.core.account.dto.AccountDtos.SpaceRef;
import net.java21.data2flow.core.account.dto.AccountDtos.UserDetailResponse;
import net.java21.data2flow.core.account.dto.AccountDtos.UserSummaryResponse;
import net.java21.data2flow.core.account.event.IamEventPublisher;
import net.java21.data2flow.core.account.repository.PasswordHistoryRepository;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.account.repository.UserRepository.NewUser;
import net.java21.data2flow.core.account.repository.UserRepository.UserListRow;
import net.java21.data2flow.core.account.repository.UserRepository.UserSearch;
import net.java21.data2flow.core.audit.service.AuditCodes;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.organization.domain.OrganizationModels.OrgSettings;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import net.java21.data2flow.core.role.domain.RoleModels.UserRole;
import net.java21.data2flow.core.role.repository.RoleRepository;
import net.java21.data2flow.core.role.repository.SpaceScopeRepository;
import net.java21.data2flow.core.role.service.RoleAssignments;
import net.java21.data2flow.core.role.service.RoleAssignments.Assignment;
import net.java21.data2flow.core.session.domain.RefreshToken.RevokeReason;
import net.java21.data2flow.core.session.repository.RefreshTokenRepository;
import net.java21.data2flow.core.session.service.SessionRevocations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 회원 관리(IAM-01.03 직접 생성, IAM-01.04 비활성화·삭제, IAM-01.07 목록·역할 변경, IAM-01.10 상태 전이, IAM-02.03 잠금 해제).
 * 모두 ADMIN(IAM_MANAGE)이고 장기 토큰으로는 할 수 없다(BR-IAM-19). 마지막 ACTIVE ADMIN은 남겨야 하고(BR-IAM-07, 먼저 판정 —
 * ADMIN이 1명뿐일 때 자기 역할 변경은 AT-IAM-09.2대로 LAST_ADMIN_REQUIRED), 그 밖의 자기 역할·상태 변경은 거부한다(BR-IAM-08). 회원 목록·상세 조회는 개인정보 접속 기록으로 남긴다(NFR-12.02).
 */
@Service
public class UserAdminService {

    static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "u.name", "loginId", "u.login_id", "createdAt", "u.created_at", "lastLoginAt", "u.last_login_at", "status", "u.status");

    private final UserRepository users;
    private final RoleRepository roles;
    private final OrganizationRepository organizations;
    private final PasswordHistoryRepository history;
    private final RefreshTokenRepository tokens;
    private final RoleAssignments assignments;
    private final SpaceScopeRepository spaceNames;
    private final PasswordPolicy policy;
    private final PasswordEncoder encoder;
    private final SessionRevocations revocations;
    private final RoleChecker roleChecker;
    private final IamEventPublisher events;
    private final Audits audits;
    private final Clock clock;

    public UserAdminService(UserRepository users, RoleRepository roles, OrganizationRepository organizations,
                            PasswordHistoryRepository history, RefreshTokenRepository tokens, RoleAssignments assignments,
                            PasswordPolicy policy, PasswordEncoder encoder, SessionRevocations revocations,
                            RoleChecker roleChecker, IamEventPublisher events, Audits audits, Clock clock,
                            SpaceScopeRepository spaceNames) {
        this.spaceNames = spaceNames;
        this.users = users;
        this.roles = roles;
        this.organizations = organizations;
        this.history = history;
        this.tokens = tokens;
        this.assignments = assignments;
        this.policy = policy;
        this.encoder = encoder;
        this.revocations = revocations;
        this.roleChecker = roleChecker;
        this.events = events;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-IAM-31 회원 목록(검색·역할·상태·공간 필터) */
    @Transactional
    public ListApiResponse<UserSummaryResponse> list(String keyword, String role, String status, String spaceId,
                                                     List<String> sort, Integer page, Integer size) {
        CurrentUser admin = requireAdmin();
        PageParams params = PageParams.of(page, size);
        String orderBy = SortParams.parse(sort, SORT_COLUMNS.keySet(), new SortParams.Order("createdAt", false))
                .toOrderBy(SORT_COLUMNS) + ", u.id DESC";
        UserSearch search = new UserSearch(admin.organizationId(), PageParams.keyword(keyword), upper(role), upper(status),
                spaceId == null || !spaceId.matches("\\d{1,18}") ? null : Long.parseLong(spaceId));
        List<UserListRow> rows = users.search(search, orderBy, params.size(), params.offset());
        long total = users.count(search);
        Map<Long, String> names = spaceNames.findNames(admin.organizationId(),
                rows.stream().flatMap(r -> r.spaceScope().stream()).collect(java.util.stream.Collectors.toSet()));
        audits.record(audits.event(admin.organizationId(), AuditCodes.PERSONAL_INFO_ACCESSED).actor(admin)
                .target("USER", null).detail("scope", "USER_LIST").detail("rows", rows.size()));
        return ListApiResponse.of(params, rows.stream().map(r -> toSummary(r, names)).toList(), total);
    }

    /** API-IAM-32 회원 상세. 다른 조직 사용자는 404 USER_NOT_FOUND(BR-IAM-01) */
    @Transactional
    public UserDetailResponse detail(long userId) {
        CurrentUser admin = requireAdmin();
        AppUser user = load(admin, userId);
        UserRole role = roles.findUserRole(admin.organizationId(), userId).orElse(null);
        Map<Long, String> names = role == null ? Map.of() : spaceNames.findNames(admin.organizationId(), role.spaceScope());
        audits.record(audits.event(admin.organizationId(), AuditCodes.PERSONAL_INFO_ACCESSED).actor(admin)
                .target("USER", Long.toString(userId)).detail("scope", "USER_DETAIL"));
        return new UserDetailResponse(Long.toString(user.id()), user.loginId(), user.email(), user.name(), user.phone(),
                user.locale(), user.timezone(), user.status().name(), role == null ? null : role.role(),
                role == null || role.customRoleId() == null ? null : Long.toString(role.customRoleId()),
                role == null ? List.of() : role.spaceScope().stream().map(id -> new SpaceRef(Long.toString(id), names.get(id))).toList(),
                user.totpEnabled(), user.mustChangePassword(), user.lockedUntil(), user.lastLoginAt(), user.lastLoginIp(),
                tokens.countActiveSessions(admin.organizationId(), userId, clock.instant()), user.createdAt(), user.version());
    }

    /**
     * API-IAM-21 직접 생성(IAM-01.03). 임시 비밀번호(없으면 생성해 1회 응답)로 ACTIVE 계정을 만들고, 첫 로그인에서 변경을 강제한다(AT-IAM-05.5).
     */
    @Transactional
    public CreateUserResponse create(CreateUserRequest req) {
        CurrentUser admin = requireAdmin();
        long orgId = admin.organizationId();
        String loginId = LoginIdRules.normalize(req.loginId());
        String email = normalizeEmail(req.email());
        Assignment assignment = assignments.validate(orgId, req.role(), req.customRoleId(), req.spaceScope());
        if (users.existsLoginId(orgId, loginId)) {
            throw new BusinessException(CoreErrorCode.LOGIN_ID_DUPLICATED);
        }
        if (users.findByEmail(orgId, email).isPresent()) {
            throw new BusinessException(CoreErrorCode.EMAIL_DUPLICATED);
        }
        boolean generated = req.temporaryPassword() == null || req.temporaryPassword().isBlank();
        String temporary = generated ? Tokens.newPassword(16) : req.temporaryPassword();
        if (!generated) {
            policy.check("temporaryPassword", temporary, loginId, email, List.of());
        }
        OrgSettings settings = organizations.findSettings(orgId).orElse(null);
        Instant now = clock.instant();
        String hash = encoder.encode(temporary);
        long id = users.insert(new NewUser(orgId, loginId, email, req.name().strip(), null,
                settings == null ? "ko" : settings.locale(), settings == null ? "Asia/Seoul" : settings.timezone(),
                UserStatus.ACTIVE, hash, true, now));
        history.push(orgId, id, hash, now);
        roles.upsertUserRole(orgId, id, assignment.role(), assignment.customRoleId(), assignment.spaceScope(), admin.userId(), now);
        audits.record(audits.event(orgId, AuditCodes.USER_CREATED).actor(admin).target("USER", Long.toString(id))
                .detail("loginId", loginId).detail("role", assignment.role()).detail("method", "DIRECT"));
        return new CreateUserResponse(Long.toString(id), generated ? temporary : null);
    }

    /** API-IAM-23 역할·공간 권한 변경(IAM-01.07). 토큰은 폐기하지 않고 다음 요청부터 반영된다(BR-IAM-13) */
    @Transactional
    public ChangeRoleResponse changeRole(long userId, ChangeRoleRequest req) {
        CurrentUser admin = requireAdmin();
        long orgId = admin.organizationId();
        AppUser user = lock(admin, userId);
        if (user.status() == UserStatus.DELETED || user.status() == UserStatus.INVITED
                || user.status() == UserStatus.PENDING_APPROVAL) {
            throw new BusinessException(CoreErrorCode.USER_STATE_CONFLICT);
        }
        VersionCheck.require(req.baseVersion(), user.version());
        Assignment next = assignments.validate(orgId, req.role(), req.customRoleId(), req.spaceScope());
        UserRole before = roles.findUserRole(orgId, userId).orElse(null);
        if (before != null && "ADMIN".equals(before.role()) && !"ADMIN".equals(next.role())
                && user.status() == UserStatus.ACTIVE && users.countActiveAdmins(orgId) <= 1) {
            throw new BusinessException(CoreErrorCode.LAST_ADMIN_REQUIRED);
        }
        requireNotSelf(admin, userId);
        Instant now = clock.instant();
        VersionCheck.requireUpdated(users.updateVersion(userId, orgId, req.baseVersion(), now));
        roles.upsertUserRole(orgId, userId, next.role(), next.customRoleId(), next.spaceScope(), admin.userId(), now);
        int version = user.version() + 1;
        boolean roleChanged = before == null || !before.role().equals(next.role())
                || !Objects.equals(before.customRoleId(), next.customRoleId());
        boolean scopeChanged = before == null || !before.spaceScope().equals(next.spaceScope());
        if (roleChanged) {
            audits.record(audits.event(orgId, AuditCodes.ROLE_CHANGED).actor(admin).target("USER", Long.toString(userId))
                    .detail("before", before == null ? "NONE" : before.role()).detail("after", next.role()));
        }
        if (scopeChanged) {
            audits.record(audits.event(orgId, AuditCodes.SPACE_SCOPE_CHANGED).actor(admin).target("USER", Long.toString(userId))
                    .detail("before", before == null ? List.of() : before.spaceScope()).detail("after", next.spaceScope()));
        }
        events.permissionChanged(orgId, userId, next.role(), next.customRoleId(), next.spaceScope(), version);
        return new ChangeRoleResponse(Long.toString(userId), next.role(),
                next.customRoleId() == null ? null : Long.toString(next.customRoleId()),
                next.spaceScope().stream().map(String::valueOf).toList(), version);
    }

    /** API-IAM-25 비활성화(IAM-01.04): 모든 세션·장기 토큰 즉시 무효 */
    @Transactional
    public void disable(long userId, String reason) {
        CurrentUser admin = requireAdmin();
        AppUser user = lock(admin, userId);
        transition(user, UserStatus.DISABLED);
        requireOtherAdmin(admin.organizationId(), user);
        requireNotSelf(admin, userId);
        Instant now = clock.instant();
        users.updateStatus(userId, admin.organizationId(), UserStatus.DISABLED, now);
        List<?> sids = revocations.revokeAll(admin.organizationId(), userId, RevokeReason.USER_DISABLED, null);
        int apiTokens = revocations.revokeApiTokensOfUser(admin.organizationId(), userId);
        record(admin, AuditCodes.USER_DISABLED, userId, Map.of("reason", reason == null ? "" : reason,
                "revokedSessions", sids.size(), "revokedApiTokens", apiTokens, "from", user.status().name()));
        events.userStateChanged(admin.organizationId(), userId, user.status().name(), UserStatus.DISABLED.name(), "ADMIN_DISABLED");
    }

    /** API-IAM-26 재활성화(IAM-01.10). 토큰은 복구하지 않는다 */
    @Transactional
    public void enable(long userId) {
        CurrentUser admin = requireAdmin();
        requireNotSelf(admin, userId);
        AppUser user = lock(admin, userId);
        if (user.status() != UserStatus.DISABLED) {
            throw new BusinessException(CoreErrorCode.USER_STATE_CONFLICT);
        }
        users.updateStatus(userId, admin.organizationId(), UserStatus.ACTIVE, clock.instant());
        record(admin, AuditCodes.USER_ENABLED, userId, Map.of("from", user.status().name()));
        events.userStateChanged(admin.organizationId(), userId, user.status().name(), UserStatus.ACTIVE.name(), "ADMIN_ENABLED");
    }

    /** API-IAM-27 잠금 해제(IAM-02.03, AT-IAM-10.3) */
    @Transactional
    public void unlock(long userId) {
        CurrentUser admin = requireAdmin();
        AppUser user = lock(admin, userId);
        if (user.status() != UserStatus.LOCKED) {
            throw new BusinessException(CoreErrorCode.USER_STATE_CONFLICT);
        }
        users.updateStatus(userId, admin.organizationId(), UserStatus.ACTIVE, clock.instant());
        record(admin, AuditCodes.USER_UNLOCKED, userId, Map.of("by", "ADMIN"));
        events.userStateChanged(admin.organizationId(), userId, UserStatus.LOCKED.name(), UserStatus.ACTIVE.name(), "ADMIN_UNLOCKED");
    }

    /** API-IAM-28 삭제 = 익명화(IAM-01.10, NFR-12.01). 감사 로그의 행위자 이름 스냅샷은 남는다(AT-IAM-10.2) */
    @Transactional
    public void delete(long userId, String confirmLoginId) {
        CurrentUser admin = requireAdmin();
        AppUser user = lock(admin, userId);
        transition(user, UserStatus.DELETED);
        String expected = user.loginId() == null ? user.email() : user.loginId();
        if (confirmLoginId == null || !confirmLoginId.strip().equalsIgnoreCase(expected)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("confirmLoginId", "MISMATCH", null)));
        }
        requireOtherAdmin(admin.organizationId(), user);
        requireNotSelf(admin, userId);
        long orgId = admin.organizationId();
        Instant now = clock.instant();
        revocations.revokeAll(orgId, userId, RevokeReason.USER_DISABLED, null);
        revocations.revokeApiTokensOfUser(orgId, userId);
        record(admin, AuditCodes.USER_DELETED, userId, Map.of("from", user.status().name()));
        history.deleteAll(orgId, userId);
        roles.deleteUserRole(orgId, userId);
        users.anonymize(userId, orgId, now);
        events.userStateChanged(orgId, userId, user.status().name(), UserStatus.DELETED.name(), "ADMIN_DELETED");
    }

    private CurrentUser requireAdmin() {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        return roleChecker.currentUser();
    }

    private AppUser load(CurrentUser admin, long userId) {
        return users.findByIdAndOrganizationId(userId, admin.organizationId())
                .orElseThrow(() -> new BusinessException(CoreErrorCode.USER_NOT_FOUND));
    }

    private AppUser lock(CurrentUser admin, long userId) {
        return users.lockByIdAndOrganizationId(userId, admin.organizationId())
                .orElseThrow(() -> new BusinessException(CoreErrorCode.USER_NOT_FOUND));
    }

    private static void requireNotSelf(CurrentUser admin, long userId) {
        if (admin.userId() == userId) {
            throw new BusinessException(CoreErrorCode.SELF_MODIFICATION_FORBIDDEN);
        }
    }

    private static void transition(AppUser user, UserStatus target) {
        if (!user.status().canTransitionTo(target)) {
            throw new BusinessException(CoreErrorCode.USER_STATE_CONFLICT);
        }
    }

    /** BR-IAM-07: 대상이 ACTIVE ADMIN이면 다른 ACTIVE ADMIN이 있어야 한다 */
    private void requireOtherAdmin(long orgId, AppUser user) {
        boolean isAdmin = roles.findUserRole(orgId, user.id()).map(r -> "ADMIN".equals(r.role())).orElse(false);
        if (isAdmin && user.status() == UserStatus.ACTIVE && users.countActiveAdmins(orgId) <= 1) {
            throw new BusinessException(CoreErrorCode.LAST_ADMIN_REQUIRED);
        }
    }

    private void record(CurrentUser admin, String action, long userId, Map<String, Object> detail) {
        AuditEvent.Builder event = audits.event(admin.organizationId(), action).actor(admin).target("USER", Long.toString(userId));
        event.detail(detail);
        audits.record(event);
    }

    /** 공간 범위 요약: 전체면 "ALL", 아니면 첫 공간 이름(+나머지 수), 예: "본관 2층 +2" */
    static UserSummaryResponse toSummary(UserListRow r, Map<Long, String> names) {
        String scope;
        if (r.role() == null) {
            scope = null;
        } else if (r.spaceScope().isEmpty()) {
            scope = "ALL";
        } else {
            Long first = r.spaceScope().getFirst();
            scope = names.getOrDefault(first, first.toString()) + (r.spaceScope().size() > 1 ? " +" + (r.spaceScope().size() - 1) : "");
        }
        return new UserSummaryResponse(Long.toString(r.id()), r.name(), r.loginId(), r.email(), r.role(), scope, r.status(),
                r.mfaEnabled(), r.lastLoginAt());
    }

    static String normalizeEmail(String raw) {
        String email = raw == null ? "" : raw.strip();
        if (!email.matches("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$") || email.length() > 254) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("email", "Email", null)));
        }
        return email;
    }

    private static String upper(String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip().toUpperCase(Locale.ROOT);
    }
}
