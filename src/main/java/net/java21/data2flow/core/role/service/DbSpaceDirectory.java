package net.java21.data2flow.core.role.service;

import net.java21.data2flow.core.role.repository.SpaceScopeRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;

/**
 * 공간 트리(DEV-01.01) 기반 구현(IAM-04.02). 사용자 공간 범위는 지정한 공간과 그 하위 공간 전체이고(BR-IAM-16),
 * 요청마다 DB에서 펼친다(BR-IAM-13: 공간을 옮기거나 추가하면 다음 요청부터 반영).
 */
@Component
public class DbSpaceDirectory implements SpaceDirectory {

    private final SpaceScopeRepository spaces;

    public DbSpaceDirectory(SpaceScopeRepository spaces) {
        this.spaces = spaces;
    }

    @Override
    public Set<Long> existingSpaceIds(long organizationId, Collection<Long> spaceIds) {
        return spaces.findExistingIds(organizationId, spaceIds);
    }

    @Override
    public Set<Long> expandWithDescendants(long organizationId, Collection<Long> spaceIds) {
        return spaces.findWithDescendants(organizationId, spaceIds);
    }
}
