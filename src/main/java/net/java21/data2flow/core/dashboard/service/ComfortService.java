package net.java21.data2flow.core.dashboard.service;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.dashboard.domain.Comfort;
import net.java21.data2flow.core.dashboard.domain.DeviceSnapshot;
import net.java21.data2flow.core.dashboard.domain.SpaceTree;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.ComfortCause;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.ComfortRow;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.ComfortView;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.TargetRange;
import net.java21.data2flow.core.dashboard.repository.DashboardRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 공간 쾌적도 계산(DSH-01.02). 조직의 공간 트리·목표 환경·배치된 기기 최신값을 한 번 읽어(조회 3~4번) 공간마다 판정한다.
 * 권한 검사는 부르는 쪽(홈·공간 요약·실시간)이 하고, 여기서는 넘겨받은 공간 범위 안의 기기만 쓴다(BR-DSH-01).
 */
@Service
public class ComfortService {

    private final DashboardRepository repository;

    public ComfortService(DashboardRepository repository) {
        this.repository = repository;
    }

    /** 계산 재료(한 요청·한 실시간 묶음 동안 쓴다) */
    public record Context(SpaceTree tree, SpaceScope scope, Map<String, String> units, List<DeviceSnapshot> devices,
                          Map<Long, List<DeviceSnapshot>> devicesBySpace) {

        /** 이 공간(하위 포함)의 기기 최신값 */
        public List<Comfort.Reading> readings(long spaceId) {
            List<Comfort.Reading> readings = new ArrayList<>();
            for (Long id : tree.subtree(spaceId)) {
                for (DeviceSnapshot d : devicesBySpace.getOrDefault(id, List.of())) {
                    for (DeviceSnapshot.MetricValue m : d.metrics()) {
                        readings.add(new Comfort.Reading(d.id(), m.key(), m.value(), m.unit(), m.quality(), m.at()));
                    }
                }
            }
            return readings;
        }
    }

    public Context load(long organizationId, SpaceScope scope) {
        SpaceTree tree = new SpaceTree(repository.findSpaces(organizationId), repository.findTargets(organizationId));
        Map<String, String> units = repository.findMetricUnits(organizationId);
        List<DeviceSnapshot> devices = repository.findPlacedActiveDevices(organizationId, scope, units);
        Map<Long, List<DeviceSnapshot>> bySpace = new HashMap<>();
        for (DeviceSnapshot d : devices) {
            bySpace.computeIfAbsent(d.spaceId(), k -> new ArrayList<>()).add(d);
        }
        return new Context(tree, scope, units, devices, bySpace);
    }

    public Comfort.Result evaluate(Context ctx, long spaceId) {
        return Comfort.evaluate(ctx.tree().effectiveTargets(spaceId).targets(), ctx.readings(spaceId));
    }

    public ComfortView view(Context ctx, long spaceId) {
        return toView(evaluate(ctx, spaceId));
    }

    /**
     * 홈 쾌적도 목록: 기기가 직접 배치된 공간(실·구역 등)마다 한 줄, 나쁜 상태부터, 같으면 이름순.
     * 공간 범위 밖 공간은 기기 조회에서 이미 빠진다.
     */
    public List<ComfortRow> rows(Context ctx) {
        Set<Long> spaceIds = new LinkedHashSet<>(ctx.devicesBySpace().keySet());
        List<RankedRow> ranked = new ArrayList<>();
        for (Long spaceId : spaceIds) {
            SpaceTree.Node node = ctx.tree().node(spaceId);
            if (node == null || !ctx.scope().includes(spaceId)) {
                continue;
            }
            Comfort.Result result = evaluate(ctx, spaceId);
            ranked.add(new RankedRow(result.state().badness(), node.name(),
                    new ComfortRow(Long.toString(spaceId), node.name(), result.state().name(), causes(result), result.updatedAt())));
        }
        ranked.sort(Comparator.comparingInt(RankedRow::badness).reversed().thenComparing(RankedRow::name));
        return ranked.stream().map(RankedRow::row).toList();
    }

    public static ComfortView toView(Comfort.Result result) {
        return new ComfortView(result.state().name(), causes(result), result.updatedAt());
    }

    static List<ComfortCause> causes(Comfort.Result result) {
        return result.causes().stream()
                .map(c -> new ComfortCause(c.metricKey(), c.value(), c.unit(), new TargetRange(c.target().min(), c.target().max())))
                .toList();
    }

    private record RankedRow(int badness, String name, ComfortRow row) {
    }
}
