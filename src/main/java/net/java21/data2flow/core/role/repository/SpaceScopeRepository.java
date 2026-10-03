package net.java21.data2flow.core.role.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 권한 쪽 공간 조회(IAM-04.02·04.05). 공간 트리 {@code data2flow_core.spaces}의 {@code path}(조상 ID를 / 로 이은 경로,
 * 예: {@code /1/4/9/})로 하위 공간을 한 번에 펼친다. 보관(ARCHIVED)된 공간은 지정할 수 없다.
 */
@Repository
public class SpaceScopeRepository {

    private final JdbcClient jdbc;

    public SpaceScopeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<Long> findExistingIds(long organizationId, Collection<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT id FROM data2flow_core.spaces
                         WHERE organization_id = :org AND status = 'ACTIVE' AND id = ANY(CAST(:ids AS bigint[]))""")
                .param("org", organizationId).param("ids", Pg.bigintArray(spaceIds))
                .query(Long.class).list());
    }

    /** 주어진 공간과 그 하위 공간 전체(ACTIVE만) */
    public Set<Long> findWithDescendants(long organizationId, Collection<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT DISTINCT d.id
                          FROM data2flow_core.spaces a
                          JOIN data2flow_core.spaces d ON d.organization_id = a.organization_id AND d.path LIKE a.path || '%'
                         WHERE a.organization_id = :org AND a.id = ANY(CAST(:ids AS bigint[])) AND d.status = 'ACTIVE'""")
                .param("org", organizationId).param("ids", Pg.bigintArray(spaceIds))
                .query(Long.class).list());
    }

    /** 공간 이름(회원 목록·상세의 공간 범위 표시, IAM-01.07). 보관된 공간도 이름을 돌려준다 */
    public Map<Long, String> findNames(long organizationId, Collection<Long> spaceIds) {
        Map<Long, String> names = new HashMap<>();
        if (spaceIds == null || spaceIds.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, name FROM data2flow_core.spaces WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(spaceIds))
                .query((rs, n) -> names.put(rs.getLong("id"), rs.getString("name"))).list();
        return names;
    }
}
