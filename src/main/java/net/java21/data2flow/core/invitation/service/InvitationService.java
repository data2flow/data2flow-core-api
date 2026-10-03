package net.java21.data2flow.core.invitation.service;

import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.account.domain.AppUser;
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
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.AcceptInvitationRequest;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.AcceptInvitationResponse;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.CreateInvitationsRequest;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.CreateInvitationsResponse;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.InvitationInfoResponse;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.InvitationResult;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.InvitationSummaryResponse;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.LoginIdAvailabilityResponse;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.ResendInvitationResponse;
import net.java21.data2flow.core.invitation.repository.InvitationRepository;
import net.java21.data2flow.core.invitation.repository.InvitationRepository.Invitation;
import net.java21.data2flow.core.mail.service.MailLinks;
import net.java21.data2flow.core.mail.service.MailService;
import net.java21.data2flow.core.organization.domain.OrganizationModels.OrgSettings;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import net.java21.data2flow.core.role.repository.RoleRepository;
import net.java21.data2flow.core.role.service.RoleAssignments;
import net.java21.data2flow.core.role.service.RoleAssignments.Assignment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 초대(IAM-01.03, BR-IAM-09·10): 관리자가 이메일로 초대하면 INVITED 사용자 행과 72시간·1회용 토큰을 만들고 메일을 보낸다.
 * 받는 사람은 링크에서 로그인 아이디와 비밀번호를 정해 ACTIVE가 된다. 재발송은 새 토큰을 만들고 이전 토큰을 무효로 하며 최대 5회다.
 * 토큰 원문은 메일에만 있고 DB에는 SHA-256만 둔다.
 */
@Service
public class InvitationService {

    /** 처음 1회 + 재발송 5회(BR-IAM-09) */
    static final int MAX_SENT = 6;

    private final InvitationRepository invitations;
    private final UserRepository users;
    private final RoleRepository roles;
    private final OrganizationRepository organizations;
    private final PasswordHistoryRepository history;
    private final RoleAssignments assignments;
    private final PasswordPolicy policy;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;
    private final MailService mail;
    private final MailLinks links;
    private final RoleChecker roleChecker;
    private final IamEventPublisher events;
    private final Audits audits;
    private final CoreProperties properties;
    private final Clock clock;

    public InvitationService(InvitationRepository invitations, UserRepository users, RoleRepository roles,
                             OrganizationRepository organizations, PasswordHistoryRepository history, RoleAssignments assignments,
                             PasswordPolicy policy, org.springframework.security.crypto.password.PasswordEncoder encoder,
                             MailService mail, MailLinks links, RoleChecker roleChecker, IamEventPublisher events, Audits audits,
                             CoreProperties properties, Clock clock) {
        this.invitations = invitations;
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

    /** API-IAM-20 초대(이메일 1~20개). 항목별 결과를 돌려준다 */
    @Transactional
    public CreateInvitationsResponse create(CreateInvitationsRequest req) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser admin = roleChecker.currentUser();
        long orgId = admin.organizationId();
        Assignment assignment = assignments.validate(orgId, req.role(), req.customRoleId(), req.spaceScope());
        OrgSettings settings = organizations.findSettings(orgId).orElse(null);
        String orgName = settings == null ? organizations.findById(orgId).map(o -> o.name()).orElse("data2flow") : settings.displayName();
        String locale = settings == null ? "ko" : settings.locale();
        String timezone = settings == null ? "Asia/Seoul" : settings.timezone();
        Set<String> emails = new LinkedHashSet<>();
        for (String raw : req.emails()) {
            emails.add(raw == null ? "" : raw.strip());
        }
        List<InvitationResult> results = new ArrayList<>();
        for (String email : emails) {
            results.add(inviteOne(admin, orgName, locale, timezone, email, req.name(), assignment));
        }
        return new CreateInvitationsResponse(results);
    }

