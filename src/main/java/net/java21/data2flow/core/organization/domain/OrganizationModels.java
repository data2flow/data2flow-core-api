package net.java21.data2flow.core.organization.domain;

import java.time.Instant;
import java.util.List;

/** 조직·조직 설정·보안 정책 값(domain-model IAM §2.1·§2.9, OPS §2.1) */
public final class OrganizationModels {

    private OrganizationModels() {
    }

    /** {@code data2flow_core.organizations}. v1은 1행(ADR-004) */
    public record Organization(long id, String code, String name, String timezone, String locale, String status) {
    }

    /** {@code data2flow_core.org_settings}(OPS-07.01) */
    public record OrgSettings(long organizationId, String displayName, String logoObjectKey, String timezone, String locale,
                              String unitSystem, String dateFormat, int version, Instant updatedAt) {
    }

    /** {@code data2flow_core.org_security_policies}(IAM-02.03·02.05·03.01·07.01·01.08) */
    public record SecurityPolicy(long organizationId, int sessionIdleMinutes, int sessionAbsoluteHours, int accessTtlMinutes,
                                 int refreshTtlHours, int loginMaxFailures, int lockoutMinutes, List<String> mfaRequiredRoles,
                                 boolean signupRequestEnabled, List<String> signupAllowedDomains, int auditRetentionDays,
                                 int version) {

        /** 정책 행이 없을 때의 기본값(DDL 기본값과 같다) */
        public static SecurityPolicy defaults(long organizationId) {
            return new SecurityPolicy(organizationId, 30, 12, 60, 6, 5, 15, List.of(), false, List.of(), 365, 0);
        }
    }
}
