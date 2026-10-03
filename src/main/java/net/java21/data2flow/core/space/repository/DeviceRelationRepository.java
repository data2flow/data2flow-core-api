package net.java21.data2flow.core.space.repository;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 공간–기기 관계(DEV-01.05, {@code device_space_relations}). 설치 공간({@code devices.space_id})에서 생기는 자동 관계는 저장하지 않고
 * 조회할 때 기기 종류로 만든다: SENSOR → MEASURES, ACTUATOR → CONTROLS, HYBRID → 둘 다, GATEWAY → 없음(domain-model §2.4).
 * 저장 행은 설치 공간 밖을 추가로 측정·제어하는 관계만이다. 기기 행({@code devices})은 기기 기능 소유이고 여기서는 읽기만 한다.
 *
 * <p>기능(capability)은 모델의 {@code device_models.capabilities}(jsonb {@code [{capability, constraints}]}, DEV-03.01)에서 읽는다.
 * ACT가 M3에서 {@code model_capabilities} 테이블을 만들면 그쪽으로 옮긴다.
 * 그 테이블이 아직 없으면(M2 앞부분) 기능 목록은 비고 기능 조건은 아무것도 맞지 않는다.
 */
@Repository
public class DeviceRelationRepository {

    private final JdbcClient jdbc;

    public DeviceRelationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 삭제되지 않은 기기 */
    public Optional<DeviceRef> findDevice(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT id, name, kind, status, space_id, model_id FROM data2flow_core.devices
                         WHERE organization_id = :org AND id = :id AND status <> 'DELETED'""")
                .param("org", organizationId).param("id", deviceId)
                .query((rs, n) -> new DeviceRef(rs.getLong("id"), rs.getString("name"), rs.getString("kind"), rs.getString("status"),
                        Pg.longOrNull(rs, "space_id"), Pg.longOrNull(rs, "model_id")))
                .optional();
    }

    /** 삭제되지 않은 기기들 */
    public List<DeviceRef> findDevices(long organizationId, Collection<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, name, kind, status, space_id, model_id FROM data2flow_core.devices
                         WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[])) AND status <> 'DELETED'""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds))
                .query((rs, n) -> new DeviceRef(rs.getLong("id"), rs.getString("name"), rs.getString("kind"), rs.getString("status"),
                        Pg.longOrNull(rs, "space_id"), Pg.longOrNull(rs, "model_id")))
                .list();
    }

    /** 저장된(추가) 관계 */
    public List<StoredRelation> findRelations(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT space_id, relation FROM data2flow_core.device_space_relations
                         WHERE organization_id = :org AND device_id = :device ORDER BY space_id, relation""")
                .param("org", organizationId).param("device", deviceId)
                .query((rs, n) -> new StoredRelation(rs.getLong("space_id"), rs.getString("relation")))
                .list();
    }

    /** 추가 관계 전체 교체 */
    public void replaceRelations(long organizationId, long deviceId, List<StoredRelation> items, Instant now) {
        jdbc.sql("DELETE FROM data2flow_core.device_space_relations WHERE organization_id = :org AND device_id = :device")
                .param("org", organizationId).param("device", deviceId).update();
        for (StoredRelation item : items) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.device_space_relations (organization_id, device_id, space_id, relation, created_at)
                            VALUES (:org, :device, :space, :relation, :now)""")
                    .param("org", organizationId).param("device", deviceId).param("space", item.spaceId())
                    .param("relation", item.relation()).param("now", Pg.ts(now)).update();
        }
    }

    /**
     * 공간(들)과 관계가 있는 기기(API-DEV-18·128). 기기·관계 쌍마다 한 행, 이름순.
     *
     * @param spaceIds   대상 공간(하위 포함이면 펼친 집합)
     * @param relation   MEASURES·CONTROLS 또는 null(둘 다)
     * @param capability 모델 기능 이름(대소문자 무시) 또는 null
     * @param scope      사용자 공간 범위(기기 설치 공간 기준, IAM-04.06). 내부 API는 null
     */
    public List<SpaceDeviceRow> findSpaceDevices(long organizationId, Collection<Long> spaceIds, String relation, String capability,
                                                 SpaceScope scope) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("org", organizationId);
        params.put("spaces", Pg.bigintArray(spaceIds));
        StringBuilder where = new StringBuilder();
        if (relation != null) {
            where.append(" AND r.relation = :relation");
            params.put("relation", relation);
        }
        if (capability != null) {
            where.append(" AND EXISTS (SELECT 1 FROM data2flow_core.device_models cm, jsonb_array_elements(cm.capabilities) c WHERE cm.id = d.model_id AND lower(c->>'capability') = lower(:capability))");
            params.put("capability", capability);
        }
        where.append(SpaceScopeSql.and(scope, "d.space_id", params));
        String capabilitiesColumn =
                "ARRAY(SELECT DISTINCT c->>'capability' FROM data2flow_core.device_models cm, jsonb_array_elements(cm.capabilities) c WHERE cm.id = d.model_id ORDER BY 1)";
        return jdbc.sql("""
                        WITH r AS (
                            SELECT d.id AS device_id, 'MEASURES' AS relation, d.space_id FROM data2flow_core.devices d
                             WHERE d.organization_id = :org AND d.status <> 'DELETED' AND d.kind IN ('SENSOR', 'HYBRID')
                               AND d.space_id = ANY(CAST(:spaces AS bigint[]))
                            UNION
                            SELECT d.id, 'CONTROLS', d.space_id FROM data2flow_core.devices d
                             WHERE d.organization_id = :org AND d.status <> 'DELETED' AND d.kind IN ('ACTUATOR', 'HYBRID')
                               AND d.space_id = ANY(CAST(:spaces AS bigint[]))
                            UNION
                            SELECT x.device_id, x.relation, x.space_id FROM data2flow_core.device_space_relations x
                             WHERE x.organization_id = :org AND x.space_id = ANY(CAST(:spaces AS bigint[]))
                        )
                        SELECT * FROM (
                            SELECT DISTINCT ON (d.id, r.relation) d.id, d.name, d.kind, d.status, r.relation, r.space_id, d.model_id,
                                   m.name AS model_name, st.connectivity, st.last_seen_at, """ + capabilitiesColumn + """
                                    AS capabilities
                              FROM r
                              JOIN data2flow_core.devices d ON d.id = r.device_id AND d.organization_id = :org AND d.status <> 'DELETED'
                              LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id
                              LEFT JOIN data2flow_pipeline.device_state st ON st.device_id = d.id
                             WHERE 1 = 1""" + where + """
                             ORDER BY d.id, r.relation, r.space_id
                        ) x ORDER BY x.name, x.id, x.relation LIMIT 5000""")
                .params(params)
                .query((rs, n) -> new SpaceDeviceRow(rs.getLong("id"), rs.getString("name"), rs.getString("kind"), rs.getString("status"),
                        rs.getString("relation"), rs.getLong("space_id"), Pg.longOrNull(rs, "model_id"), rs.getString("model_name"),
                        Pg.stringList(rs, "capabilities"), rs.getString("connectivity"), Pg.instant(rs, "last_seen_at")))
                .list();
    }

    /** 기기 요약 */
    public record DeviceRef(long id, String name, String kind, String status, Long spaceId, Long modelId) {
    }

    /** 저장된 관계 */
    public record StoredRelation(long spaceId, String relation) {
    }

    /** 공간 기기 한 행 */
    public record SpaceDeviceRow(long deviceId, String name, String kind, String status, String relation, long spaceId,
                                 Long modelId, String modelName, List<String> capabilities, String connectivity,
                                 Instant lastSeenAt) {
    }
}
