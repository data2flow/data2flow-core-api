package net.java21.data2flow.core.organization.service;

import net.java21.data2flow.contracts.authz.BuiltinRole;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.AuditCodes;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.organization.domain.OrganizationModels.OrgSettings;
import net.java21.data2flow.core.organization.domain.OrganizationModels.SecurityPolicy;
import net.java21.data2flow.core.organization.dto.OrganizationDtos.OrgSettingsResponse;
import net.java21.data2flow.core.organization.dto.OrganizationDtos.SecurityPolicyResponse;
import net.java21.data2flow.core.organization.dto.OrganizationDtos.UpdateOrgSettingsRequest;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 조직 기본 설정(OPS-07.01)과 조직 보안 정책(API-IAM-72: 세션·잠금·2단계 인증 필수 역할·가입 신청).
 * 둘 다 baseVersion 낙관적 잠금이고 바꾸면 감사(ORG_SETTING_CHANGED, SECURITY_POLICY_CHANGED)를 남긴다.
 */
@Service
public class OrganizationSettingsService {

    static final Set<String> LOCALES = Set.of("ko", "en", "ja", "zh");
    static final Set<String> UNIT_SYSTEMS = Set.of("METRIC", "IMPERIAL");

    private final OrganizationRepository organizations;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public OrganizationSettingsService(OrganizationRepository organizations, RoleChecker roleChecker, Audits audits, Clock clock) {
        this.organizations = organizations;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-OPS-40 GET(로그인 사용자). 설정 행이 없으면 기본값으로 만든다 */
    @Transactional
    public OrgSettingsResponse settings() {
        long orgId = roleChecker.currentUser().organizationId();
        return toResponse(loadSettings(orgId));
    }

    /** API-OPS-40 PUT(ADMIN). 형식이 틀리면 400 SETTING_INVALID {필드}(OPS-07.01) */
    @Transactional
    public OrgSettingsResponse updateSettings(UpdateOrgSettingsRequest req) {
        roleChecker.require(Permission.OPS_MANAGE);
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        OrgSettings before = loadSettings(user.organizationId());
        VersionCheck.require(req.baseVersion(), before.version());
        String displayName = req.displayName() == null ? before.displayName() : req.displayName().strip();
        if (displayName.isEmpty() || displayName.length() > 100) {
            throw new BusinessException(CoreErrorCode.SETTING_INVALID, "displayName");
        }
        String timezone = req.timezone() == null ? before.timezone() : req.timezone();
        if (!ZoneId.getAvailableZoneIds().contains(timezone) && !"UTC".equals(timezone)) {
            throw new BusinessException(CoreErrorCode.SETTING_INVALID, "timezone");
        }
        String locale = req.locale() == null ? before.locale() : req.locale().toLowerCase(Locale.ROOT);
        if (!LOCALES.contains(locale)) {
            throw new BusinessException(CoreErrorCode.SETTING_INVALID, "locale");
        }
        String unit = req.unitSystem() == null ? before.unitSystem() : req.unitSystem().toUpperCase(Locale.ROOT);
        if (!UNIT_SYSTEMS.contains(unit)) {
            throw new BusinessException(CoreErrorCode.SETTING_INVALID, "unitSystem");
        }
        String dateFormat = req.dateFormat() == null ? before.dateFormat() : req.dateFormat();
        if (dateFormat.isBlank() || dateFormat.length() > 20 || !dateFormat.matches("[YMDHhmsaA/.: -]+")) {
            throw new BusinessException(CoreErrorCode.SETTING_INVALID, "dateFormat");
        }
        VersionCheck.requireUpdated(organizations.updateSettings(user.organizationId(), req.baseVersion(), displayName, timezone,
                locale, unit, dateFormat, user.userId(), clock.instant()));
        OrgSettings after = loadSettings(user.organizationId());
        audits.record(audits.event(user.organizationId(), AuditCodes.ORG_SETTING_CHANGED).actor(user)
                .target("ORG_SETTINGS", Long.toString(user.organizationId()))
                .detail("before", settingsMap(before)).detail("after", settingsMap(after)));
        return toResponse(after);
    }

    /** API-IAM-72 GET(ADMIN). 정책 행이 없으면 기본값으로 만든다 */
    @Transactional
    public SecurityPolicyResponse policy() {
        roleChecker.requireAdmin();
        return toResponse(loadPolicy(roleChecker.currentUser().organizationId()));
    }

    /** API-IAM-72 PUT(ADMIN). 들어온 필드만 바꾸고 범위 밖이면 400(errors[].field) */
    @Transactional
    public SecurityPolicyResponse updatePolicy(JsonNode body) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        int baseVersion = (int) VersionCheck.baseVersion(body);
        SecurityPolicy before = loadPolicy(user.organizationId());
        VersionCheck.require(baseVersion, before.version());
        List<FieldErrorDetail> errors = new ArrayList<>();
        SecurityPolicy p = new SecurityPolicy(user.organizationId(),
                intField(body, "sessionIdleMinutes", before.sessionIdleMinutes(), 5, 240, errors),
                intField(body, "sessionAbsoluteHours", before.sessionAbsoluteHours(), 1, 24, errors),
                intField(body, "accessTtlMinutes", before.accessTtlMinutes(), 5, 120, errors),
                intField(body, "refreshTtlHours", before.refreshTtlHours(), 1, 24, errors),
                intField(body, "loginMaxFailures", before.loginMaxFailures(), 3, 10, errors),
                intField(body, "lockoutMinutes", before.lockoutMinutes(), 5, 120, errors),
                rolesField(body, before.mfaRequiredRoles(), errors),
                boolField(body, "signupRequestEnabled", before.signupRequestEnabled(), errors),
                domainsField(body, before.signupAllowedDomains(), errors),
                intField(body, "auditRetentionDays", before.auditRetentionDays(), 365, 3650, errors),
                before.version());
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        VersionCheck.requireUpdated(organizations.updatePolicy(p, baseVersion, user.userId(), clock.instant()));
        audits.record(audits.event(user.organizationId(), AuditCodes.SECURITY_POLICY_CHANGED).actor(user)
                .target("SECURITY_POLICY", Long.toString(user.organizationId()))
                .detail("before", policyMap(before)).detail("after", policyMap(p)));
        return toResponse(loadPolicy(user.organizationId()));
    }

