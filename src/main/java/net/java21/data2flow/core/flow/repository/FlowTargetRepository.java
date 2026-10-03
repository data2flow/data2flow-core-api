package net.java21.data2flow.core.flow.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 플로우 노드 대상(공간·기기) 확인(TARGET_MISSING·TARGET_EMPTY, TC-FLW-020). 공간–기기 관계는 DEV-01.05와 같다: 설치 공간에서
 * SENSOR·HYBRID는 MEASURES, ACTUATOR·HYBRID는 CONTROLS, 그 밖은 {@code device_space_relations}.
 */
@Repository
public class FlowTargetRepository {

    private final JdbcClient jdbc;

    public FlowTargetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsSpace(long organizationId, long spaceId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id AND status = 'ACTIVE')")
                .param("org", organizationId).param("id", spaceId).query(Boolean.class).single();
    }

    /** 삭제되지 않은 기기의 설치 공간(공간 없음이면 0) */
    public Optional<Long> findDeviceSpace(long organizationId, long deviceId) {
        return jdbc.sql("SELECT coalesce(space_id, 0) FROM data2flow_core.devices WHERE organization_id = :org AND id = :id AND status <> 'DELETED'")
                .param("org", organizationId).param("id", deviceId).query(Long.class).optional();
    }

    /** 공간(하위 포함 여부)과 관계가 있는 ACTIVE 기기 수. capability가 있으면 모델이 그 기능을 지원하는 기기만 */
    public long countRelated(long organizationId, long spaceId, String relation, boolean includeChildren, String capability) {
        String kinds = "CONTROLS".equals(relation) ? "('ACTUATOR', 'HYBRID')" : "('SENSOR', 'HYBRID')";
        return jdbc.sql("""
                        WITH sp AS (
                            SELECT s.id FROM data2flow_core.spaces s
                              JOIN data2flow_core.spaces p ON p.id = :space AND p.organization_id = s.organization_id
                             WHERE s.organization_id = :org AND (s.id = p.id OR (:children AND s.path LIKE p.path || '%'))
                        ), r AS (
                            SELECT d.id FROM data2flow_core.devices d
                             WHERE d.organization_id = :org AND d.status = 'ACTIVE' AND d.kind IN """ + kinds + """
                               AND d.space_id IN (SELECT id FROM sp)
                            UNION
                            SELECT x.device_id FROM data2flow_core.device_space_relations x
                             WHERE x.organization_id = :org AND x.relation = :relation AND x.space_id IN (SELECT id FROM sp)
                        )
                        SELECT count(*) FROM r JOIN data2flow_core.devices d ON d.id = r.id AND d.organization_id = :org
                         WHERE d.status = 'ACTIVE' AND (CAST(:capability AS varchar) IS NULL OR EXISTS (
                               SELECT 1 FROM data2flow_core.device_models m, jsonb_array_elements(m.capabilities) c
                                WHERE m.id = d.model_id AND c->>'capability' = :capability))""")
                .param("org", organizationId).param("space", spaceId).param("children", includeChildren).param("relation", relation)
                .param("capability", capability).query(Long.class).single();
    }

    /** 조직 사용자(책임자 지정, FLOW_OWNER_INVALID) */
    public boolean existsActiveUser(long organizationId, long userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.app_users WHERE organization_id = :org AND id = :id AND status = 'ACTIVE')")
                .param("org", organizationId).param("id", userId).query(Boolean.class).single();
    }
}
