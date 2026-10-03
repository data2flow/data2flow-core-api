package net.java21.data2flow.core.space.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 공간 한 행({@code data2flow_core.spaces}). {@code path}는 조상 ID를 / 로 이은 경로(예: {@code /1/4/9/}, 자기 자신 포함).
 */
public record Space(long id, long organizationId, Long parentId, SpaceType type, String name, String code, String path,
                    int depth, int sortOrder, String usage, BigDecimal areaM2, Integer capacity, String timezone,
                    String address, BigDecimal latitude, BigDecimal longitude, Integer kmaNx, Integer kmaNy,
                    String modeOverride, Instant modeOverrideUntil, boolean scheduleInherit, String status, int version,
                    Instant createdAt, Instant updatedAt) {

    /** 자기 자신을 포함한 조상 ID(루트부터) */
    public List<Long> pathIds() {
        return pathIds(path);
    }

    public static List<Long> pathIds(String path) {
        List<Long> ids = new ArrayList<>();
        if (path == null) {
            return ids;
        }
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                ids.add(Long.parseLong(part));
            }
        }
        return ids;
    }

    /** 이벤트용 경로: 끝 / 를 뺀 형태(contracts SpaceChanged 예 {@code /1/7/31}) */
    public static String eventPath(String path) {
        return path != null && path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /** 이 공간이 {@code other}의 조상이거나 같은가 */
    public boolean isAncestorOrSelfOf(Space other) {
        return other.path().startsWith(path);
    }
}