    OrgSettings loadSettings(long orgId) {
        return organizations.findSettings(orgId).orElseGet(() -> {
            organizations.insertSettingsIfAbsent(orgId, organizations.findById(orgId).map(o -> o.name()).orElse("data2flow"),
                    "Asia/Seoul", "ko");
            return organizations.findSettings(orgId).orElseThrow();
        });
    }

    SecurityPolicy loadPolicy(long orgId) {
        return organizations.findPolicy(orgId).orElseGet(() -> {
            organizations.insertPolicyIfAbsent(orgId);
            return organizations.findPolicy(orgId).orElseThrow();
        });
    }

    private static OrgSettingsResponse toResponse(OrgSettings s) {
        return new OrgSettingsResponse(s.displayName(), s.logoObjectKey() == null ? null : "/api/v1/core/org-settings/logo",
                s.timezone(), s.locale(), s.unitSystem(), s.dateFormat(), s.version());
    }

    private static SecurityPolicyResponse toResponse(SecurityPolicy p) {
        return new SecurityPolicyResponse(p.sessionIdleMinutes(), p.sessionAbsoluteHours(), p.accessTtlMinutes(),
                p.refreshTtlHours(), p.loginMaxFailures(), p.lockoutMinutes(), p.mfaRequiredRoles(), p.signupRequestEnabled(),
                p.signupAllowedDomains(), p.auditRetentionDays(), p.version());
    }

    private static Map<String, Object> settingsMap(OrgSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("displayName", s.displayName());
        m.put("timezone", s.timezone());
        m.put("locale", s.locale());
        m.put("unitSystem", s.unitSystem());
        m.put("dateFormat", s.dateFormat());
        return m;
    }

    private static Map<String, Object> policyMap(SecurityPolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionIdleMinutes", p.sessionIdleMinutes());
        m.put("sessionAbsoluteHours", p.sessionAbsoluteHours());
        m.put("accessTtlMinutes", p.accessTtlMinutes());
        m.put("refreshTtlHours", p.refreshTtlHours());
        m.put("loginMaxFailures", p.loginMaxFailures());
        m.put("lockoutMinutes", p.lockoutMinutes());
        m.put("mfaRequiredRoles", p.mfaRequiredRoles());
        m.put("signupRequestEnabled", p.signupRequestEnabled());
        m.put("signupAllowedDomains", p.signupAllowedDomains());
        m.put("auditRetentionDays", p.auditRetentionDays());
        return m;
    }

    private static int intField(JsonNode body, String field, int current, int min, int max, List<FieldErrorDetail> errors) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return current;
        }
        if (!node.isIntegralNumber() || node.asInt() < min || node.asInt() > max) {
            errors.add(new FieldErrorDetail(field, "Range", min + "~" + max));
            return current;
        }
        return node.asInt();
    }

    private static boolean boolField(JsonNode body, String field, boolean current, List<FieldErrorDetail> errors) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return current;
        }
        if (!node.isBoolean()) {
            errors.add(new FieldErrorDetail(field, "Type", null));
            return current;
        }
        return node.asBoolean();
    }

    private static List<String> rolesField(JsonNode body, List<String> current, List<FieldErrorDetail> errors) {
        JsonNode node = body.get("mfaRequiredRoles");
        if (node == null || node.isNull()) {
            return current;
        }
        Set<String> roles = new LinkedHashSet<>();
        for (JsonNode item : node.values()) {
            String role = item.asString("").toUpperCase(Locale.ROOT);
            try {
                roles.add(BuiltinRole.valueOf(role).name());
            } catch (IllegalArgumentException ex) {
                errors.add(new FieldErrorDetail("mfaRequiredRoles", "INVALID", role));
            }
        }
        return List.copyOf(roles);
    }

    private static List<String> domainsField(JsonNode body, List<String> current, List<FieldErrorDetail> errors) {
        JsonNode node = body.get("signupAllowedDomains");
        if (node == null || node.isNull()) {
            return current;
        }
        Set<String> domains = new LinkedHashSet<>();
        for (JsonNode item : node.values()) {
            String domain = item.asString("").strip().toLowerCase(Locale.ROOT);
            if (!domain.matches("^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")) {
                errors.add(new FieldErrorDetail("signupAllowedDomains", "INVALID", domain));
            } else {
                domains.add(domain);
            }
        }
        return List.copyOf(domains);
    }
}
