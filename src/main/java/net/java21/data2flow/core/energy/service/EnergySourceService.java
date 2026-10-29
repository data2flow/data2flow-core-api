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
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.Ref;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.SourceCandidate;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.SourceRequest;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.SourceView;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.SpaceRow;
import net.java21.data2flow.core.energy.repository.EnergySourceRepository;
import net.java21.data2flow.core.energy.repository.EnergySourceRepository.DeviceRow;
import net.java21.data2flow.core.energy.repository.EnergySourceRepository.SourceRow;
import net.java21.data2flow.core.energy.repository.EnergySourceRepository.Values;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static net.java21.data2flow.core.energy.service.EnergyAccess.invalid;

/**
 * 에너지 원천 API-ENE-04(ENE-01.01, UC-ENE-02, BR-ENE-01~04).
 *
 * <ul>
 *   <li>조회 ENE_READ, 변경 ENE_ADMIN. 범위 밖 공간은 404 SPACE_NOT_FOUND</li>
 *   <li>측정(METERED): 기기 모델에 있는 {@code energy}(누적 kWh) 또는 {@code power}(W) 항목만(아니면 400 ENE_SOURCE_METRIC_INVALID).
 *       누적형은 보내지 않으면 항목으로 정한다(energy 누적, power 순간)</li>
 *   <li>추정(ESTIMATED): 정격 소비 전력 필수, 대기 전력 선택. 하위 합계 포함은 측정 원천에만 의미가 있어 늘 false다</li>
 *   <li>409 ENE_SOURCE_CONFLICT: 같은 계량기(측정은 같은 기기·항목, 추정은 같은 기기)를 이미 다른 원천이 쓴다, 또는 하위 합계를 포함하는
 *       계량기끼리 조상·자손 공간에 있다(BR-ENE-02 이중 계산). 하위 합계를 포함하지 않는 하위 계량기는 상위 계량기 아래에 둘 수 있다(내역용)</li>
 *   <li>종류와 기기는 만든 뒤 바꿀 수 없다(바꾸려면 지우고 다시 만든다). 바꾸면 EVT-ENE-07(SOURCE)</li>
 * </ul>
 */
@Service
public class EnergySourceService {

    static final long NO_DATA_SECONDS = 86_400;

