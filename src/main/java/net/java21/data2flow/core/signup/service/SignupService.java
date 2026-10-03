package net.java21.data2flow.core.signup.service;

import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.account.domain.UserStatus;
import net.java21.data2flow.core.account.event.IamEventPublisher;
import net.java21.data2flow.core.account.repository.PasswordHistoryRepository;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.account.repository.UserRepository.NewUser;
import net.java21.data2flow.core.account.service.LoginIdRules;
import net.java21.data2flow.core.account.service.PasswordPolicy;
import net.java21.data2flow.core.audit.service.AuditCodes;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.AfterCommit;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.common.Identity;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.mail.service.MailLinks;
import net.java21.data2flow.core.mail.service.MailService;
import net.java21.data2flow.core.organization.domain.OrganizationModels.OrgSettings;
import net.java21.data2flow.core.organization.domain.OrganizationModels.Organization;
import net.java21.data2flow.core.organization.domain.OrganizationModels.SecurityPolicy;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import net.java21.data2flow.core.role.repository.RoleRepository;
import net.java21.data2flow.core.role.service.RoleAssignments;
import net.java21.data2flow.core.role.service.RoleAssignments.Assignment;
import net.java21.data2flow.core.signup.dto.SignupDtos.ApproveSignupRequest;
import net.java21.data2flow.core.signup.dto.SignupDtos.CreateSignupRequest;
import net.java21.data2flow.core.signup.dto.SignupDtos.SignupDecisionResponse;
import net.java21.data2flow.core.signup.dto.SignupDtos.SignupSummaryResponse;
import net.java21.data2flow.core.signup.repository.SignupRequestRepository;
import net.java21.data2flow.core.signup.repository.SignupRequestRepository.SignupRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 관리자 승인형 가입 신청(IAM-01.08, ADR-032, BR-IAM-28·29). 즉시 사용하는 셀프 가입과 조직 생성은 없다(IAM-01.06):
 * 신청은 기존 조직의 승인 대기 계정만 만든다(AT-IAM-20.3).
 *
 * <ol>
 *   <li>신청: 조직 설정이 켜져 있어야 한다(꺼짐 404 SIGNUP_DISABLED). 허용 도메인, 아이디 중복(409), 비밀번호 정책을 본다.
 *       이미 등록·신청된 이메일이면 새 신청 없이 같은 202와 "이미 신청됨" 메일(계정 존재 은닉). IP당 시간당 5회(429).</li>
 *   <li>이메일 확인(24시간): PENDING_APPROVAL 사용자 행을 만든다(역할 없음). 이 상태로 로그인하면 403 AUTH_PENDING_APPROVAL.</li>
 *   <li>ADMIN 승인(역할·공간 지정 필수) → ACTIVE, 또는 거절(사유 2~200자) → 사용자 행 삭제. 둘 다 신청자에게 메일과 감사 기록.</li>
 * </ol>
 */
@Service
public class SignupService {

    static final int IP_LIMIT = 5;
    static final Duration IP_WINDOW = Duration.ofHours(1);
    private static final Set<String> LIST_STATUSES = Set.of("PENDING_APPROVAL", "APPROVED", "REJECTED", "EXPIRED");

    private final SignupRequestRepository signups;
    private final UserRepository users;
    private final RoleRepository roles;
    private final OrganizationRepository organizations;
    private final PasswordHistoryRepository history;
    private final RoleAssignments assignments;
    private final PasswordPolicy policy;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final MailLinks links;
    private final RoleChecker roleChecker;
    private final IamEventPublisher events;
    private final Audits audits;
    private final CoreProperties properties;
    private final Clock clock;

    public SignupService(SignupRequestRepository signups, UserRepository users, RoleRepository roles,
                         OrganizationRepository organizations, PasswordHistoryRepository history, RoleAssignments assignments,
                         PasswordPolicy policy, PasswordEncoder encoder, MailService mail, MailLinks links, RoleChecker roleChecker,
                         IamEventPublisher events, Audits audits, CoreProperties properties, Clock clock) {
        this.signups = signups;
        this.users = users;
        this.roles = roles;
        this.organizations = organizations;
        this.history = history;
        this.assignments = assignments;
        this.policy = policy;
        this.encoder = encoder;
        this.mail = mail;
        this.links = links;
        this.roleChecker = roleChecker;
        this.events = events;
        this.audits = audits;
        this.properties = properties;
        this.clock = clock;
    }