    private InvitationResult inviteOne(CurrentUser admin, String orgName, String locale, String timezone, String email, String name,
                                       Assignment assignment) {
        long orgId = admin.organizationId();
        if (!email.matches("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$") || email.length() > 254) {
            return new InvitationResult(email, "FAILED", null, CommonErrorCode.INVALID_REQUEST.code());
        }
        Instant now = clock.instant();
        AppUser existing = users.findByEmail(orgId, email).orElse(null);
        if (existing != null) {
            if (existing.status() != UserStatus.INVITED) {
                return new InvitationResult(email, "FAILED", null, CoreErrorCode.EMAIL_DUPLICATED.code());
            }
            if (invitations.existsValidPending(orgId, existing.id(), now)) {
                return new InvitationResult(email, "FAILED", null, CoreErrorCode.INVITATION_PENDING_EXISTS.code());
            }
            // 만료·취소된 초대의 INVITED 행은 지우고 새로 초대한다(초대 기록도 함께 지워진다)
            users.deleteUnactivated(existing.id(), orgId);
        }
        String displayName = name == null || name.isBlank() ? email.substring(0, email.indexOf('@')) : name.strip();
        if (displayName.length() > 50) {
            displayName = displayName.substring(0, 50);
        }
        long userId = users.insert(new NewUser(orgId, null, email, displayName, null, locale, timezone, UserStatus.INVITED,
                null, false, now));
        String token = Tokens.newToken();
        Instant expiresAt = now.plus(properties.tokens().invitation());
        long invitationId = invitations.insert(orgId, userId, email, assignment.role(), assignment.customRoleId(),
                assignment.spaceScope(), Tokens.sha256Hex(token), expiresAt, admin.userId(), now);
        audits.record(audits.event(orgId, AuditCodes.USER_INVITED).actor(admin).target("USER", Long.toString(userId))
                .detail("invitationId", Long.toString(invitationId)).detail("role", assignment.role()));
        sendInvitationMail(orgId, email, orgName, token, expiresAt, locale, timezone);
        return new InvitationResult(email, "CREATED", Long.toString(invitationId), null);
    }

    /** API-IAM-10 공개 초대 확인. 만료·사용·취소·없음은 모두 410 INVITATION_INVALID */
    @Transactional
    public InvitationInfoResponse info(String token) {
        Invitation invitation = usable(token);
        String orgName = organizations.findSettings(invitation.organizationId()).map(OrgSettings::displayName)
                .orElse(organizations.findById(invitation.organizationId()).map(o -> o.name()).orElse(""));
        String invitedBy = users.findByIdAndOrganizationId(invitation.invitedBy(), invitation.organizationId())
                .map(AppUser::name).orElse(null);
        return new InvitationInfoResponse(orgName, invitedBy, invitation.role(), invitation.email(), invitation.expiresAt());
    }

    /** API-IAM-10a 아이디 사용 가능 여부(초대 토큰이 있어야 한다). 형식·예약어 위반은 400 LOGIN_ID_INVALID */
    @Transactional
    public LoginIdAvailabilityResponse loginIdAvailability(String token, String loginId) {
        Invitation invitation = usable(token);
        String normalized = LoginIdRules.normalize(loginId);
        if (users.existsLoginId(invitation.organizationId(), normalized)) {
            return new LoginIdAvailabilityResponse(false, "DUPLICATED");
        }
        return new LoginIdAvailabilityResponse(true, null);
    }

