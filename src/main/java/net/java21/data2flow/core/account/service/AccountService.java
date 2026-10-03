package net.java21.data2flow.core.account.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.account.domain.AppUser;
import net.java21.data2flow.core.account.dto.AccountDtos.ChangePasswordRequest;
import net.java21.data2flow.core.account.dto.AccountDtos.CustomRoleRef;
import net.java21.data2flow.core.account.dto.AccountDtos.MeResponse;
import net.java21.data2flow.core.account.repository.PasswordHistoryRepository;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.audit.service.AuditCodes;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.role.domain.RoleModels.UserRole;
import net.java21.data2flow.core.role.repository.RoleRepository;
import net.java21.data2flow.core.session.domain.RefreshToken.RevokeReason;
import net.java21.data2flow.core.session.service.SessionRevocations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 내 정보(IAM-01.05, IAM-01.09): 프로필 조회·수정, 비밀번호 변경. 비밀번호를 바꾸면 현재 세션을 뺀 다른 로그인을 모두 끊는다
 * (BR-IAM-12, AT-IAM-08.2). 임시 비밀번호 상태에서도 쓸 수 있는 API다(BR-IAM-06).
 */
@Service
public class AccountService {

    static final Set<String> LOCALES = Set.of("ko", "en", "ja", "zh");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordHistoryRepository history;
    private final PasswordPolicy policy;
    private final PasswordEncoder encoder;
    private final SessionRevocations revocations;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public AccountService(UserRepository users, RoleRepository roles, PasswordHistoryRepository history, PasswordPolicy policy,
                          PasswordEncoder encoder, SessionRevocations revocations, RoleChecker roleChecker, Audits audits,
                          JsonMapper json, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.history = history;
        this.policy = policy;
        this.encoder = encoder;
        this.revocations = revocations;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-IAM-04 */
    @Transactional(readOnly = true)
    public MeResponse me() {
        CurrentUser current = roleChecker.currentUser();
        AppUser user = users.findByIdAndOrganizationId(current.userId(), current.organizationId())
                .orElseThrow(() -> new BusinessException(CoreErrorCode.USER_NOT_FOUND));
        UserRole role = roles.findUserRole(current.organizationId(), current.userId()).orElse(null);
        AccessGrant grant = roleChecker.grant();
        CustomRoleRef customRole = role == null || role.customRoleId() == null ? null
                : roles.findCustomRole(current.organizationId(), role.customRoleId())
                .map(r -> new CustomRoleRef(Long.toString(r.id()), r.name())).orElse(null);
        return new MeResponse(Long.toString(user.id()), user.loginId(), user.email(), user.name(), user.phone(), user.locale(),
                user.timezone(), role == null ? null : role.role(), customRole,
                grant.permissions().stream().map(Enum::name).sorted().toList(),
                role == null ? List.of() : role.spaceScope().stream().map(String::valueOf).toList(),
                user.mustChangePassword(), user.totpEnabled(), json.readValue(user.notificationPref(), MAP), user.version());
    }

    /** API-IAM-15 프로필 부분 수정(들어온 키만, null이면 지움 — 이름은 지울 수 없음) */
    @Transactional
    public MeResponse patchMe(JsonNode body) {
        CurrentUser current = roleChecker.currentUser();
        int baseVersion = (int) VersionCheck.baseVersion(body);
        AppUser user = users.lockByIdAndOrganizationId(current.userId(), current.organizationId())
                .orElseThrow(() -> new BusinessException(CoreErrorCode.USER_NOT_FOUND));
        VersionCheck.require(baseVersion, user.version());
        String name = user.name();
        if (body.has("name")) {
            String v = body.get("name").isNull() ? "" : body.get("name").asString("").strip();
            if (v.isEmpty() || v.length() > 50) {
                throw invalid("name", "Size");
            }
            name = v;
        }
        String phone = user.phone();
        if (body.has("phone")) {
            phone = body.get("phone").isNull() ? null : body.get("phone").asString("").strip();
            if (phone != null && !phone.isEmpty() && !phone.matches("^\\+[1-9]\\d{6,14}$")) {
                throw invalid("phone", "Pattern");
            }
            if (phone != null && phone.isEmpty()) {
                phone = null;
            }
        }
        String locale = user.locale();
        if (body.has("locale")) {
            locale = body.get("locale").asString("").toLowerCase(Locale.ROOT);
            if (!LOCALES.contains(locale)) {
                throw invalid("locale", "INVALID");
            }
        }
        String timezone = user.timezone();
        if (body.has("timezone")) {
            timezone = body.get("timezone").asString("");
            if (!ZoneId.getAvailableZoneIds().contains(timezone) && !"UTC".equals(timezone)) {
                throw invalid("timezone", "INVALID");
            }
        }
        String pref = user.notificationPref();
        if (body.has("notificationPref")) {
            JsonNode node = body.get("notificationPref");
            if (!node.isNull() && !node.isObject()) {
                throw invalid("notificationPref", "Type");
            }
            pref = node.isNull() ? "{}" : json.writeValueAsString(node);
        }
        Instant now = clock.instant();
        VersionCheck.requireUpdated(users.updateProfile(user.id(), user.organizationId(), baseVersion, name, phone, locale, timezone,
                pref, now));
        Map<String, Object> changed = new LinkedHashMap<>();
        body.propertyNames().stream().filter(k -> !"baseVersion".equals(k)).forEach(k -> changed.put(k, "CHANGED"));
        audits.record(audits.event(user.organizationId(), AuditCodes.USER_UPDATED).actor(current)
                .target("USER", Long.toString(user.id())).detail("fields", changed.keySet()));
        return me();
    }

    /**
     * API-IAM-12 비밀번호 변경. 현재 비밀번호를 확인하고 정책을 지키면 바꾼다. keepCurrentSession(기본 true)이면 현재 세션(X-SESSION-ID)만 남긴다.
     */
    @Transactional
    public void changePassword(ChangePasswordRequest req, String currentSessionId) {
        CurrentUser current = roleChecker.currentUser();
        AppUser user = users.lockByIdAndOrganizationId(current.userId(), current.organizationId())
                .orElseThrow(() -> new BusinessException(CoreErrorCode.USER_NOT_FOUND));
        if (user.passwordHash() == null || !encoder.matches(req.currentPassword(), user.passwordHash())) {
            throw new BusinessException(CoreErrorCode.PASSWORD_CURRENT_MISMATCH);
        }
        List<String> recent = new java.util.ArrayList<>(history.findRecentHashes(user.organizationId(), user.id()));
        recent.add(user.passwordHash());
        policy.check("newPassword", req.newPassword(), user.loginId(), user.email(), recent);
        Instant now = clock.instant();
        String hash = encoder.encode(req.newPassword());
        users.updatePassword(user.id(), user.organizationId(), hash, false, now);
        history.push(user.organizationId(), user.id(), hash, now);
        UUID keep = null;
        if (req.keepCurrentSession() == null || req.keepCurrentSession()) {
            keep = parseSessionId(currentSessionId);
        }
        List<UUID> revoked = revocations.revokeAll(user.organizationId(), user.id(), RevokeReason.PASSWORD_CHANGED, keep);
        audits.record(audits.event(user.organizationId(), AuditCodes.PASSWORD_CHANGED).actor(current)
                .target("USER", Long.toString(user.id())).detail("revokedSessions", revoked.size())
                .detail("wasTemporary", user.mustChangePassword()));
    }

    private static UUID parseSessionId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
