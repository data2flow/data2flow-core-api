package net.java21.data2flow.core.sim.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 가상 환경의 core 기준 정보(SIM-01.01·02·07·11, domain-model 머리말 "기준 정보는 DEV에 virtual=true"): 조직의 SIM 소스, 가상 기기·공간,
 * 가상 장비용 모델, 하트비트 카나리 기기(ING-07.05), 가상 데이터 정리 작업 기록. 시뮬레이션 설정·상태는 simulator 소유 {@code data2flow_sim}.
 */
@Repository
public class SimRepository {

    /** 하트비트 카나리 기기 외부 ID(simulator HeartbeatCanary) */
    public static final String HEARTBEAT_EXTERNAL_ID = "__heartbeat__";

    private final JdbcClient jdbc;

    public SimRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ModelRef(long id, String code, String kind, String capabilities) {
    }

    public record VirtualDevice(long id, String name, String externalId, Long spaceId, Long modelId, String kind, String status) {
    }

    public record VirtualSpace(long id, String name, Long parentId, String type, boolean sandbox, String path) {
    }

    /** 조직의 SIM 소스(보관 제외, code 'simulation' 우선) */
    public Optional<Long> findSimSource(long organizationId) {
        return jdbc.sql("""
                        SELECT id FROM data2flow_core.data_sources WHERE organization_id = :org AND type = 'SIMULATION'
                           AND lifecycle <> 'ARCHIVED' ORDER BY (code = 'simulation') DESC, id LIMIT 1""")
                .param("org", organizationId).query(Long.class).optional();
    }