    /** API-IAM-11 수락: 아이디·비밀번호 설정 → ACTIVE, 역할 적용. 같은 링크는 다시 쓸 수 없다(AT-IAM-06.1) */
    @Transactional
    public AcceptInvitationResponse accept(String token, AcceptInvitationRequest req) {
        Invitation invitation = usable(token);
        if (!Boolean.TRUE.equals(req.privacyConsent())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("privacyConsent", "AssertTrue", null)));
        }
        long orgId = invitation.organizationId();
        String loginId = LoginIdRules.normalize(req.loginId());
        if (users.existsLoginId(orgId, loginId)) {
            throw new BusinessException(CoreErrorCode.LOGIN_ID_DUPLICATED);
        }
        policy.check("password", req.password(), loginId, invitation.email(), List.of());
        Instant now = clock.instant();
        String hash = encoder.encode(req.password());
        users.activate(invitation.userId(), orgId, loginId, hash, false, now);
        history.push(orgId, invitation.userId(), hash, now);
        roles.upsertUserRole(orgId, invitation.userId(), invitation.role(), invitation.customRoleId(), invitation.spaceScope(),
                invitation.invitedBy(), now);
        invitations.updateStatus(invitation.id(), orgId, "ACCEPTED", now);
        audits.record(audits.event(orgId, AuditCodes.INVITATION_ACCEPTED)
                .actor(AuditActorType.USER, Long.toString(invitation.userId()), null)
                .target("USER", Long.toString(invitation.userId()))
                .detail("invitationId", Long.toString(invitation.id())).detail("role", invitation.role())
                .detail("privacyConsent", true));
        events.userStateChanged(orgId, invitation.userId(), UserStatus.INVITED.name(), UserStatus.ACTIVE.name(), "INVITATION_ACCEPTED");
        return new AcceptInvitationResponse(loginId);
    }

    /** API-IAM-22 재발송(BR-IAM-09): 새 토큰·새 만료, 이전 링크 무효. 6번째 발송부터 429 */
    @Transactional
    public ResendInvitationResponse resend(long invitationId) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser admin = roleChecker.currentUser();
        long orgId = admin.organizationId();
        Invitation invitation = invitations.lockByIdAndOrganizationId(invitationId, orgId)
                .orElseThrow(() -> new BusinessException(CoreErrorCode.INVITATION_INVALID));
        if (!"PENDING".equals(invitation.status())) {
            throw new BusinessException(CoreErrorCode.INVITATION_INVALID);
        }
        if (invitation.sentCount() >= MAX_SENT) {
            throw new BusinessException(CoreErrorCode.INVITATION_RESEND_LIMIT);
        }
        Instant now = clock.instant();
        String token = Tokens.newToken();
        Instant expiresAt = now.plus(properties.tokens().invitation());
        invitations.updateForResend(invitationId, orgId, Tokens.sha256Hex(token), expiresAt, now);
        OrgSettings settings = organizations.findSettings(orgId).orElse(null);
        String orgName = settings == null ? "data2flow" : settings.displayName();
        sendInvitationMail(orgId, invitation.email(), orgName, token, expiresAt, settings == null ? "ko" : settings.locale(),
                settings == null ? "Asia/Seoul" : settings.timezone());
        audits.record(audits.event(orgId, AuditCodes.INVITATION_RESENT).actor(admin)
                .target("USER", Long.toString(invitation.userId())).detail("invitationId", Long.toString(invitationId))
                .detail("sentCount", invitation.sentCount() + 1));
        return new ResendInvitationResponse(expiresAt, invitation.sentCount() + 1);
    }

    /** API-IAM-29 취소. INVITED 행은 같은 이메일을 다시 초대할 때 지운다 */
    @Transactional
    public void cancel(long invitationId) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser admin = roleChecker.currentUser();
        long orgId = admin.organizationId();
        Invitation invitation = invitations.lockByIdAndOrganizationId(invitationId, orgId)
                .orElseThrow(() -> new BusinessException(CoreErrorCode.INVITATION_INVALID));
        if (!"PENDING".equals(invitation.status())) {
            throw new BusinessException(CoreErrorCode.INVITATION_INVALID);
        }
        invitations.updateStatus(invitationId, orgId, "CANCELED", clock.instant());
        audits.record(audits.event(orgId, AuditCodes.INVITATION_CANCELED).actor(admin)
                .target("USER", Long.toString(invitation.userId())).detail("invitationId", Long.toString(invitationId)));
    }

    /** API-IAM-33 초대 목록(기본 PENDING) */
    @Transactional(readOnly = true)
    public ListApiResponse<InvitationSummaryResponse> list(String status, Integer page, Integer size) {
        roleChecker.requireAdmin();
        long orgId = roleChecker.currentUser().organizationId();
        String filter = status == null || status.isBlank() ? "PENDING" : status.strip().toUpperCase(Locale.ROOT);
        if ("ALL".equals(filter)) {
            filter = null;
        } else if (!Set.of("PENDING", "ACCEPTED", "CANCELED", "EXPIRED").contains(filter)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("status", "INVALID", null)));
        }
        PageParams params = PageParams.of(page, size);
        Instant now = clock.instant();
        List<InvitationSummaryResponse> items = invitations.list(orgId, filter, now, params.size(), params.offset()).stream()
                .map(i -> new InvitationSummaryResponse(Long.toString(i.id()), i.email(), i.role(),
                        i.customRoleId() == null ? null : Long.toString(i.customRoleId()),
                        i.spaceScope().stream().map(String::valueOf).toList(), i.effectiveStatus(now), i.sentCount(),
                        Long.toString(i.invitedBy()), i.expiresAt(), i.createdAt()))
                .toList();
        return ListApiResponse.of(params, items, invitations.count(orgId, filter, now));
    }

    private Invitation usable(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            throw new BusinessException(CoreErrorCode.INVITATION_INVALID);
        }
        Invitation invitation = invitations.lockByTokenHash(Tokens.sha256Hex(token))
                .orElseThrow(() -> new BusinessException(CoreErrorCode.INVITATION_INVALID));
        if (!invitation.usable(clock.instant())) {
            throw new BusinessException(CoreErrorCode.INVITATION_INVALID);
        }
        AppUser user = users.findByIdAndOrganizationId(invitation.userId(), invitation.organizationId()).orElse(null);
        if (user == null || user.status() != UserStatus.INVITED) {
            throw new BusinessException(CoreErrorCode.INVITATION_INVALID);
        }
        return invitation;
    }

    private void sendInvitationMail(long orgId, String email, String orgName, String token, Instant expiresAt, String locale,
                                    String timezone) {
        Locale mailLocale = MailLinks.locale(locale);
        String link = links.link("/invitations/" + token, mailLocale);
        String expires = MailLinks.format(expiresAt, timezone);
        AfterCommit.run("초대 메일", () -> mail.send(orgId, email, "mail.invitation", mailLocale, orgName, link, expires));
    }
}