    /** API-IAM-67 신청(공개). 성공·이메일 중복 모두 202 */
    @Transactional
    public void create(CreateSignupRequest req, String ip) {
        Organization org = organizations.findSingleActive().orElseThrow(() -> new BusinessException(CoreErrorCode.SIGNUP_DISABLED));
        SecurityPolicy p = organizations.findPolicy(org.id()).orElse(SecurityPolicy.defaults(org.id()));
        if (!p.signupRequestEnabled()) {
            throw new BusinessException(CoreErrorCode.SIGNUP_DISABLED);
        }
        Instant now = clock.instant();
        String inet = net.java21.data2flow.core.common.Pg.inetOrNull(ip);
        if (inet != null) {
            List<Instant> recent = signups.listCreatedSinceByIp(org.id(), inet, now.minus(IP_WINDOW));
            if (recent.size() >= IP_LIMIT) {
                long retry = Math.max(1, Duration.between(now, recent.get(0).plus(IP_WINDOW)).toSeconds());
                throw new BusinessException(CoreErrorCode.SIGNUP_RATE_LIMITED).withHeader("Retry-After", Long.toString(retry));
            }
        }
        String email = req.email().strip();
        if (!email.matches("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("email", "Email", null)));
        }
        String domain = email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT);
        if (!p.signupAllowedDomains().isEmpty() && !p.signupAllowedDomains().contains(domain)) {
            throw new BusinessException(CoreErrorCode.SIGNUP_DOMAIN_NOT_ALLOWED);
        }
        if (!Boolean.TRUE.equals(req.privacyConsent())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("privacyConsent", "AssertTrue", null)));
        }
        String loginId = LoginIdRules.normalize(req.loginId());
        if (users.existsLoginId(org.id(), loginId) || signups.existsOpenLoginId(org.id(), loginId)) {
            throw new BusinessException(CoreErrorCode.LOGIN_ID_DUPLICATED);
        }
        OrgSettings settings = organizations.findSettings(org.id()).orElse(null);
        Locale locale = MailLinks.locale(settings == null ? "ko" : settings.locale());
        String orgName = settings == null ? org.name() : settings.displayName();
        if (users.findByEmail(org.id(), email).isPresent() || signups.existsOpenEmail(org.id(), email)) {
            AfterCommit.run("가입 중복 안내 메일", () -> mail.send(org.id(), email, "mail.signup-duplicate", locale, orgName));
            return;
        }
        policy.check("password", req.password(), loginId, email, List.of());
        String token = Tokens.newToken();
        long id = signups.insert(org.id(), email, req.name().strip(), loginId, encoder.encode(req.password()),
                req.message(), Tokens.sha256Hex(token), inet, now);
        audits.record(audits.event(org.id(), AuditCodes.SIGNUP_REQUESTED).actor(AuditActorType.SYSTEM, null, null)
                .target("SIGNUP_REQUEST", Long.toString(id)).ip(inet).detail("loginId", loginId));
        String link = links.link("/signup/verify/" + token, locale);
        String expires = MailLinks.format(now.plus(properties.tokens().signupVerify()), settings == null ? "Asia/Seoul" : settings.timezone());
        AfterCommit.run("가입 확인 메일", () -> mail.send(org.id(), email, "mail.signup-verify", locale, orgName, link, expires));
    }

    /** API-IAM-68 이메일 확인(공개) → PENDING_APPROVAL 사용자 생성. 만료·사용됨 410 */
    @Transactional
    public void verify(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            throw new BusinessException(CoreErrorCode.SIGNUP_REQUEST_INVALID);
        }
        Instant now = clock.instant();
        SignupRequest req = signups.lockByVerifyTokenHash(Tokens.sha256Hex(token))
                .filter(r -> "PENDING_VERIFICATION".equals(r.status()))
                .filter(r -> r.createdAt().plus(properties.tokens().signupVerify()).isAfter(now))
                .orElseThrow(() -> new BusinessException(CoreErrorCode.SIGNUP_REQUEST_INVALID));
        long orgId = req.organizationId();
        if (users.existsLoginId(orgId, req.loginId())) {
            throw new BusinessException(CoreErrorCode.LOGIN_ID_DUPLICATED);
        }
        if (users.findByEmail(orgId, req.email()).isPresent()) {
            throw new BusinessException(CoreErrorCode.SIGNUP_REQUEST_INVALID);
        }
        OrgSettings settings = organizations.findSettings(orgId).orElse(null);
        long userId = users.insert(new NewUser(orgId, req.loginId(), req.email(), req.name(), null,
                settings == null ? "ko" : settings.locale(), settings == null ? "Asia/Seoul" : settings.timezone(),
                UserStatus.PENDING_APPROVAL, req.passwordHash(), false, now));
        signups.markVerified(req.id(), orgId, userId, now);
    }

    /** API-IAM-69 목록(ADMIN). 이메일 확인 전 신청은 보이지 않는다 */
    @Transactional(readOnly = true)
    public ListApiResponse<SignupSummaryResponse> list(String status, Integer page, Integer size) {
        Identity.require();
        roleChecker.requireAdmin();
        long orgId = roleChecker.currentUser().organizationId();
        String filter = status == null || status.isBlank() ? "PENDING_APPROVAL" : status.strip().toUpperCase(Locale.ROOT);
        if (!LIST_STATUSES.contains(filter)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("status", "INVALID", null)));
        }
        PageParams params = PageParams.of(page, size);
        List<SignupSummaryResponse> items = signups.list(orgId, filter, params.size(), params.offset()).stream()
                .map(r -> new SignupSummaryResponse(Long.toString(r.id()), r.email(), r.name(), r.loginId(), r.message(), r.status(),
                        r.emailVerifiedAt(), r.requestIp(), r.createdAt()))
                .toList();
        return ListApiResponse.of(params, items, signups.count(orgId, filter));
    }

    /** API-IAM-69 승인: 역할·공간 범위 필수(없으면 400). 사용자 ACTIVE + 역할, 메일, 감사 SIGNUP_APPROVED */
    @Transactional
    public SignupDecisionResponse approve(long signupRequestId, ApproveSignupRequest req) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser admin = roleChecker.currentUser();
        long orgId = admin.organizationId();
        if (req == null || req.role() == null || req.role().isBlank() || req.spaceScope() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(
                    new FieldErrorDetail(req == null || req.role() == null || req.role().isBlank() ? "role" : "spaceScope", "NotNull", null)));
        }
        SignupRequest signup = pendingApproval(signupRequestId, orgId);
        Assignment assignment = assignments.validate(orgId, req.role(), req.customRoleId(), req.spaceScope());
        Instant now = clock.instant();
        long userId = signup.userId();
        users.activate(userId, orgId, null, null, false, now);
        history.push(orgId, userId, signup.passwordHash(), now);
        roles.upsertUserRole(orgId, userId, assignment.role(), assignment.customRoleId(), assignment.spaceScope(), admin.userId(), now);
        signups.markDecided(signup.id(), orgId, "APPROVED", admin.userId(), null, now);
        audits.record(audits.event(orgId, AuditCodes.SIGNUP_APPROVED).actor(admin).target("USER", Long.toString(userId))
                .detail("signupRequestId", Long.toString(signup.id())).detail("role", assignment.role())
                .detail("spaceScope", assignment.spaceScope()));
        events.userStateChanged(orgId, userId, UserStatus.PENDING_APPROVAL.name(), UserStatus.ACTIVE.name(), "SIGNUP_APPROVED");
        Locale locale = userLocale(orgId);
        String link = links.link("/login", locale);
        String email = signup.email();
        String name = signup.name();
        AfterCommit.run("가입 승인 메일", () -> mail.send(orgId, email, "mail.signup-approved", locale, name, link));
        return new SignupDecisionResponse(Long.toString(signup.id()), "APPROVED", Long.toString(userId), null,
                Long.toString(admin.userId()), now);
    }

    /** API-IAM-69 거절: 사유 2~200자 필수. 사용자 행 삭제(같은 아이디로 다시 신청 가능), 메일에 사유, 감사 SIGNUP_REJECTED */
    @Transactional
    public SignupDecisionResponse reject(long signupRequestId, String rawReason) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser admin = roleChecker.currentUser();
        long orgId = admin.organizationId();
        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.length() < 2 || reason.length() > 200) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("reason", "Size", null)));
        }
        SignupRequest signup = pendingApproval(signupRequestId, orgId);
        Instant now = clock.instant();
        signups.markDecided(signup.id(), orgId, "REJECTED", admin.userId(), reason, now);
        users.deleteUnactivated(signup.userId(), orgId);
        audits.record(audits.event(orgId, AuditCodes.SIGNUP_REJECTED).actor(admin)
                .target("SIGNUP_REQUEST", Long.toString(signup.id())).detail("reason", reason));
        Locale locale = userLocale(orgId);
        String email = signup.email();
        String name = signup.name();
        AfterCommit.run("가입 거절 메일", () -> mail.send(orgId, email, "mail.signup-rejected", locale, name, reason));
        return new SignupDecisionResponse(Long.toString(signup.id()), "REJECTED", null, reason, Long.toString(admin.userId()), now);
    }

    /** 만료 정리(§3.4): 확인 24시간·승인 대기 14일이 지나면 EXPIRED, 승인 대기 사용자 행은 지운다 */
    @Transactional
    public int expire() {
        Instant now = clock.instant();
        List<SignupRequest> expired = signups.lockExpired(now.minus(properties.tokens().signupVerify()),
                now.minus(properties.tokens().signupApproval()));
        for (SignupRequest r : expired) {
            signups.markExpired(r.id(), r.organizationId(), now);
            if (r.userId() != null) {
                users.deleteUnactivated(r.userId(), r.organizationId());
            }
        }
        return expired.size();
    }

    private SignupRequest pendingApproval(long id, long orgId) {
        SignupRequest signup = signups.lockByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!"PENDING_APPROVAL".equals(signup.status()) || signup.userId() == null) {
            throw new BusinessException(CoreErrorCode.USER_STATE_CONFLICT);
        }
        return signup;
    }

    private Locale userLocale(long orgId) {
        return MailLinks.locale(organizations.findSettings(orgId).map(OrgSettings::locale).orElse("ko"));
    }
}
