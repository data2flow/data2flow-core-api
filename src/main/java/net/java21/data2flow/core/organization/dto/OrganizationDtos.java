package net.java21.data2flow.core.organization.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/** 조직 설정(API-OPS-40)·보안 정책(API-IAM-72) DTO */
public final class OrganizationDtos {

    private OrganizationDtos() {
    }

    public record UpdateOrgSettingsRequest(String displayName, String timezone, String locale, String unitSystem,
                                           String dateFormat, @NotNull @PositiveOrZero Integer baseVersion) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OrgSettingsResponse(String displayName, String logoUrl, String timezone, String locale, String unitSystem,
                                      String dateFormat, int version) {
    }

    public record SecurityPolicyResponse(int sessionIdleMinutes, int sessionAbsoluteHours, int accessTtlMinutes,
                                         int refreshTtlHours, int loginMaxFailures, int lockoutMinutes,
                                         List<String> mfaRequiredRoles, boolean signupRequestEnabled,
                                         List<String> signupAllowedDomains, int auditRetentionDays, int version) {
    }
}
