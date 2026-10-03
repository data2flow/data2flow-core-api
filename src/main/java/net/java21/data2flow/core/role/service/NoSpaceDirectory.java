package net.java21.data2flow.core.role.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;

/**
 * M1 구현: 공간 테이블({@code data2flow_core.spaces})이 아직 없으므로 지정 가능한 공간이 없다. 공간 범위를 지정하면
 * {@code SPACE_SCOPE_INVALID}가 되고, 빈 범위(전체 공간)만 쓸 수 있다. M2(DEV-01.01)에서 재귀 쿼리 구현으로 바꾼다.
 */
@Component
public class NoSpaceDirectory implements SpaceDirectory {

    @Override
    public Set<Long> existingSpaceIds(long organizationId, Collection<Long> spaceIds) {
        return Set.of();
    }

    @Override
    public Set<Long> expandWithDescendants(long organizationId, Collection<Long> spaceIds) {
        return spaceIds == null ? Set.of() : Set.copyOf(spaceIds);
    }
}
