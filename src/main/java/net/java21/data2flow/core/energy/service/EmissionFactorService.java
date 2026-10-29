package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.event.EnergyConfigChanged.Scope;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.domain.EnergyRules;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.EmissionFactorRequest;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.EmissionFactorView;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.FactorRow;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.FactorValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static net.java21.data2flow.core.energy.service.EnergyAccess.invalid;

/**
 * 전력 배출계수 API-ENE-02(ENE-01.02, UC-ENE-01, AT-ENE-01.3).
 *
 * <ul>
 *   <li>조회 ENE_READ: 조직 계수와 시스템 기본값(출처 문서명·기준 연도, {@code system=true})을 시작일 순으로 함께 보여 준다.
 *       같은 날 시작하는 조직 계수가 있으면 그 날부터는 조직 계수를 쓴다</li>
 *   <li>변경 ENE_ADMIN(공간 범위 제한 없음). 시스템 기본값 행은 409 ENE_EMISSION_FACTOR_SYSTEM_READONLY</li>
 *   <li>계수 0~2(소수 넷째 자리까지), 출처 필수(200자), 기준 연도 1990~2100. 위반은 400 ENE_RATE_INVALID, 같은 시작일은 409 ENE_RATE_PERIOD_OVERLAP</li>
 * </ul>
 */
@Service
public class EmissionFactorService {

    private static final BigDecimal MAX_FACTOR = BigDecimal.valueOf(2);

    private final EnergyConfigRepository config;
    private final EnergyAccess access;
    private final EnergyEvents events;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public EmissionFactorService(EnergyConfigRepository config, EnergyAccess access, EnergyEvents events, RoleChecker roleChecker, Audits audits,
                                 Clock clock) {
        this.config = config;
        this.access = access;
        this.events = events;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<EmissionFactorView> list() {
        roleChecker.require(Permission.ENE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        List<FactorRow> rows = config.factors(orgId);
        List<LocalDate> starts = rows.stream().map(FactorRow::validFrom).distinct().sorted().toList();
        return rows.stream().map(f -> view(f, starts)).toList();
    }

    @Transactional
    public EmissionFactorView create(EmissionFactorRequest request) {
        access.requireOrgWide(Permission.ENE_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        FactorValues values = values(request);
        if (config.factorStartTaken(orgId, values.validFrom(), null)) {
            throw new BusinessException(EnergyErrorCode.ENE_RATE_PERIOD_OVERLAP);
        }
        long id = config.insertFactor(orgId, values, user.userId(), clock.instant());
        changed(user, id, values, "CREATED");
        return get(orgId, id);
    }

    @Transactional
    public EmissionFactorView update(long id, EmissionFactorRequest request) {
        access.requireOrgWide(Permission.ENE_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        editable(orgId, id);
        FactorValues values = values(request);
        if (config.factorStartTaken(orgId, values.validFrom(), id)) {
            throw new BusinessException(EnergyErrorCode.ENE_RATE_PERIOD_OVERLAP);
        }
        config.updateFactor(orgId, id, values);
        changed(user, id, values, "UPDATED");
        return get(orgId, id);
    }

    @Transactional
    public void delete(long id) {
        access.requireOrgWide(Permission.ENE_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        FactorRow f = editable(orgId, id);
        config.deleteFactor(orgId, id);
        changed(user, id, new FactorValues(f.validFrom(), f.factor(), f.sourceTitle(), f.sourceYear()), "DELETED");
    }

    /** 조직 계수 행. 시스템 기본값이면 409, 없거나 다른 조직이면 404 */
    private FactorRow editable(long orgId, long id) {
        FactorRow f = config.factor(orgId, id).orElseThrow(() -> new BusinessException(EnergyErrorCode.ENE_EMISSION_FACTOR_NOT_FOUND));
        if (f.organizationId() == EnergyConfigRepository.SYSTEM_ORG) {
            throw new BusinessException(EnergyErrorCode.ENE_EMISSION_FACTOR_SYSTEM_READONLY);
        }
        return f;
    }

    private EmissionFactorView get(long orgId, long id) {
        return list().stream().filter(v -> v.emissionFactorId().equals(Long.toString(id))).findFirst()
                .orElseThrow(() -> new BusinessException(EnergyErrorCode.ENE_EMISSION_FACTOR_NOT_FOUND));
    }

    private void changed(CurrentUser user, long id, FactorValues v, String change) {
        long orgId = user.organizationId();
        audits.record(audits.event(orgId, EnergyAuditCodes.ENE_EMISSION_FACTOR_CHANGED).actor(user).target("ENERGY_EMISSION_FACTOR", Long.toString(id))
                .detail("change", change).detail("validFrom", v.validFrom().toString()).detail("factor", v.factor().toPlainString()));
        events.configChanged(orgId, Scope.EMISSION, null);
    }

    /** 요청 검증(TC-ENE-013) */
    static FactorValues values(EmissionFactorRequest r) {
        if (r == null || r.validFrom() == null) {
            throw invalid(EnergyErrorCode.ENE_RATE_INVALID, "validFrom", "NotNull");
        }
        BigDecimal factor = r.factorTco2PerMwh();
        if (factor == null || factor.signum() < 0 || factor.compareTo(MAX_FACTOR) > 0 || factor.stripTrailingZeros().scale() > 4) {
            throw invalid(EnergyErrorCode.ENE_RATE_INVALID, "factorTco2PerMwh", "Range");
        }
        String title = r.sourceTitle() == null ? "" : r.sourceTitle().strip();
        if (title.isEmpty() || title.length() > 200) {
            throw invalid(EnergyErrorCode.ENE_RATE_INVALID, "sourceTitle", title.isEmpty() ? "NotBlank" : "Size");
        }
        if (r.sourceYear() == null || r.sourceYear() < 1990 || r.sourceYear() > 2100) {
            throw invalid(EnergyErrorCode.ENE_RATE_INVALID, "sourceYear", "Range");
        }
        return new FactorValues(r.validFrom(), factor, title, r.sourceYear());
    }

    private static EmissionFactorView view(FactorRow f, List<LocalDate> starts) {
        return new EmissionFactorView(Long.toString(f.id()), f.validFrom(), EnergyRules.validTo(starts, f.validFrom()), f.factor(), f.sourceTitle(),
                f.sourceYear(), f.organizationId() == EnergyConfigRepository.SYSTEM_ORG);
    }
}
