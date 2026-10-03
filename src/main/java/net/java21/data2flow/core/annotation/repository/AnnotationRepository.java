package net.java21.data2flow.core.annotation.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 시계열 주석({@code data2flow_core.annotations}, TSD-01.04). 기기 주석의 공간은 기기의 현재 공간(devices.space_id)을 따른다
 * (공간 범위 IAM-04.06). 기기·공간이 모두 없는 주석은 조직 전체 주석이다.
 */
@Repository
public class AnnotationRepository {

    private static final String COLUMNS = """
            a.id, a.organization_id, a.time_from, a.time_to, a.device_id, a.space_id, a.metric_key, a.type, a.title, a.ref,
            a.created_by, a.created_at, d.space_id AS device_space_id""";

    private static final String FROM = """
             FROM data2flow_core.annotations a
             LEFT JOIN data2flow_core.devices d ON d.id = a.device_id AND d.organization_id = a.organization_id""";

    /** 기간 겹침: 진행 중 구간(ALARM·OFFLINE·SCRIPT_ERROR, time_to 없음)은 끝이 열린 구간, 나머지 time_to 없음은 시점 */
    private static final String WHERE = """
             WHERE a.organization_id = :org
               AND a.time_from < :to
               AND coalesce(a.time_to, CASE WHEN a.type IN ('ALARM', 'OFFLINE', 'SCRIPT_ERROR') THEN 'infinity'::timestamptz
                                            ELSE a.time_from END) >= :from
               AND (CAST(:types AS text[]) IS NULL OR a.type = ANY (CAST(:types AS text[])))
               AND (CAST(:metric AS varchar) IS NULL OR a.metric_key IS NULL OR a.metric_key = :metric)
               AND (:mode = 'ALL'
                    OR (:mode = 'DEVICE' AND (a.device_id = CAST(:device AS bigint)
                        OR (a.device_id IS NULL AND (a.space_id IS NULL OR a.space_id = ANY (CAST(:ancestors AS bigint[]))))))
                    OR (:mode = 'SPACE' AND (
                        (a.device_id IS NULL AND (a.space_id IS NULL OR a.space_id = ANY (CAST(:ancestors AS bigint[]))
                            OR a.space_id IN (SELECT s.id FROM data2flow_core.spaces s WHERE s.organization_id = :org AND s.path LIKE :prefix)))
                        OR (a.device_id IS NOT NULL AND d.space_id IN (SELECT s.id FROM data2flow_core.spaces s
                                                                       WHERE s.organization_id = :org AND s.path LIKE :prefix)))))
               AND (:unrestricted OR (CASE WHEN a.device_id IS NOT NULL THEN d.space_id ELSE a.space_id END) IS NULL
                    OR (CASE WHEN a.device_id IS NOT NULL THEN d.space_id ELSE a.space_id END) = ANY (CAST(:allowed AS bigint[])))
            """;

    private final JdbcClient jdbc;

