package net.java21.data2flow.core.device.repository;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.device.domain.Device;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 기기({@code data2flow_core.devices})와 태그({@code device_tags}) 저장(DEV-02.01·02.02·02.03·02.10) */
@Repository
public class DeviceRepository {

    static final String COLUMNS = """
            d.id, d.organization_id, d.source_id, d.external_id, d.name, d.kind, d.model_id, d.space_id, d.suggested_space_id,
            d.status, d.is_virtual, d.expected_interval_sec, d.offline_multiplier, d.approved_at, d.approved_by, d.auto_registered,
            d.first_seen_at, d.source_meta::text AS source_meta, d.logical_device_id, d.replaced_by_device_id, d.version,
            d.created_at, d.updated_at""";

    private final JdbcClient jdbc;

    public DeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Device> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.devices d WHERE d.organization_id = :org AND d.id = :id")
                .param("org", organizationId).param("id", id).query(DeviceRepository::map).optional();
    }

    /** 여러 대를 한 번에(삭제 포함). 순서는 ID 순 */
    public List<Device> findByIds(long organizationId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.devices d WHERE d.organization_id = :org"
                        + " AND d.id = ANY(CAST(:ids AS bigint[])) ORDER BY d.id")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids)).query(DeviceRepository::map).list();
    }

    public Optional<Device> findBySourceAndExternalId(long organizationId, long sourceId, String externalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.devices d"
                        + " WHERE d.organization_id = :org AND d.source_id = :source AND d.external_id = :ext")
                .param("org", organizationId).param("source", sourceId).param("ext", externalId)
                .query(DeviceRepository::map).optional();
    }

    /**
     * 새 기기. 같은 (소스, 외부 ID)가 이미 있으면(동시 등록 포함) 빈 값 — 유일 제약이 한 건만 남긴다(UC-ING-03 2d-1).
     * 처음 등록된 기기는 자기 자신이 논리 기기다(domain-model §2.18).
     */
    public Optional<Long> insert(NewDevice d) {
        Optional<Long> id = jdbc.sql("""
                        INSERT INTO data2flow_core.devices (organization_id, source_id, external_id, name, kind, model_id, space_id,
                            suggested_space_id, status, is_virtual, expected_interval_sec, offline_multiplier, approved_at, approved_by,
                            auto_registered, first_seen_at, source_meta, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :source, :ext, :name, :kind, :model, :space, :suggested, :status, :virtual, :interval, :multiplier,
                                :approvedAt, :approvedBy, :auto, :firstSeen, CAST(:meta AS jsonb), :by, :by, :now, :now)
                        ON CONFLICT (source_id, external_id) DO NOTHING
                        RETURNING id""")
                .param("org", d.organizationId()).param("source", d.sourceId()).param("ext", d.externalId()).param("name", d.name())
                .param("kind", d.kind()).param("model", d.modelId()).param("space", d.spaceId()).param("suggested", d.suggestedSpaceId())
                .param("status", d.status()).param("virtual", d.virtual()).param("interval", d.expectedIntervalSec())
                .param("multiplier", d.offlineMultiplier()).param("approvedAt", Pg.ts(d.approvedAt())).param("approvedBy", d.approvedBy())
                .param("auto", d.autoRegistered()).param("firstSeen", Pg.ts(d.firstSeenAt())).param("meta", d.sourceMeta())
                .param("by", d.createdBy()).param("now", Pg.ts(d.now()))
                .query(Long.class).optional();
        id.ifPresent(value -> jdbc.sql("UPDATE data2flow_core.devices SET logical_device_id = id WHERE id = :id AND organization_id = :org")
                .param("id", value).param("org", d.organizationId()).update());
        return id;
    }

    /** 삭제(DELETED)된 행을 같은 키로 다시 쓴다(거부 뒤 무시 목록에서 풀린 기기의 재발견, 삭제한 기기의 재등록) */
    public int revive(long id, NewDevice d) {
        return jdbc.sql("""
                        UPDATE data2flow_core.devices
                           SET name = :name, kind = :kind, model_id = :model, space_id = :space, suggested_space_id = :suggested,
                               status = :status, is_virtual = :virtual, expected_interval_sec = :interval, offline_multiplier = :multiplier,
                               approved_at = :approvedAt, approved_by = :approvedBy, auto_registered = :auto, first_seen_at = :firstSeen,
                               source_meta = CAST(:meta AS jsonb), replaced_by_device_id = NULL, replaced_at = NULL,
                               version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'DELETED'""")
                .param("name", d.name()).param("kind", d.kind()).param("model", d.modelId()).param("space", d.spaceId())
                .param("suggested", d.suggestedSpaceId()).param("status", d.status()).param("virtual", d.virtual())
                .param("interval", d.expectedIntervalSec()).param("multiplier", d.offlineMultiplier())
                .param("approvedAt", Pg.ts(d.approvedAt())).param("approvedBy", d.approvedBy()).param("auto", d.autoRegistered())
                .param("firstSeen", Pg.ts(d.firstSeenAt())).param("meta", d.sourceMeta()).param("by", d.createdBy())
                .param("now", Pg.ts(d.now())).param("org", d.organizationId()).param("id", id)
                .update();
    }

    /** API-DEV-13 수정(온 필드만 바뀐 값으로 넘어온다) */
    public int update(long organizationId, long id, int baseVersion, String name, String kind, Long modelId, Long spaceId,
                      Integer interval, BigDecimal multiplier, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.devices
                           SET name = :name, kind = :kind, model_id = :model, space_id = :space, expected_interval_sec = :interval,
                               offline_multiplier = :multiplier, version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base AND status <> 'DELETED'""")
                .param("name", name).param("kind", kind).param("model", modelId).param("space", spaceId).param("interval", interval)
                .param("multiplier", multiplier).param("by", updatedBy).param("now", Pg.ts(now)).param("org", organizationId)
                .param("id", id).param("base", baseVersion)
                .update();
    }

    /** 상태 전이(활성·비활성·삭제). 현재 상태가 {@code from}이고 버전이 맞을 때만 */
    public int updateStatus(long organizationId, long id, int baseVersion, Collection<String> from, String to, long updatedBy,
                            Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.devices SET status = :to, version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base AND status = ANY(CAST(:from AS text[]))""")
                .param("to", to).param("by", updatedBy).param("now", Pg.ts(now)).param("org", organizationId).param("id", id)
                .param("base", baseVersion).param("from", Pg.textArray(from))
                .update();
    }

    /** 승인(PENDING → ACTIVE, AT-DEV-03.6: 동시에 두 번 승인하면 한 건만 바뀐다) */
    public int approve(long organizationId, long id, int baseVersion, long modelId, long spaceId, String kind, String name,
                       long approvedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.devices
                           SET status = 'ACTIVE', model_id = :model, space_id = :space, kind = :kind, name = :name,
                               approved_at = :now, approved_by = :by, version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base AND status = 'PENDING'""")
                .param("model", modelId).param("space", spaceId).param("kind", kind).param("name", name).param("now", Pg.ts(now))
                .param("by", approvedBy).param("org", organizationId).param("id", id).param("base", baseVersion)
                .update();
    }

    /** 거부(PENDING → DELETED) */
    public int reject(long organizationId, long id, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.devices SET status = 'DELETED', version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'PENDING'""")
                .param("by", updatedBy).param("now", Pg.ts(now)).param("org", organizationId).param("id", id)
                .update();
    }

    /** 버전만 올린다(태그·속성처럼 다른 테이블이 바뀌었을 때 캐시 무효화용) */
    public int updateVersion(long organizationId, long id, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.devices SET version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("by", updatedBy).param("now", Pg.ts(now)).param("org", organizationId).param("id", id)
                .update();
    }

    /** 다시 수신된 PENDING 기기의 원본 정보(deviceName·tags) 갱신(최상위 키 단위로 덮어쓰기). 버전은 바꾸지 않는다(승인 화면의 baseVersion 유지) */
    public void updatePendingMeta(long organizationId, long id, String sourceMetaJson, Long suggestedSpaceId) {
        jdbc.sql("""
                        UPDATE data2flow_core.devices SET source_meta = source_meta || CAST(:meta AS jsonb),
                               suggested_space_id = coalesce(:suggested, suggested_space_id)
                         WHERE organization_id = :org AND id = :id AND status = 'PENDING'""")
                .param("meta", sourceMetaJson).param("suggested", suggestedSpaceId).param("org", organizationId).param("id", id)
                .update();
    }

    public List<String> findTags(long organizationId, long deviceId) {
        return jdbc.sql("SELECT tag FROM data2flow_core.device_tags WHERE organization_id = :org AND device_id = :id ORDER BY created_at, tag")
                .param("org", organizationId).param("id", deviceId).query(String.class).list();
    }

    public Map<Long, List<String>> findTags(long organizationId, Collection<Long> deviceIds) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        if (deviceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT device_id, tag FROM data2flow_core.device_tags
                         WHERE organization_id = :org AND device_id = ANY(CAST(:ids AS bigint[])) ORDER BY device_id, created_at, tag""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds))
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.computeIfAbsent(rs.getLong("device_id"), k -> new java.util.ArrayList<>()).add(rs.getString("tag")));
        return result;
    }

    /** 태그 전체 교체. 남는 태그의 처음 저장 시각은 유지한다(표시 순서) */
    public void replaceTags(long organizationId, long deviceId, List<String> tags, Instant now) {
        jdbc.sql("DELETE FROM data2flow_core.device_tags WHERE organization_id = :org AND device_id = :id AND tag <> ALL(CAST(:tags AS text[]))")
                .param("org", organizationId).param("id", deviceId).param("tags", Pg.textArray(tags)).update();
        int i = 0;
        for (String tag : tags) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.device_tags (organization_id, device_id, tag, created_at)
                            VALUES (:org, :id, :tag, :at) ON CONFLICT (device_id, tag) DO NOTHING""")
                    .param("org", organizationId).param("id", deviceId).param("tag", tag)
                    .param("at", Pg.ts(now.plusNanos(i++ * 1000L))).update();
        }
    }

    /** 삭제한 기기의 플랫폼 브로커 자격을 모두 폐기한다(BR-DSC-14) */
    public int updateCredentialsRevoked(long organizationId, long deviceId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.device_credentials SET status = 'REVOKED', revoked_at = :now, updated_at = :now
                         WHERE organization_id = :org AND device_id = :id AND status = 'ACTIVE'""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", deviceId).update();
    }

    static Device map(ResultSet rs, int n) throws SQLException {
        int interval = rs.getInt("expected_interval_sec");
        Integer expected = rs.wasNull() ? null : interval;
        return new Device(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("source_id"), rs.getString("external_id"),
                rs.getString("name"), rs.getString("kind"), Pg.longOrNull(rs, "model_id"), Pg.longOrNull(rs, "space_id"),
                Pg.longOrNull(rs, "suggested_space_id"), rs.getString("status"), rs.getBoolean("is_virtual"), expected,
                rs.getBigDecimal("offline_multiplier"), Pg.instant(rs, "approved_at"), Pg.longOrNull(rs, "approved_by"),
                rs.getBoolean("auto_registered"), Pg.instant(rs, "first_seen_at"), rs.getString("source_meta"),
                Pg.longOrNull(rs, "logical_device_id"), Pg.longOrNull(rs, "replaced_by_device_id"), rs.getInt("version"),
                Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }

    /** 새 기기 값 */
    public record NewDevice(long organizationId, long sourceId, String externalId, String name, String kind, Long modelId,
                            Long spaceId, Long suggestedSpaceId, String status, boolean virtual, Integer expectedIntervalSec,
                            BigDecimal offlineMultiplier, Instant approvedAt, Long approvedBy, boolean autoRegistered,
                            Instant firstSeenAt, String sourceMeta, Long createdBy, Instant now) {
    }
}
