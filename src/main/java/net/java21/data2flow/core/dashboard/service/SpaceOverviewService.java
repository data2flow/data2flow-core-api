package net.java21.data2flow.core.dashboard.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.dashboard.domain.DashboardErrorCode;
import net.java21.data2flow.core.dashboard.domain.DeviceSnapshot;
import net.java21.data2flow.core.dashboard.domain.SpaceTree;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.ChildSpace;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.MetricView;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.OverviewDevice;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.PathItem;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.SpaceInfo;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.SpaceOverviewResponse;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.TargetEnv;
import net.java21.data2flow.core.dashboard.repository.DashboardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 공간 요약(API-DSH-02 {@code GET /core/spaces/{space-id}/overview}, DSH-02.01·01.02): 경로, 실제 목표 환경(상속 표시), 쾌적도,
 * 직접 배치된 기기 카드(현재값·상태·마지막 수신·배터리·신호), 하위 공간과 그 쾌적도. 실시간 갱신은 API-DSH-20 {@code space:{id}}.
 *
 * <p>공간 트리(API-DSH-02 {@code GET /core/spaces/tree})는 만들지 않는다: ID 자리와 겹치는 리터럴 GET 금지(api-rules)이고,
 * 웹은 공간 트리를 API-DEV-01({@code GET /core/spaces})로 받는다. 트리의 쾌적도 점은 {@link ComfortService}로 API-DEV-01이 붙일 수 있다.
 */
@Service
public class SpaceOverviewService {

    private final RoleChecker roleChecker;
    private final DashboardRepository repository;
    private final ComfortService comfort;
    private final net.java21.data2flow.core.alarm.repository.AlarmRepository alarms;
    private final tools.jackson.databind.json.JsonMapper json;

    public SpaceOverviewService(RoleChecker roleChecker, DashboardRepository repository, ComfortService comfort,
                                net.java21.data2flow.core.alarm.repository.AlarmRepository alarms, tools.jackson.databind.json.JsonMapper json) {
        this.alarms = alarms;
        this.json = json;
        this.roleChecker = roleChecker;
        this.repository = repository;
        this.comfort = comfort;
    }

    @Transactional(readOnly = true)
    public SpaceOverviewResponse overview(long spaceId) {
        roleChecker.require(Permission.DASHBOARD_READ, spaceId, DashboardErrorCode.SPACE_NOT_FOUND);
        long orgId = roleChecker.currentUser().organizationId();
        SpaceScope scope = roleChecker.spaceScope();
        ComfortService.Context ctx = comfort.load(orgId, scope);
        SpaceTree tree = ctx.tree();
        SpaceTree.Node node = tree.node(spaceId);
        if (node == null) {
            throw new BusinessException(DashboardErrorCode.SPACE_NOT_FOUND);
        }
        // 범위 밖 조상은 이름만(API-DSH-02 "권한 범위 밖 조상은 이름만 있는 회색 노드")
        List<PathItem> path = tree.pathOf(spaceId).stream().map(n -> new PathItem(Long.toString(n.id()), n.name())).toList();
        SpaceTree.EffectiveTargets effective = tree.effectiveTargets(spaceId);
        String inheritedFrom = effective.fromSpaceId() == null || effective.fromSpaceId() == spaceId
                ? null : Long.toString(effective.fromSpaceId());
        List<TargetEnv> targetEnv = effective.targets().stream()
                .map(t -> new TargetEnv(t.metricKey(), t.min(), t.max(), inheritedFrom)).toList();
        SpaceInfo info = new SpaceInfo(Long.toString(spaceId), node.name(), node.type(), path, targetEnv);
        List<OverviewDevice> devices = repository.findDevicesInSpace(orgId, spaceId, ctx.units()).stream()
                .map(SpaceOverviewService::device).toList();
        List<ChildSpace> children = tree.childrenOf(spaceId).stream()
                .filter(c -> scope.includes(c.id()))
                .map(c -> new ChildSpace(Long.toString(c.id()), c.name(), c.type(), comfort.evaluate(ctx, c.id()).state().name()))
                .toList();
        // 열린 알람(DSH-02.01): 이 공간과 하위(범위 안)의 ACTIVE·ACKNOWLEDGED·SUPPRESSED, 최근 순 50건
        String spacePath = alarms.findSpacePath(orgId, spaceId).orElse(null);
        List<Object> openAlarms = spacePath == null ? List.of() : alarms.search(new net.java21.data2flow.core.alarm.repository.AlarmRepository.Search(
                        orgId, List.of("ACTIVE", "ACKNOWLEDGED", "SUPPRESSED"), null, spacePath, null, null, null, null,
                        scope.unrestricted() ? null : alarms.listSpacePaths(orgId, scope.allowedSpaceIds()), null, null), 50, 0).stream()
                .map(a -> (Object) net.java21.data2flow.core.alarm.service.AlarmViews.view(a, json)).toList();
        return new SpaceOverviewResponse(info, comfort.view(ctx, spaceId), devices, openAlarms,
                repository.existsFloorplan(orgId, spaceId), children);
    }

    static OverviewDevice device(DeviceSnapshot d) {
        List<MetricView> metrics = d.metrics().stream().map(m -> new MetricView(m.key(), m.value(), m.unit(), m.quality(), m.at())).toList();
        return new OverviewDevice(Long.toString(d.id()), d.name(), d.modelId() == null ? null : Objects.toString(d.modelId()),
                d.modelName(), d.status(), d.connectivity(), d.lastSeenAt(), d.battery(), d.rssi(), d.virtual(), metrics);
    }
}
