package net.java21.data2flow.core.commissioning.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.commissioning.domain.CommissioningRules.Observed;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** QR 토큰({@code devices.qr_token})과 현장 설치({@code device_commissionings}), 설치 현황판 집계(DEV-09.04·13.05·13.06) */
@Repository
public class CommissioningRepository {

    private static final String COLUMNS = """
            c.organization_id, c.device_id, c.status, c.planned_space_id, c.installed_by, u.name AS installed_by_name, c.installed_at,
            c.photo_refs, c.x, c.y, c.first_seen_at, c.client_op_id, c.checklist::text AS checklist, c.checked_at, c.updated_at""";
    private static final String FROM = " " + """
             FROM data2flow_core.device_commissionings c
             LEFT JOIN data2flow_core.app_users u ON u.id = c.installed_by AND u.organization_id = c.organization_id""";

    private final JdbcClient jdbc;

    public CommissioningRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- QR 토큰

    public Optional<String> findQrToken(long organizationId, long deviceId) {
        return jdbc.sql("SELECT qr_token FROM data2flow_core.devices WHERE organization_id = :org AND id = :id AND qr_token IS NOT NULL")
                .param("org", organizationId).param("id", deviceId).query(String.class).optional();
    }

    public int updateQrToken(long organizationId, long deviceId, String token) {
        return jdbc.sql("UPDATE data2flow_core.devices SET qr_token = :t WHERE organization_id = :org AND id = :id AND status <> 'DELETED'")
                .param("t", token).param("org", organizationId).param("id", deviceId).update();
    }

    /** 토큰의 기기(삭제 제외) */
    public Optional<Long> findDeviceByQrToken(long organizationId, String token) {
        return jdbc.sql("SELECT id FROM data2flow_core.devices WHERE organization_id = :org AND qr_token = :t AND status <> 'DELETED'")
                .param("org", organizationId).param("t", token).query(Long.class).optional();
    }

    // ---- 현장 설치