    public boolean existsSourceCode(long organizationId, String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.data_sources WHERE organization_id = :org AND code = :code)")
                .param("org", organizationId).param("code", code).query(Boolean.class).single();
    }

    /** SIM 소스 만들기(ACTIVE, ChirpStack v4 형식 디코더, 모르는 기기 거부: 가상 기기는 core가 먼저 만든다) */
    public long insertSimSource(long organizationId, String code, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.data_sources (organization_id, code, name, type, lifecycle, connection, decoder_key,
                               unknown_device_policy, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :code, '가상 환경', 'SIMULATION', 'ACTIVE', '{}'::jsonb, 'chirpstack-v4', 'REJECT', :user, :user, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("code", code).param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public Optional<ModelRef> findModelByCode(long organizationId, String code) {
        return jdbc.sql("SELECT id, code, kind, capabilities::text AS capabilities FROM data2flow_core.device_models"
                        + " WHERE organization_id = :org AND code = :code")
                .param("org", organizationId).param("code", code)
                .query((rs, n) -> new ModelRef(rs.getLong("id"), rs.getString("code"), rs.getString("kind"), rs.getString("capabilities")))
                .optional();
    }

    /** 가상 장비·센서 모델(protocol VIRTUAL). 같은 코드가 있으면 그대로 */
    public long insertModel(long organizationId, String code, String name, String kind, String capabilitiesJson, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.device_models (organization_id, code, vendor, name, protocol, kind, description, capabilities,
                               created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :code, 'data2flow', :name, 'VIRTUAL', :kind, '가상 환경 기기 모델(SIM-03.01)', CAST(:caps AS jsonb),
                                :user, :user, :now, :now)
                        ON CONFLICT (organization_id, code) DO UPDATE SET updated_at = data2flow_core.device_models.updated_at
                        RETURNING id""")
                .param("org", organizationId).param("code", code).param("name", name).param("kind", kind).param("caps", capabilitiesJson)
                .param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    /** 모델에 드라이버가 연결돼 있지 않으면 연결한다 */
    public void bindDriverIfAbsent(long organizationId, long modelId, long driverId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.driver_bindings (model_id, driver_id, organization_id, created_at)
                        SELECT :model, :driver, :org, :now
                         WHERE NOT EXISTS (SELECT 1 FROM data2flow_core.driver_bindings WHERE organization_id = :org AND model_id = :model)""")
                .param("model", modelId).param("driver", driverId).param("org", organizationId).param("now", Pg.ts(now)).update();
    }

    /**
     * 가상 기기 만들기(ACTIVE, virtual). 외부 ID가 없으면 simulator 규칙대로 {@code 5a1d} + 기기 ID 16진수 12자리(SIM-api API-SIM-33).
     */
    public long insertVirtualDevice(long organizationId, long sourceId, String externalId, String name, String kind, Long modelId,
                                    Long spaceId, long userId, Instant now) {
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.devices (organization_id, source_id, external_id, name, kind, model_id, space_id, status,
                               is_virtual, approved_at, approved_by, source_meta, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :source, :ext, :name, :kind, :model, :space, 'ACTIVE', true, :now, :user, '{"origin":"SIMULATION"}'::jsonb,
                                :user, :user, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("source", sourceId)
                .param("ext", externalId == null ? "__pending__" + java.util.UUID.randomUUID() : externalId).param("name", name)
                .param("kind", kind).param("model", modelId).param("space", spaceId).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
        jdbc.sql("""
                        UPDATE data2flow_core.devices SET logical_device_id = id,
                               external_id = CASE WHEN external_id LIKE '\\_\\_pending\\_\\_%' THEN '5a1d' || lpad(to_hex(id), 12, '0') ELSE external_id END
                         WHERE organization_id = :org AND id = :id""")
                .param("org", organizationId).param("id", id).update();
        return id;
    }

    public Optional<VirtualDevice> findVirtualDevice(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT id, name, external_id, space_id, model_id, kind, status FROM data2flow_core.devices
                         WHERE organization_id = :org AND id = :id AND is_virtual AND status <> 'DELETED'""")
                .param("org", organizationId).param("id", deviceId).query((rs, n) -> new VirtualDevice(rs.getLong("id"), rs.getString("name"),
                        rs.getString("external_id"), Pg.longOrNull(rs, "space_id"), Pg.longOrNull(rs, "model_id"), rs.getString("kind"),
                        rs.getString("status")))
                .optional();
    }

    public List<VirtualDevice> listVirtualDevices(long organizationId) {
        return jdbc.sql("""
                        SELECT id, name, external_id, space_id, model_id, kind, status FROM data2flow_core.devices
                         WHERE organization_id = :org AND is_virtual AND status <> 'DELETED' ORDER BY id""")
                .param("org", organizationId).query((rs, n) -> new VirtualDevice(rs.getLong("id"), rs.getString("name"),
                        rs.getString("external_id"), Pg.longOrNull(rs, "space_id"), Pg.longOrNull(rs, "model_id"), rs.getString("kind"),
                        rs.getString("status")))
                .list();
    }

    public long countVirtualDevices(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.devices WHERE organization_id = :org AND is_virtual AND status <> 'DELETED'"
                        + " AND external_id <> '" + HEARTBEAT_EXTERNAL_ID + "'")
                .param("org", organizationId).query(Long.class).single();
    }

    /** 가상 기기 삭제(DELETED). 바뀐 행 수 */
    public int deleteVirtualDevice(long organizationId, long deviceId, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.devices SET status = 'DELETED', version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND is_virtual AND status <> 'DELETED'""")
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", deviceId).update();
    }

    /** 가상 기기를 지운다(사가 보상: simulator 설정 생성 실패) */
    public void deleteDevices(long organizationId, Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM data2flow_core.devices WHERE organization_id = :org AND is_virtual AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds)).update();
    }

    public List<VirtualSpace> listVirtualSpaces(long organizationId) {
        return jdbc.sql("""
                        SELECT id, name, parent_id, type, sandbox, path FROM data2flow_core.spaces
                         WHERE organization_id = :org AND is_virtual AND status = 'ACTIVE' ORDER BY path""")
                .param("org", organizationId).query((rs, n) -> new VirtualSpace(rs.getLong("id"), rs.getString("name"),
                        Pg.longOrNull(rs, "parent_id"), rs.getString("type"), rs.getBoolean("sandbox"), rs.getString("path")))
                .list();
    }

    /** 가상 공간들을 넣을 가상 뿌리(SITE "가상 환경"). 없으면 빈 값 */
    public Optional<Long> findVirtualRoot(long organizationId) {
        return jdbc.sql("""
                        SELECT id FROM data2flow_core.spaces WHERE organization_id = :org AND is_virtual AND type = 'SITE'
                           AND parent_id IS NULL AND status = 'ACTIVE' ORDER BY id LIMIT 1""")
                .param("org", organizationId).query(Long.class).optional();
    }

    /** 하트비트 카나리 기기(공간 없음, 가상, ACTIVE) */
    public Optional<Long> findHeartbeatDevice(long organizationId, long sourceId) {
        return jdbc.sql("SELECT id FROM data2flow_core.devices WHERE organization_id = :org AND source_id = :source AND external_id = :ext")
                .param("org", organizationId).param("source", sourceId).param("ext", HEARTBEAT_EXTERNAL_ID).query(Long.class).optional();
    }

    public void renameSpace(long organizationId, long spaceId, String name, long userId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.spaces SET name = :name, version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND is_virtual""")
                .param("name", name).param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", spaceId)
                .update();
    }

    /** 가상 공간을 지울 때 그 공간의 가상 기기도 지운다(DELETED). 지운 ID */
    public List<Long> deleteDevicesInSpace(long organizationId, long spaceId, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.devices SET status = 'DELETED', version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND space_id = :space AND is_virtual AND status <> 'DELETED' RETURNING id""")
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("space", spaceId)
                .query(Long.class).list();
    }

    /** 가상 데이터 정리 작업 기록(API-SIM-25·내부 정리). 작업 ID */
    public long insertPurgeJob(long organizationId, String origin, List<String> runIds, Instant from, Instant to, List<Long> spaceIds,
                               Long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.sim_purge_jobs (organization_id, origin, run_ids, range_from, range_to, space_ids,
                               requested_by, created_at, updated_at)
                        VALUES (:org, :origin, CAST(:runs AS text[]), :from, :to, CAST(:spaces AS bigint[]), :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("origin", origin).param("runs", Pg.textArray(runIds)).param("from", Pg.ts(from))
                .param("to", Pg.ts(to)).param("spaces", Pg.bigintArray(spaceIds)).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }
}
