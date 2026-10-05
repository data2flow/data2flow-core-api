package net.java21.data2flow.core.gateway.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.domain.References.SourceRef;
import net.java21.data2flow.core.device.repository.DeviceReferenceRepository;
import net.java21.data2flow.core.gateway.dto.GatewayDtos;
import net.java21.data2flow.core.gateway.dto.GatewayDtos.GatewayResponse;
import net.java21.data2flow.core.gateway.dto.GatewayDtos.GatewayStatsResponse;
import net.java21.data2flow.core.gateway.dto.GatewayDtos.Ref;
import net.java21.data2flow.core.gateway.dto.GatewayDtos.TouchItem;
import net.java21.data2flow.core.gateway.dto.GatewayDtos.TouchRequest;
import net.java21.data2flow.core.gateway.repository.GatewayRepository;
import net.java21.data2flow.core.gateway.repository.GatewayRepository.GatewayRow;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LoRa 게이트웨이(DEV-05.01, BR-DEV-19). pipeline이 업링크 rxInfo의 게이트웨이를 1분 묶음으로 알려 주면(API-DEV-125) 자동 생성·마지막 수신
 * 갱신. 관리는 ChirpStack이 하고(ADR-001) 플랫폼은 이름·설치 공간·오프라인 기준만 둔다. 상태는 조회할 때 마지막 수신과 기준으로 계산한다
 * (게이트웨이 오프라인 알람 EVT-DEV-08은 DEV-05.03, M4).
 */
@Service
public class GatewayService {

    static final Set<String> STATUSES = Set.of("ONLINE", "OFFLINE", "UNKNOWN");

    private final RoleChecker roleChecker;
    private final GatewayRepository gateways;
    private final DeviceReferenceRepository refs;
    private final DeploymentOrganization deployment;
    private final Audits audits;
    private final Clock clock;

    public GatewayService(RoleChecker roleChecker, GatewayRepository gateways, DeviceReferenceRepository refs,
                          DeploymentOrganization deployment, Audits audits, Clock clock) {
        this.roleChecker = roleChecker;
        this.gateways = gateways;
        this.refs = refs;
        this.deployment = deployment;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-DEV-60 */
    @Transactional(readOnly = true)
    public ListApiResponse<GatewayResponse> list(String sourceId, String status, String spaceId, Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        long org = roleChecker.currentUser().organizationId();
        String st = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);
        if (st != null && !STATUSES.contains(st)) {
            throw invalid("status");
        }
        Long source = optionalId(sourceId, "sourceId");
        Long space = optionalId(spaceId, "spaceId");
        SpaceScope scope = roleChecker.spaceScope();
        Set<Long> allowed = scope.unrestricted() ? null : scope.allowedSpaceIds();
        PageParams params = PageParams.of(page, size);
        Instant now = clock.instant();
        List<GatewayResponse> items = gateways.list(org, source, st, space, allowed, now, params.size(), params.offset()).stream()
                .map(GatewayService::toResponse).toList();
        return ListApiResponse.of(params, items, gateways.count(org, source, st, space, allowed, now));
    }

    /** 게이트웨이 상세(API-DEV-60 항목 모양) */
    @Transactional(readOnly = true)
    public GatewayResponse get(long gatewayId) {
        roleChecker.require(Permission.DEV_READ);
        return toResponse(load(gatewayId, Permission.DEV_READ));
    }

