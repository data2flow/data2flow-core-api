package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.event.EnergyConfigChanged.Scope;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.domain.EnergyRules;
import net.java21.data2flow.core.energy.domain.TouSchedule;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.Ref;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.TariffRequest;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.TariffView;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.TariffRow;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.TariffValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static net.java21.data2flow.core.energy.service.EnergyAccess.invalid;

/**
 * 요금 단가 API-ENE-01(ENE-01.02, UC-ENE-01, BR-ENE-05).
 *
 * <ul>
 *   <li>조회 ENE_READ, 변경 ENE_ADMIN. 조직 단가는 공간 범위 제한이 없는 관리자만, 사이트 단가는 그 사이트가 범위 안인 관리자가 바꾼다</li>
 *   <li>범위는 조직(scopeSpaceId 없음) 또는 사이트. 기간의 끝은 같은 범위 다음 행 시작 전날이라 같은 범위·같은 시작일만 겹침이다(409 ENE_RATE_PERIOD_OVERLAP)</li>
 *   <li>FLAT은 단가 &gt; 0(소수 둘째 자리까지), TOU는 {@link TouSchedule} 검사를 통과해야 한다(400 ENE_RATE_INVALID)</li>
 *   <li>바꾸면 EVT-ENE-07(TARIFF)을 보내고, 이미 계산한 과거 비용은 다시 계산하지 않는다(재계산은 API-ENE-03 명시 요청)</li>
 * </ul>
 */
@Service
public class TariffService {

    private static final BigDecimal MAX_RATE = new BigDecimal("99999999.99");
    private static final BigDecimal MAX_BASE_CHARGE = new BigDecimal("999999999999");