    public Optional<Commissioning> lock(long organizationId, long deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + " WHERE c.organization_id = :org AND c.device_id = :d FOR UPDATE OF c")
                .param("org", organizationId).param("d", deviceId).query(CommissioningRepository::map).optional();
    }

    public Optional<Commissioning> find(long organizationId, long deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + " WHERE c.organization_id = :org AND c.device_id = :d")
                .param("org", organizationId).param("d", deviceId).query(CommissioningRepository::map).optional();
    }

    /** 같은 클라이언트 작업 ID(어느 기기든) */
    public Optional<Commissioning> findByClientOp(long organizationId, UUID clientOpId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + " WHERE c.organization_id = :org AND c.client_op_id = :op")
                .param("org", organizationId).param("op", clientOpId).query(CommissioningRepository::map).optional();
    }

    public void upsertInstalled(long organizationId, long deviceId, long spaceId, long installedBy, Instant installedAt, List<String> photoRefs,
                                BigDecimal x, BigDecimal y, UUID clientOpId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.device_commissionings (organization_id, device_id, status, planned_space_id, installed_by,
                               installed_at, photo_refs, x, y, first_seen_at, client_op_id, checklist, checked_at, created_at, updated_at)
                        VALUES (:org, :d, 'INSTALLED', :space, :by, :at, CAST(:photos AS text[]), :x, :y, NULL, :op, NULL, NULL, :now, :now)
                        ON CONFLICT (device_id) DO UPDATE
                           SET status = 'INSTALLED', planned_space_id = EXCLUDED.planned_space_id, installed_by = EXCLUDED.installed_by,
                               installed_at = EXCLUDED.installed_at, photo_refs = EXCLUDED.photo_refs, x = EXCLUDED.x, y = EXCLUDED.y,
                               first_seen_at = NULL, client_op_id = EXCLUDED.client_op_id, checklist = NULL, checked_at = NULL,
                               updated_at = EXCLUDED.updated_at
                         WHERE data2flow_core.device_commissionings.organization_id = EXCLUDED.organization_id""")
                .param("org", organizationId).param("d", deviceId).param("space", spaceId).param("by", installedBy)
                .param("at", Pg.ts(installedAt)).param("photos", Pg.textArray(photoRefs)).param("x", x).param("y", y).param("op", clientOpId)
                .param("now", Pg.ts(now)).update();
    }

    public int updateStatus(long organizationId, long deviceId, String status, Instant firstSeenAt, String checklistJson, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.device_commissionings
                           SET status = :status, first_seen_at = coalesce(:first, first_seen_at), checklist = CAST(:checklist AS jsonb),
                               checked_at = :now, updated_at = :now
                         WHERE organization_id = :org AND device_id = :d""")
                .param("status", status).param("first", Pg.ts(firstSeenAt)).param("checklist", checklistJson).param("now", Pg.ts(now))
                .param("org", organizationId).param("d", deviceId).update();
    }

    /** 확인을 기다리는 설치(INSTALLED·PROBLEM) — 정기 점검이 이 배포의 조직을 모두 본다 */
    @OrganizationScopeExempt("1분 설치 확인 작업이 이 배포의 조직(제한이 있으면 그 조직)을 모두 본다")
    public List<Long[]> listAwaiting(OptionalLong onlyOrganization) {
        return jdbc.sql("""
                        SELECT organization_id, device_id FROM data2flow_core.device_commissionings
                         WHERE status IN ('INSTALLED', 'PROBLEM') AND (CAST(:org AS bigint) IS NULL OR organization_id = :org)
                         ORDER BY organization_id, device_id""")
                .param("org", onlyOrganization.isPresent() ? onlyOrganization.getAsLong() : null)
                .query((rs, n) -> new Long[]{rs.getLong(1), rs.getLong(2)}).list();
    }

    /** 첫 수신·점검 체크리스트 판정용 관측값(pipeline device_state, 게이트웨이, 소스 상태) */
    public Optional<Observed> observe(long organizationId, long deviceId, Instant now) {
        return jdbc.sql("""
                        SELECT st.last_seen_at, st.battery, st.rssi, st.snr, st.best_gateway_eui,
                               CASE WHEN g.id IS NULL THEN NULL
                                    ELSE g.last_seen_at IS NOT NULL AND g.last_seen_at >= CAST(:now AS timestamptz) - make_interval(secs => g.offline_after_sec)
                               END AS gateway_online,
                               s.lifecycle, ss.connection_state
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_pipeline.device_state st ON st.device_id = d.id AND st.organization_id = d.organization_id
                          LEFT JOIN data2flow_core.gateways g ON g.organization_id = d.organization_id AND g.source_id = d.source_id
                                                             AND g.gateway_eui = st.best_gateway_eui
                          LEFT JOIN data2flow_core.data_sources s ON s.id = d.source_id AND s.organization_id = d.organization_id
                          LEFT JOIN data2flow_core.source_states ss ON ss.source_id = d.source_id AND ss.organization_id = d.organization_id
                         WHERE d.organization_id = :org AND d.id = :d""")
                .param("org", organizationId).param("d", deviceId).param("now", Pg.ts(now))
                .query((rs, n) -> new Observed(Pg.instant(rs, "last_seen_at"), rs.getBigDecimal("battery"), rs.getBigDecimal("rssi"),
                        rs.getBigDecimal("snr"), rs.getString("best_gateway_eui"), (Boolean) rs.getObject("gateway_online"),
                        rs.getString("lifecycle"), rs.getString("connection_state")))
                .optional();
    }

    /** 기기 최근값 JSON({metricKey:{v,t,q}}) */
    public Optional<String> findLatest(long organizationId, long deviceId) {
        return jdbc.sql("SELECT latest::text FROM data2flow_pipeline.device_state WHERE organization_id = :org AND device_id = :d")
                .param("org", organizationId).param("d", deviceId).query(String.class).optional();
    }

    // ---- 현황판(DEV-13.06)

    /** 사이트(또는 전체) 아래 FLOOR 공간 */
    public List<FloorRef> listFloors(long organizationId, Long siteId, Set<Long> allowedSpaceIds) {
        return jdbc.sql("""
                        SELECT f.id, f.name, f.path, f.depth FROM data2flow_core.spaces f
                          LEFT JOIN data2flow_core.spaces site ON site.id = :site AND site.organization_id = f.organization_id
                         WHERE f.organization_id = :org AND f.type = 'FLOOR'
                           AND (CAST(:site AS bigint) IS NULL OR f.path LIKE site.path || '%')
                           AND (CAST(:allowed AS bigint[]) IS NULL OR f.id = ANY(CAST(:allowed AS bigint[])))
                         ORDER BY f.path""")
                .param("org", organizationId).param("site", siteId)
                .param("allowed", allowedSpaceIds == null ? null : Pg.bigintArray(allowedSpaceIds))
                .query((rs, n) -> new FloorRef(rs.getLong("id"), rs.getString("name"), rs.getString("path"))).list();
    }

    /**
     * 공간(하위 포함)에 놓였거나 놓일 기기의 설치 상태. 설치 기록이 없으면 PLANNED. 공간은 설치 기록의 설치 공간, 없으면 기기 공간.
     * status가 있으면 그 상태만
     */
    public List<BoardDevice> listBoardDevices(long organizationId, String spacePath, String status, Set<Long> allowedSpaceIds) {
        return jdbc.sql("""
                        SELECT d.id, d.name, coalesce(c.planned_space_id, d.space_id) AS space_id, coalesce(c.status, 'PLANNED') AS status,
                               c.installed_at, c.checklist::text AS checklist, c.x IS NOT NULL AS positioned,
                               coalesce(cardinality(c.photo_refs), 0) > 0 AS photographed, c.first_seen_at
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.device_commissionings c ON c.device_id = d.id AND c.organization_id = d.organization_id
                          JOIN data2flow_core.spaces s ON s.id = coalesce(c.planned_space_id, d.space_id) AND s.organization_id = d.organization_id
                         WHERE d.organization_id = :org AND d.status <> 'DELETED' AND NOT d.is_virtual AND s.path LIKE :path || '%'
                           AND (CAST(:status AS text) IS NULL OR coalesce(c.status, 'PLANNED') = :status)
                           AND (CAST(:allowed AS bigint[]) IS NULL OR s.id = ANY(CAST(:allowed AS bigint[])))
                         ORDER BY d.name, d.id""")
                .param("org", organizationId).param("path", spacePath).param("status", status)
                .param("allowed", allowedSpaceIds == null ? null : Pg.bigintArray(allowedSpaceIds))
                .query((rs, n) -> new BoardDevice(rs.getLong("id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"), rs.getString("status"),
                        Pg.instant(rs, "installed_at"), rs.getString("checklist"), rs.getBoolean("positioned"), rs.getBoolean("photographed"),
                        Pg.instant(rs, "first_seen_at")))
                .list();
    }

    public Optional<String> findSpacePath(long organizationId, long spaceId) {
        return jdbc.sql("SELECT path FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", spaceId).query(String.class).optional();
    }

    /** 공간 ID → 그 공간이 속한 FLOOR(자기 자신 포함) — SSE 갱신 대상 판정 */
    public Optional<Long> findDeviceSpace(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT coalesce(c.planned_space_id, d.space_id) FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.device_commissionings c ON c.device_id = d.id AND c.organization_id = d.organization_id
                         WHERE d.organization_id = :org AND d.id = :d""")
                .param("org", organizationId).param("d", deviceId).query(Long.class).optional();
    }

    public List<Long> findPathIds(long organizationId, Collection<Long> spaceIds) {
        return spaceIds.isEmpty() ? List.of() : jdbc.sql("SELECT id FROM data2flow_core.spaces WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(spaceIds)).query(Long.class).list();
    }

    static Commissioning map(ResultSet rs, int n) throws SQLException {
        return new Commissioning(rs.getLong("organization_id"), rs.getLong("device_id"), rs.getString("status"),
                Pg.longOrNull(rs, "planned_space_id"), Pg.longOrNull(rs, "installed_by"), rs.getString("installed_by_name"),
                Pg.instant(rs, "installed_at"), Pg.stringList(rs, "photo_refs"), rs.getBigDecimal("x"), rs.getBigDecimal("y"),
                Pg.instant(rs, "first_seen_at"), rs.getObject("client_op_id", UUID.class), rs.getString("checklist"), Pg.instant(rs, "checked_at"),
                Pg.instant(rs, "updated_at"));
    }

    public record Commissioning(long organizationId, long deviceId, String status, Long spaceId, Long installedBy, String installedByName,
                                Instant installedAt, List<String> photoRefs, BigDecimal x, BigDecimal y, Instant firstSeenAt, UUID clientOpId,
                                String checklistJson, Instant checkedAt, Instant updatedAt) {
    }

    public record FloorRef(long id, String name, String path) {
    }

    public record BoardDevice(long deviceId, String name, Long spaceId, String status, Instant installedAt, String checklistJson,
                              boolean positioned, boolean photographed, Instant firstSeenAt) {
    }
}