    public AnnotationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AnnotationRow> search(AnnotationSearch search, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " " + WHERE + " ORDER BY a.time_from, a.id LIMIT :limit OFFSET :offset")
                .params(params(search)).param("limit", limit).param("offset", offset)
                .query(AnnotationRepository::map).list();
    }

    public long countSearch(AnnotationSearch search) {
        return jdbc.sql("SELECT count(*)" + " " + FROM + " " + WHERE).params(params(search)).query(Long.class).single();
    }

    public Optional<AnnotationRow> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " WHERE a.organization_id = :org AND a.id = :id")
                .param("org", organizationId).param("id", id).query(AnnotationRepository::map).optional();
    }

    public long insert(long organizationId, Instant timeFrom, Instant timeTo, Long deviceId, Long spaceId, String metricKey,
                       String type, String title, String ref, Long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.annotations (organization_id, time_from, time_to, device_id, space_id, metric_key, type,
                                                                title, ref, created_by, created_at)
                        VALUES (:org, :from, :to, :device, :space, :metric, :type, :title, :ref, :by, :now) RETURNING id""")
                .param("org", organizationId).param("from", Pg.ts(timeFrom)).param("to", Pg.ts(timeTo)).param("device", deviceId)
                .param("space", spaceId).param("metric", metricKey).param("type", type).param("title", title).param("ref", ref)
                .param("by", createdBy).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.annotations WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    /** 아직 끝나지 않은 시스템 주석(같은 원천). deviceId·ref 중 주어진 것으로 찾는다 */
    public Optional<Long> findOpen(long organizationId, String type, Long deviceId, String ref) {
        return jdbc.sql("""
                        SELECT id FROM data2flow_core.annotations
                         WHERE organization_id = :org AND type = :type AND time_to IS NULL
                           AND (CAST(:device AS bigint) IS NULL OR device_id = :device)
                           AND (CAST(:ref AS varchar) IS NULL OR ref = :ref)
                         ORDER BY time_from DESC LIMIT 1""")
                .param("org", organizationId).param("type", type).param("device", deviceId).param("ref", ref)
                .query(Long.class).optional();
    }

    /** 원천 해제: time_to를 채운다(시작보다 이르면 시작 시각) */
    public int updateEnd(long organizationId, long id, Instant at) {
        return jdbc.sql("""
                        UPDATE data2flow_core.annotations SET time_to = GREATEST(time_from, :at)
                         WHERE organization_id = :org AND id = :id AND time_to IS NULL""")
                .param("at", Pg.ts(at)).param("org", organizationId).param("id", id).update();
    }

    /** 기기(삭제 안 됨)와 그 공간. 기기가 없으면 빈 값 */
    public Optional<DeviceSpace> findDevice(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT d.id, d.space_id, s.path FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.spaces s ON s.id = d.space_id
                         WHERE d.organization_id = :org AND d.id = :id AND d.status <> 'DELETED'""")
                .param("org", organizationId).param("id", deviceId)
                .query((rs, n) -> new DeviceSpace(rs.getLong("id"), Pg.longOrNull(rs, "space_id"), rs.getString("path"))).optional();
    }

    /** 공간 경로(/1/4/9/) */
    public Optional<String> findSpacePath(long organizationId, long spaceId) {
        return jdbc.sql("SELECT path FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", spaceId).query(String.class).optional();
    }

    /** 스크립트 이름(시스템 주석 제목용). 없으면 빈 값 */
    public Optional<String> findScriptName(long organizationId, long scriptId) {
        return jdbc.sql("SELECT name FROM data2flow_core.scripts WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", scriptId).query(String.class).optional();
    }

    private static Map<String, Object> params(AnnotationSearch s) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", s.organizationId());
        p.put("from", Pg.ts(s.from()));
        p.put("to", Pg.ts(s.to()));
        p.put("types", s.types() == null ? null : Pg.textArray(s.types()));
        p.put("metric", s.metricKey());
        p.put("mode", s.mode());
        p.put("device", s.deviceId());
        p.put("ancestors", Pg.bigintArray(s.ancestorSpaceIds()));
        p.put("prefix", s.subtreePath() == null ? "" : s.subtreePath() + "%");
        p.put("unrestricted", s.unrestricted());
        p.put("allowed", Pg.bigintArray(s.allowedSpaceIds()));
        return p;
    }

    private static AnnotationRow map(ResultSet rs, int n) throws SQLException {
        return new AnnotationRow(rs.getLong("id"), rs.getLong("organization_id"), Pg.instant(rs, "time_from"), Pg.instant(rs, "time_to"),
                Pg.longOrNull(rs, "device_id"), Pg.longOrNull(rs, "space_id"), rs.getString("metric_key"), rs.getString("type"),
                rs.getString("title"), rs.getString("ref"), Pg.longOrNull(rs, "created_by"), Pg.instant(rs, "created_at"),
                Pg.longOrNull(rs, "device_space_id"));
    }

    /**
     * 검색 조건(조직 조건 포함).
     *
     * @param mode            ALL(대상 없음), DEVICE(기기 + 그 공간과 조상 공간·조직 전체 주석), SPACE(공간과 조상·하위 공간 주석, 하위 공간 기기 주석, 조직 전체)
     * @param subtreePath     SPACE일 때 공간 경로(하위 공간 찾기)
     * @param types           저장 종류 목록. null이면 전체
     * @param allowedSpaceIds 공간 범위(펼친 집합). unrestricted면 무시
     */
    public record AnnotationSearch(long organizationId, Instant from, Instant to, String mode, Long deviceId, List<Long> ancestorSpaceIds,
                                   String subtreePath, List<String> types, String metricKey, boolean unrestricted,
                                   Set<Long> allowedSpaceIds) {
    }

    public record AnnotationRow(long id, long organizationId, Instant timeFrom, Instant timeTo, Long deviceId, Long spaceId,
                                String metricKey, String type, String title, String ref, Long createdBy, Instant createdAt,
                                Long deviceSpaceId) {
        /** 공간 범위 판정에 쓰는 공간: 기기 주석은 기기의 현재 공간 */
        public Long effectiveSpaceId() {
            return deviceId != null ? deviceSpaceId : spaceId;
        }
    }

    public record DeviceSpace(long id, Long spaceId, String path) {
    }
}
