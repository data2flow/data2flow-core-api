package net.java21.data2flow.core.device.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 기기 목록 조건(API-DEV-11·20, IAM-04.06). {@code allowedSpaceIds}가 null이면 공간 범위 제한 없음,
 * 아니면 이 공간(하위까지 펼친 집합)에 놓인 기기만 보인다(공간이 없는 승인 대기 기기도 숨긴다, BR-DEV-25).
 *
 * @param virtual null이면 실제·가상 모두
 * @param onboarding INCOMPLETE·COMPLETE 또는 null
 * @param updatedAfter 내부 캐시 예열(API-DEV-130)용
 * @param expression 기기 검색식 조건(DEV-13.03, API-DEV-133). 없으면 null
 */
public record DeviceFilter(long organizationId, String q, List<String> statuses, List<String> connectivities, List<String> kinds,
                           Long modelId, Long spaceId, boolean includeDescendants, Long sourceId, List<String> tags, Long groupId,
                           Boolean virtual, String onboarding, Set<Long> allowedSpaceIds, Instant updatedAfter,
                           SqlCondition expression) {

    public DeviceFilter(long organizationId, String q, List<String> statuses, List<String> connectivities, List<String> kinds,
                        Long modelId, Long spaceId, boolean includeDescendants, Long sourceId, List<String> tags, Long groupId,
                        Boolean virtual, String onboarding, Set<Long> allowedSpaceIds, Instant updatedAfter) {
        this(organizationId, q, statuses, connectivities, kinds, modelId, spaceId, includeDescendants, sourceId, tags, groupId, virtual,
                onboarding, allowedSpaceIds, updatedAfter, null);
    }

    /** 검색식 조건을 붙인 사본 */
    public DeviceFilter withExpression(SqlCondition condition) {
        return new DeviceFilter(organizationId, q, statuses, connectivities, kinds, modelId, spaceId, includeDescendants, sourceId, tags,
                groupId, virtual, onboarding, allowedSpaceIds, updatedAfter, condition);
    }

    public DeviceFilter {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        connectivities = connectivities == null ? List.of() : List.copyOf(connectivities);
        kinds = kinds == null ? List.of() : List.copyOf(kinds);
        tags = tags == null ? List.of() : List.copyOf(tags);
        allowedSpaceIds = allowedSpaceIds == null ? null : Set.copyOf(allowedSpaceIds);
    }

    /** 조직 전체(내부·그룹 계산용) */
    public static DeviceFilter all(long organizationId) {
        return new DeviceFilter(organizationId, null, null, null, null, null, null, true, null, null, null, null, null, null, null, null);
    }
}
