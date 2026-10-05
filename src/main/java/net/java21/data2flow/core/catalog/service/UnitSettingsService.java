package net.java21.data2flow.core.catalog.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.catalog.domain.UnitConversion;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

/** 조직 기본 온도 단위(DEV-04.04, API-DEV-57). 저장은 조직 설정 unit_system(C → METRIC, F → IMPERIAL)이라 OPS-07.01 화면과 같은 값이다 */
@Service
public class UnitSettingsService {

    static final String AUDIT = "UNIT_SETTINGS_UPDATED";

    private final RoleChecker roleChecker;
    private final OrganizationRepository organizations;
    private final Audits audits;
    private final Clock clock;

    public UnitSettingsService(RoleChecker roleChecker, OrganizationRepository organizations, Audits audits, Clock clock) {
        this.roleChecker = roleChecker;
        this.organizations = organizations;
        this.audits = audits;
        this.clock = clock;
    }

    public record UnitSettings(String temperatureUnit, int version) {
    }

    @Transactional(readOnly = true)
    public UnitSettings get() {
        roleChecker.require(Permission.DEV_READ);
        var s = organizations.findSettings(roleChecker.currentUser().organizationId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return new UnitSettings(UnitConversion.temperatureUnitOf(s.unitSystem()), s.version());
    }

    @Transactional
    public UnitSettings put(JsonNode body) {
        roleChecker.require(Permission.OPS_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long base = VersionCheck.baseVersion(body);
        String unit = body.has("temperatureUnit") ? body.get("temperatureUnit").asString("").strip().toUpperCase(Locale.ROOT) : "";
        if (!UnitConversion.CELSIUS.equals(unit) && !UnitConversion.FAHRENHEIT.equals(unit)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("temperatureUnit", "Invalid", "C|F")));
        }
        var s = organizations.findSettings(user.organizationId()).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        VersionCheck.require(base, s.version());
        VersionCheck.requireUpdated(organizations.updateSettings(user.organizationId(), (int) base, s.displayName(), s.timezone(), s.locale(),
                UnitConversion.unitSystemOf(unit), s.dateFormat(), user.userId(), clock.instant()));
        audits.record(audits.event(user.organizationId(), AUDIT).actor(user).target("ORGANIZATION", Long.toString(user.organizationId()))
                .detail("temperatureUnit", unit));
        return get();
    }
}
