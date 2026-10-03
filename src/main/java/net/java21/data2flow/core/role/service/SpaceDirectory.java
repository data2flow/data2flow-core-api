package net.java21.data2flow.core.role.service;

import java.util.Collection;
import java.util.Set;

/**
 * 공간 트리(DEV-01.01)에 대한 권한 쪽 확장 지점. 공간 단위 권한(IAM-04.02·04.05·04.06)은 공간 계층이 생기는 M2에서 채운다.
 *
 * <ul>
 *   <li>{@link #existingSpaceIds}: 역할·초대·승인에 지정한 공간이 조직에 있는지(없으면 {@code SPACE_SCOPE_INVALID})</li>
 *   <li>{@link #expandWithDescendants}: user_roles.space_scope를 하위 공간까지 펼친다(contracts {@code SpaceScope}의 전제)</li>
 * </ul>
 */
public interface SpaceDirectory {

    /** 주어진 ID 중 조직에 실제로 있는 공간 ID */
    Set<Long> existingSpaceIds(long organizationId, Collection<Long> spaceIds);

    /** 하위 공간을 포함해 펼친 ID 집합 */
    Set<Long> expandWithDescendants(long organizationId, Collection<Long> spaceIds);
}