    private final EnergySourceRepository sources;
    private final EnergyAccess access;
    private final EnergyEvents events;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public EnergySourceService(EnergySourceRepository sources, EnergyAccess access, EnergyEvents events, RoleChecker roleChecker, Audits audits,
                               Clock clock) {
        this.sources = sources;
        this.access = access;
        this.events = events;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    /** 목록(공간 경로 순). {@code spaceId}가 있으면 그 공간 것만, 범위 밖 공간 것은 빠진다 */
    @Transactional(readOnly = true)
    public List<SourceView> list(String spaceId) {
        roleChecker.require(Permission.ENE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Long space = EnergyAccess.id(spaceId, EnergyErrorCode.SPACE_NOT_FOUND);
        if (space != null) {
            access.space(Permission.ENE_READ, orgId, space);
        }
        var scope = roleChecker.spaceScope();
        Instant now = clock.instant();
        return sources.list(orgId, space).stream().filter(s -> scope.includes(s.spaceId())).map(s -> view(s, now)).toList();
    }

    /** 측정 후보: 공간(하위 포함)의 기기 중 모델에 energy·power가 있는 것과 이미 연결된 항목 */
    @Transactional(readOnly = true)
    public List<SourceCandidate> candidates(String spaceId) {
        roleChecker.require(Permission.ENE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Long id = EnergyAccess.id(spaceId, EnergyErrorCode.SPACE_NOT_FOUND);
        if (id == null) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "spaceId", "NotNull");
        }
        SpaceRow space = access.space(Permission.ENE_READ, orgId, id);
        var scope = roleChecker.spaceScope();
        Map<Long, List<String>> linked = sources.list(orgId, null).stream().filter(s -> "METERED".equals(s.kind()))
                .collect(Collectors.groupingBy(SourceRow::deviceId, Collectors.mapping(SourceRow::metricKey, Collectors.toList())));
        return sources.candidates(orgId, space.path()).stream().filter(c -> scope.includes(c.spaceId()))
                .map(c -> new SourceCandidate(new Ref(Long.toString(c.deviceId()), c.deviceName()), new Ref(Long.toString(c.spaceId()), c.spaceName()),
                        c.metricKeys(), linked.getOrDefault(c.deviceId(), List.of()).stream().sorted().toList()))
                .toList();
    }

    @Transactional
    public SourceView create(SourceRequest request) {
        roleChecker.require(Permission.ENE_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Values values = values(orgId, request, null);
        long id = sources.insert(orgId, values, user.userId(), clock.instant());
        changed(user, id, values.spaceId(), "CREATED");
        return get(orgId, id);
    }

    @Transactional
    public SourceView update(long id, SourceRequest request) {
        roleChecker.require(Permission.ENE_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        SourceRow current = find(orgId, id);
        access.space(Permission.ENE_ADMIN, orgId, current.spaceId());
        if (request == null || request.baseVersion() == null) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "baseVersion", "NotNull");
        }
        Values values = values(orgId, request, current);
        if (request.baseVersion() != current.version() || sources.update(orgId, id, request.baseVersion(), values, clock.instant()) == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        changed(user, id, values.spaceId(), "UPDATED");
        if (current.spaceId() != values.spaceId()) {
            events.configChanged(orgId, Scope.SOURCE, current.spaceId());
        }
        return get(orgId, id);
    }

    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.ENE_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        SourceRow current = find(orgId, id);
        access.space(Permission.ENE_ADMIN, orgId, current.spaceId());
        sources.delete(orgId, id);
        changed(user, id, current.spaceId(), "DELETED");
    }

    private SourceRow find(long orgId, long id) {
        SourceRow s = sources.find(orgId, id).orElseThrow(() -> new BusinessException(EnergyErrorCode.ENE_SOURCE_NOT_FOUND));
        if (!roleChecker.spaceScope().includes(s.spaceId())) {
            throw new BusinessException(EnergyErrorCode.ENE_SOURCE_NOT_FOUND);
        }
        return s;
    }

    private SourceView get(long orgId, long id) {
        return view(sources.find(orgId, id).orElseThrow(() -> new BusinessException(EnergyErrorCode.ENE_SOURCE_NOT_FOUND)), clock.instant());
    }

    private void changed(CurrentUser user, long id, long spaceId, String change) {
        long orgId = user.organizationId();
        audits.record(audits.event(orgId, EnergyAuditCodes.ENE_SOURCE_CHANGED).actor(user).target("ENERGY_SOURCE", Long.toString(id))
                .detail("change", change).detail("spaceId", Long.toString(spaceId)));
        events.configChanged(orgId, Scope.SOURCE, spaceId);
    }

    /** 요청 검증(TC-ENE-004). {@code current}가 있으면 고치기라 종류·기기는 그 값을 쓴다 */
    Values values(long orgId, SourceRequest r, SourceRow current) {
        if (r == null) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "spaceId", "NotNull");
        }
        Long spaceId = EnergyAccess.id(r.spaceId(), EnergyErrorCode.SPACE_NOT_FOUND);
        if (spaceId == null) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "spaceId", "NotNull");
        }
        SpaceRow space = access.space(Permission.ENE_ADMIN, orgId, spaceId);
        String kind = r.kind() == null || r.kind().isBlank() ? null : r.kind().strip().toUpperCase(Locale.ROOT);
        long deviceId;
        if (current == null) {
            if (!"METERED".equals(kind) && !"ESTIMATED".equals(kind)) {
                throw invalid(CommonErrorCode.INVALID_REQUEST, "kind", "Enum");
            }
            Long raw = EnergyAccess.id(r.deviceId(), EnergyErrorCode.DEVICE_NOT_FOUND);
            if (raw == null) {
                throw invalid(CommonErrorCode.INVALID_REQUEST, "deviceId", "NotNull");
            }
            deviceId = raw;
        } else {
            if (kind != null && !kind.equals(current.kind())) {
                throw invalid(CommonErrorCode.INVALID_REQUEST, "kind", "Immutable");
            }
            Long raw = EnergyAccess.id(r.deviceId(), EnergyErrorCode.DEVICE_NOT_FOUND);
            if (raw != null && raw != current.deviceId()) {
                throw invalid(CommonErrorCode.INVALID_REQUEST, "deviceId", "Immutable");
            }
            kind = current.kind();
            deviceId = current.deviceId();
        }
        DeviceRow device = sources.device(orgId, deviceId).orElseThrow(() -> new BusinessException(EnergyErrorCode.DEVICE_NOT_FOUND));
        if (!roleChecker.spaceScope().includes(device.spaceId())) {
            throw new BusinessException(EnergyErrorCode.DEVICE_NOT_FOUND);
        }
        boolean enabled = r.enabled() == null || r.enabled();
        Long exceptId = current == null ? null : current.id();
        Values values;
        if ("METERED".equals(kind)) {
            String metric = r.metricKey() == null ? "" : r.metricKey().strip();
            if (!EnergyRules.METER_METRICS.contains(metric) || !device.metricKeys().contains(metric)) {
                throw invalid(EnergyErrorCode.ENE_SOURCE_METRIC_INVALID, "metricKey", metric.isEmpty() ? "NotNull" : "Unsupported");
            }
            boolean cumulative = r.cumulative() == null ? EnergyRules.defaultCumulative(metric) : r.cumulative();
            boolean covers = Boolean.TRUE.equals(r.coversDescendants());
            values = new Values(spaceId, kind, deviceId, metric, cumulative, covers, null, null, enabled);
            if (covers && enabled && sources.coveringMeters(orgId, exceptId).stream().anyMatch(o -> EnergyRules.nested(space.path(), o.spacePath()))) {
                throw invalid(EnergyErrorCode.ENE_SOURCE_CONFLICT, "coversDescendants", "Nested");
            }
        } else {
            Integer rated = r.ratedPowerW();
            if (rated == null || rated < 1 || rated > EnergyRules.MAX_POWER_W) {
                throw invalid(CommonErrorCode.INVALID_REQUEST, "ratedPowerW", rated == null ? "NotNull" : "Range");
            }
            Integer standby = r.standbyPowerW();
            if (standby != null && (standby < 0 || standby > rated)) {
                throw invalid(CommonErrorCode.INVALID_REQUEST, "standbyPowerW", "Range");
            }
            values = new Values(spaceId, kind, deviceId, null, null, false, rated, standby, enabled);
        }
        if (sources.sameMeter(orgId, kind, deviceId, values.metricKey(), exceptId).isPresent()) {
            throw invalid(EnergyErrorCode.ENE_SOURCE_CONFLICT, "deviceId", "Linked");
        }
        return values;
    }

    private SourceView view(SourceRow s, Instant now) {
        Instant last = "METERED".equals(s.kind()) ? sources.lastValueAt(s.deviceId(), s.metricKey(), now).orElse(null) : null;
        String status = !s.enabled() ? "DISABLED" : "METERED".equals(s.kind()) && last == null ? "NO_DATA" : "OK";
        return new SourceView(Long.toString(s.id()), new Ref(Long.toString(s.spaceId()), s.spaceName()), s.kind(),
                new Ref(Long.toString(s.deviceId()), s.deviceName()), s.metricKey(), s.cumulative(), s.coversDescendants(), s.ratedPowerW(),
                s.standbyPowerW(), s.enabled(), last, status, s.version(), s.updatedAt());
    }
}
