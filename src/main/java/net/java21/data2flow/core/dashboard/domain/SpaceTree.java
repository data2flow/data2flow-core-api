package net.java21.data2flow.core.dashboard.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 조직의 ACTIVE 공간 트리(읽기 전용 사본). 쾌적도 계산에 필요한 하위 공간 펼치기, 조상 경로, 목표 환경 상속(BR-DEV-04)을 한다.
 *
 * <p>목표 환경 상속은 <b>묶음 단위</b>다: 공간에 목표 행이 하나라도 있으면 그 공간의 목표만 쓰고, 없으면 가장 가까운 조상의 묶음을 쓴다
 * (API-DEV 목표 조회의 {@code inherit}와 같은 뜻 — 하위 공간이 따로 정하면 상위 값을 쓰지 않는다).
 */
public final class SpaceTree {

    /** 공간 한 행 */
    public record Node(long id, Long parentId, String name, String type, String path, int sortOrder) {
    }

    private final Map<Long, Node> nodes = new HashMap<>();
    private final Map<Long, List<Long>> children = new HashMap<>();
    private final Map<Long, List<Comfort.Target>> ownTargets;

    public SpaceTree(Collection<Node> rows, Map<Long, List<Comfort.Target>> targetsBySpace) {
        for (Node n : rows) {
            nodes.put(n.id(), n);
        }
        for (Node n : rows) {
            if (n.parentId() != null && nodes.containsKey(n.parentId())) {
                children.computeIfAbsent(n.parentId(), k -> new ArrayList<>()).add(n.id());
            }
        }
        this.ownTargets = targetsBySpace;
    }

    public Node node(long id) {
        return nodes.get(id);
    }

    public boolean contains(long id) {
        return nodes.containsKey(id);
    }

    public Collection<Node> all() {
        return nodes.values();
    }

    public List<Node> childrenOf(long id) {
        return children.getOrDefault(id, List.of()).stream().map(nodes::get)
                .sorted((a, b) -> a.sortOrder() != b.sortOrder() ? Integer.compare(a.sortOrder(), b.sortOrder()) : a.name().compareTo(b.name()))
                .toList();
    }

    /** 이 공간과 모든 하위 공간 ID(없는 공간이면 빈 집합) */
    public Set<Long> subtree(long id) {
        Set<Long> result = new LinkedHashSet<>();
        if (!nodes.containsKey(id)) {
            return result;
        }
        List<Long> stack = new ArrayList<>(List.of(id));
        while (!stack.isEmpty()) {
            long current = stack.removeLast();
            if (result.add(current)) {
                stack.addAll(children.getOrDefault(current, List.of()));
            }
        }
        return result;
    }

    /** 최상위부터 이 공간까지(자기 포함) */
    public List<Node> pathOf(long id) {
        List<Node> path = new ArrayList<>();
        Node current = nodes.get(id);
        int guard = 0;
        while (current != null && guard++ < 16) {
            path.addFirst(current);
            current = current.parentId() == null ? null : nodes.get(current.parentId());
        }
        return path;
    }

    /** 실제 목표 환경과 그 출처 공간(상속, BR-DEV-04). 어디에도 없으면 빈 목록, 출처 null */
    public EffectiveTargets effectiveTargets(long id) {
        Node current = nodes.get(id);
        int guard = 0;
        while (current != null && guard++ < 16) {
            List<Comfort.Target> own = ownTargets.get(current.id());
            if (own != null && !own.isEmpty()) {
                return new EffectiveTargets(own, current.id());
            }
            current = current.parentId() == null ? null : nodes.get(current.parentId());
        }
        return new EffectiveTargets(List.of(), null);
    }

    public record EffectiveTargets(List<Comfort.Target> targets, Long fromSpaceId) {
    }
}