    /** API-DEV-61 수신 분포(DEV-05.02, AT-DEV-11.3) — DEV_READ. 기간 기본 최근 24시간, 최대 31일. 권한 범위 밖 기기는 빠진다 */
    @Transactional(readOnly = true)
    public GatewayStatsResponse stats(long gatewayId, Instant from, Instant to) {
        roleChecker.require(Permission.DEV_READ);
        GatewayRow g = load(gatewayId, Permission.DEV_READ);
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? end.minus(java.time.Duration.ofHours(24)) : from;
        if (!start.isBefore(end) || java.time.Duration.between(start, end).compareTo(java.time.Duration.ofDays(31)) > 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("from", "Range", "0~31d")));
        }
        SpaceScope scope = roleChecker.spaceScope();
        long org = g.organizationId();
        List<GatewayDtos.DeviceSignal> devices = gateways.findDeviceLinks(org, g.gatewayEui(), start, end,
                        scope.unrestricted() ? null : scope.allowedSpaceIds()).stream()
                .map(d -> new GatewayDtos.DeviceSignal(Long.toString(d.deviceId()), d.name(), round(d.avgRssi()), round(d.avgSnr()), d.uplinks(),
                        d.totalCount() == 0 ? 0 : Math.round(d.bestCount() * 1000.0 / d.totalCount()) / 1000.0))
                .toList();
        List<GatewayDtos.HourCount> hours = gateways.findUplinksByHour(org, g.gatewayEui(), start, end).stream()
                .map(h -> new GatewayDtos.HourCount(h.t(), h.count())).toList();
        List<GatewayDtos.RssiBucket> histogram = gateways.findRssiHistogram(org, g.gatewayEui(), start, end).stream()
                .map(b -> new GatewayDtos.RssiBucket(b.fromDbm(), b.fromDbm() + 10, b.count())).toList();
        return new GatewayStatsResponse(Long.toString(gatewayId), start, end, devices.size(), hours, devices, histogram);
    }

    private static Double round(java.math.BigDecimal v) {
        return v == null ? null : v.setScale(1, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /** API-DEV-62 이름·설치 공간·오프라인 기준(60~86400초). 온 키만 바꾼다 */
    @Transactional
    public GatewayResponse update(long gatewayId, JsonNode body) {
        roleChecker.require(Permission.DEV_ADMIN);
        GatewayRow before = load(gatewayId, Permission.DEV_ADMIN);
        String name = before.name();
        if (body.has("name")) {
            JsonNode n = body.get("name");
            name = n.isNull() ? before.gatewayEui() : n.isString() ? n.stringValue().strip() : "";
            if (name.isEmpty() || name.length() > 100) {
                throw invalid("name");
            }
        }
        Long spaceId = before.spaceId();
        if (body.has("spaceId")) {
            JsonNode s = body.get("spaceId");
            spaceId = s.isNull() ? null : optionalId(s.isString() ? s.stringValue() : s.toString(), "spaceId");
            if (spaceId != null) {
                long id = spaceId;
                refs.findSpace(before.organizationId(), id).orElseThrow(() -> new BusinessException(DeviceErrorCode.SPACE_NOT_FOUND));
                roleChecker.requireSpace(id, DeviceErrorCode.SPACE_NOT_FOUND);
            }
        }
        int offline = before.offlineAfterSec();
        if (body.has("offlineAfterSec")) {
            JsonNode o = body.get("offlineAfterSec");
            if (!o.isIntegralNumber() || o.intValue() < 60 || o.intValue() > 86400) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                        List.of(new FieldErrorDetail("offlineAfterSec", "Range", "60~86400")));
            }
            offline = o.intValue();
        }
        CurrentUser user = roleChecker.currentUser();
        Instant now = clock.instant();
        gateways.update(before.organizationId(), gatewayId, name, spaceId, offline, now);
        audits.record(audits.event(before.organizationId(), "GATEWAY_UPDATED").actor(user).target("GATEWAY", Long.toString(gatewayId))
                .detail("name", name).detail("spaceId", spaceId).detail("offlineAfterSec", offline));
        return toResponse(gateways.findById(before.organizationId(), gatewayId, now).orElseThrow());
    }

    /**
     * API-DEV-125 업링크 게이트웨이 기록(내부, pipeline). 소스가 없거나 배포 조직 밖이면 그 항목은 건너뛴다(204, 묶음 전체를 실패시키지 않음).
     * EUI는 소문자로 저장한다. 새로 만든 수를 돌려준다.
     */
    @Transactional
    public int touch(TouchRequest req) {
        Instant now = clock.instant();
        Map<Long, SourceRef> sources = new HashMap<>();
        int created = 0;
        for (TouchItem item : req.items()) {
            SourceRef source = sources.computeIfAbsent(item.sourceId(),
                    id -> refs.findSourceAnyOrganization(id, deployment.restriction()).orElse(null));
            if (source == null) {
                continue;
            }
            String eui = item.gatewayEui().strip().toLowerCase(Locale.ROOT);
            if (gateways.upsertSeen(source.organizationId(), source.id(), eui, item.seenAt() == null ? now : item.seenAt(), now)) {
                created++;
            }
        }
        return created;
    }

    private GatewayRow load(long gatewayId, Permission permission) {
        GatewayRow row = gateways.findById(roleChecker.currentUser().organizationId(), gatewayId, clock.instant())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        SpaceScope scope = roleChecker.spaceScope();
        // 공간이 정해지지 않은 게이트웨이는 공간 범위가 제한된 사용자에게 보이지 않는다(목록과 같게, BR-DEV-25)
        roleChecker.require(permission, row.spaceId() == null && !scope.unrestricted() ? Long.valueOf(-1L) : row.spaceId(),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        return row;
    }

    static GatewayResponse toResponse(GatewayRow g) {
        return new GatewayResponse(Long.toString(g.id()), g.gatewayEui(), g.name(), new Ref(Long.toString(g.sourceId()), g.sourceName()),
                g.spaceId() == null ? null : new Ref(Long.toString(g.spaceId()), g.spaceName()), g.status(), g.lastSeenAt(),
                g.offlineAfterSec(), g.deviceCount24h(), g.uplinks24h(), g.updatedAt());
    }

    private static Long optionalId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!raw.strip().matches("\\d{1,18}")) {
            throw invalid(field);
        }
        return Long.valueOf(raw.strip());
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