    private final EnergyConfigRepository config;
    private final EnergyAccess access;
    private final EnergyEvents events;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public TariffService(EnergyConfigRepository config, EnergyAccess access, EnergyEvents events, RoleChecker roleChecker, Audits audits, JsonMapper json,
                         Clock clock) {
        this.config = config;
        this.access = access;
        this.events = events;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** 목록(조직 단가 먼저, 범위별 시작일 순). 범위 밖 사이트 단가는 빠진다 */
    @Transactional(readOnly = true)
    public List<TariffView> list() {
        roleChecker.require(Permission.ENE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        var scope = roleChecker.spaceScope();
        List<TariffRow> rows = config.tariffs(orgId);
        return rows.stream().filter(t -> scope.includes(t.scopeSpaceId())).map(t -> view(t, rows)).toList();
    }

    /** 조직 단가 전부(권한 확인 없음). 내부 설정 묶음(API-ENE-70)용 */
    @Transactional(readOnly = true)
    public List<TariffView> listOf(long orgId) {
        List<TariffRow> rows = config.tariffs(orgId);
        return rows.stream().map(t -> view(t, rows)).toList();
    }

    @Transactional
    public TariffView create(TariffRequest request) {
        roleChecker.require(Permission.ENE_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        TariffValues values = values(orgId, request);
        if (config.tariffStartTaken(orgId, values.scopeSpaceId(), values.validFrom(), null)) {
            throw new BusinessException(EnergyErrorCode.ENE_RATE_PERIOD_OVERLAP);
        }
        long id = config.insertTariff(orgId, values, user.userId(), clock.instant());
        changed(user, id, values, "CREATED");
        return get(orgId, id);
    }

    /** 고치기(baseVersion이 맞아야 한다) */
    @Transactional
    public TariffView update(long id, TariffRequest request) {
        roleChecker.require(Permission.ENE_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        TariffRow current = find(orgId, id);
        requireChange(orgId, current.scopeSpaceId());
        if (request == null || request.baseVersion() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        TariffValues values = values(orgId, request);
        if (config.tariffStartTaken(orgId, values.scopeSpaceId(), values.validFrom(), id)) {
            throw new BusinessException(EnergyErrorCode.ENE_RATE_PERIOD_OVERLAP);
        }
        if (request.baseVersion() != current.version() || config.updateTariff(orgId, id, request.baseVersion(), values, user.userId(), clock.instant()) == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        changed(user, id, values, "UPDATED");
        if (!Objects.equals(current.scopeSpaceId(), values.scopeSpaceId())) {
            events.configChanged(orgId, Scope.TARIFF, current.scopeSpaceId());
        }
        return get(orgId, id);
    }

    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.ENE_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        TariffRow current = find(orgId, id);
        requireChange(orgId, current.scopeSpaceId());
        config.deleteTariff(orgId, id);
        changed(user, id, new TariffValues(current.scopeSpaceId(), current.validFrom(), current.mode(), current.flatRate(), current.touJson(),
                current.baseCharge()), "DELETED");
    }

    private TariffView get(long orgId, long id) {
        List<TariffRow> rows = config.tariffs(orgId);
        return rows.stream().filter(t -> t.id() == id).findFirst().map(t -> view(t, rows))
                .orElseThrow(() -> new BusinessException(EnergyErrorCode.ENE_TARIFF_NOT_FOUND));
    }

    private TariffRow find(long orgId, long id) {
        TariffRow t = config.tariff(orgId, id).orElseThrow(() -> new BusinessException(EnergyErrorCode.ENE_TARIFF_NOT_FOUND));
        if (!roleChecker.spaceScope().includes(t.scopeSpaceId())) {
            throw new BusinessException(EnergyErrorCode.ENE_TARIFF_NOT_FOUND);
        }
        return t;
    }

    private void requireChange(long orgId, Long scopeSpaceId) {
        if (scopeSpaceId == null) {
            access.requireOrgWide(Permission.ENE_ADMIN);
        } else {
            access.space(Permission.ENE_ADMIN, orgId, scopeSpaceId);
        }
    }

    private void changed(CurrentUser user, long id, TariffValues v, String change) {
        long orgId = user.organizationId();
        audits.record(audits.event(orgId, EnergyAuditCodes.ENE_TARIFF_CHANGED).actor(user).target("ENERGY_TARIFF", Long.toString(id))
                .detail("change", change).detail("scopeSpaceId", v.scopeSpaceId() == null ? "ORG" : v.scopeSpaceId().toString())
                .detail("validFrom", v.validFrom().toString()).detail("mode", v.mode()));
        events.configChanged(orgId, Scope.TARIFF, v.scopeSpaceId());
    }

    /** 요청 검증(TC-ENE-012). 권한 검사도 여기서 한다(범위가 정해져야 알 수 있다) */
    TariffValues values(long orgId, TariffRequest r) {
        if (r == null) {
            throw invalid(EnergyErrorCode.ENE_RATE_INVALID, "validFrom", "NotNull");
        }
        Long scopeId = EnergyAccess.id(r.scopeSpaceId(), EnergyErrorCode.SPACE_NOT_FOUND);
        if (scopeId == null) {
            access.requireOrgWide(Permission.ENE_ADMIN);
        } else if (!"SITE".equals(access.space(Permission.ENE_ADMIN, orgId, scopeId).type())) {
            throw invalid(EnergyErrorCode.ENE_RATE_INVALID, "scopeSpaceId", "SiteOnly");
        }
        if (r.validFrom() == null) {
            throw invalid(EnergyErrorCode.ENE_RATE_INVALID, "validFrom", "NotNull");
        }
        String mode = r.mode() == null ? "" : r.mode().strip().toUpperCase(Locale.ROOT);
        BigDecimal flat = null;
        String tou = null;
        switch (mode) {
            case "FLAT" -> {
                flat = r.flatRateKrwPerKwh();
                if (flat == null || flat.signum() <= 0 || flat.stripTrailingZeros().scale() > 2 || flat.compareTo(MAX_RATE) > 0) {
                    throw invalid(EnergyErrorCode.ENE_RATE_INVALID, "flatRateKrwPerKwh", "Range");
                }
            }
            case "TOU" -> {
                if (r.touSchedule() == null) {
                    throw invalid(EnergyErrorCode.ENE_RATE_INVALID, "touSchedule", "NotNull");
                }
                r.touSchedule().problem().ifPresent(field -> {
                    throw invalid(EnergyErrorCode.ENE_RATE_INVALID, field, "Coverage");
                });
                tou = json.writeValueAsString(r.touSchedule());
            }
            default -> throw invalid(EnergyErrorCode.ENE_RATE_INVALID, "mode", "Enum");
        }
        BigDecimal base = r.baseChargeKrwPerMonth();
        if (base != null && (base.signum() < 0 || base.stripTrailingZeros().scale() > 0 || base.compareTo(MAX_BASE_CHARGE) > 0)) {
            throw invalid(EnergyErrorCode.ENE_RATE_INVALID, "baseChargeKrwPerMonth", "Range");
        }
        return new TariffValues(scopeId, r.validFrom(), mode, flat, tou, base);
    }

    private TariffView view(TariffRow t, List<TariffRow> all) {
        List<LocalDate> starts = all.stream().filter(o -> Objects.equals(o.scopeSpaceId(), t.scopeSpaceId())).map(TariffRow::validFrom).sorted().toList();
        return new TariffView(Long.toString(t.id()), t.scopeSpaceId() == null ? null : new Ref(t.scopeSpaceId().toString(), t.scopeSpaceName()),
                t.validFrom(), EnergyRules.validTo(starts, t.validFrom()), t.mode(), t.flatRate(),
                t.touJson() == null ? null : json.readValue(t.touJson(), TouSchedule.class), t.baseCharge(), t.version(), t.updatedAt());
    }
}
