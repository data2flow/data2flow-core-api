package net.java21.data2flow.core.compliance.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Aggregate;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Excursion;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 준수 리포트와 판(CMP-02.02·02.06, {@code compliance_reports}·{@code compliance_report_revisions})과 리포트 수치 조회. 수치는 일 집계와 이탈
 * 구간에서만 읽는다(BR-CMP-12). 행 수는 1분 집계에서 실제 기기 행만 센다.
 */
@Repository
public class ComplianceReportRepository {

    private static final String REPORT = """
            SELECT r.id, r.organization_id, r.type, r.title, r.space_ids, r.facility_type, r.period_from, r.period_to, CAST(r.options AS text) AS options,
                   r.dedup_key, r.created_by, r.created_at, r.updated_at
              FROM data2flow_core.compliance_reports r
            """;
    private static final String REVISION = """
            SELECT v.id, v.organization_id, v.report_id, v.revision_no, v.status, v.progress, v.pdf_object_key, v.csv_object_key, v.sha256,
                   v.size_bytes, CAST(v.data_range AS text) AS data_range, v.profile_version_ids, v.generated_by, gu.name AS generated_by_name,
                   v.published_by, pu.name AS published_by_name, v.published_at, v.failure_reason, v.created_at, v.updated_at
              FROM data2flow_core.compliance_report_revisions v
              LEFT JOIN data2flow_core.app_users gu ON gu.id = v.generated_by
              LEFT JOIN data2flow_core.app_users pu ON pu.id = v.published_by
            """;

    private final JdbcClient jdbc;

    public ComplianceReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ReportRow(long id, long organizationId, String type, String title, List<Long> spaceIds, String facilityType, LocalDate periodFrom,
                            LocalDate periodTo, String optionsJson, String dedupKey, long createdBy, Instant createdAt, Instant updatedAt) {
    }

    public record RevisionRow(long id, long organizationId, long reportId, int revisionNo, String status, int progress, String pdfObjectKey,
                              String csvObjectKey, String sha256, Long sizeBytes, String dataRangeJson, List<Long> profileVersionIds,
                              Long generatedBy, String generatedByName, Long publishedBy, String publishedByName, Instant publishedAt,
                              String failureReason, Instant createdAt, Instant updatedAt) {
    }

    /** 생성 작업이 잡은 판 */
    public record Claimed(long organizationId, long reportId, long revisionId, int revisionNo) {
    }

    public record Expired(long organizationId, long reportId, long revisionId, String pdfObjectKey, String csvObjectKey) {
    }

    // ---- 리포트·판

    public Optional<ReportRow> findByDedup(long orgId, String dedupKey) {
        return jdbc.sql(REPORT + " WHERE r.organization_id = :org AND r.dedup_key = :key FOR UPDATE")
                .param("org", orgId).param("key", dedupKey).query(ComplianceReportRepository::report).optional();
    }

    public Optional<ReportRow> find(long orgId, long id) {
        return jdbc.sql(REPORT + " WHERE r.organization_id = :org AND r.id = :id").param("org", orgId).param("id", id)
                .query(ComplianceReportRepository::report).optional();
    }

    /** 목록(최근 수정 순). type·spaceId·기간(겹침)으로 거른다. status는 마지막 판 상태로 거른다 */
    public List<ReportRow> list(long orgId, String type, String status, Long spaceId, LocalDate from, LocalDate to, int limit, long offset) {
        return jdbc.sql(REPORT + """
                         WHERE r.organization_id = :org
                           AND (CAST(:type AS varchar) IS NULL OR r.type = :type)
                           AND (CAST(:space AS bigint) IS NULL OR :space = ANY(r.space_ids))
                           AND (CAST(:from AS date) IS NULL OR r.period_to >= :from)
                           AND (CAST(:to AS date) IS NULL OR r.period_from <= :to)
                           AND (CAST(:status AS varchar) IS NULL OR :status = (SELECT l.status FROM data2flow_core.compliance_report_revisions l
                                                                                WHERE l.report_id = r.id ORDER BY l.revision_no DESC LIMIT 1))
                         ORDER BY r.updated_at DESC, r.id DESC LIMIT :limit OFFSET :offset""")
                .param("org", orgId).param("type", type).param("space", spaceId).param("from", from).param("to", to).param("status", status)
                .param("limit", limit).param("offset", offset).query(ComplianceReportRepository::report).list();
    }

    public long insertReport(long orgId, String type, String title, Collection<Long> spaceIds, String facilityType, LocalDate from, LocalDate to,
                             String optionsJson, String dedupKey, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.compliance_reports (organization_id, type, title, space_ids, facility_type, period_from, period_to,
                               options, dedup_key, created_by, created_at, updated_at)
                        VALUES (:org, :type, :title, CAST(:spaces AS bigint[]), :facility, :from, :to, CAST(:options AS jsonb), :key, :by, :now, :now)
                        ON CONFLICT ON CONSTRAINT uq_compliance_reports_organization_id_dedup_key DO NOTHING
                        RETURNING id""")
                .param("org", orgId).param("type", type).param("title", title).param("spaces", Pg.bigintArray(spaceIds))
                .param("facility", facilityType).param("from", from).param("to", to).param("options", optionsJson).param("key", dedupKey)
                .param("by", userId).param("now", Pg.ts(now)).query(Long.class).optional().orElse(-1L);
    }

    /** 같은 조건으로 다시 만들 때 제목·옵션을 새 요청 값으로 */
    public void touchReport(long orgId, long id, String title, String optionsJson, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.compliance_reports SET title = :title, options = CAST(:options AS jsonb), updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("org", orgId).param("id", id).param("title", title).param("options", optionsJson).param("now", Pg.ts(now)).update();
    }

    /** 새 판(GENERATING). 판 번호는 그 리포트의 마지막 번호 + 1(리포트 행을 잠근 트랜잭션 안에서 부른다) */
    public RevisionRow insertRevision(long orgId, long reportId, Long userId, Instant now) {
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.compliance_report_revisions (organization_id, report_id, revision_no, generated_by, created_at,
                               updated_at)
                        SELECT :org, :report, coalesce(max(revision_no), 0) + 1, :by, :now, :now
                          FROM data2flow_core.compliance_report_revisions WHERE report_id = :report
                        RETURNING id""")
                .param("org", orgId).param("report", reportId).param("by", userId).param("now", Pg.ts(now)).query(Long.class).single();
        return revisionById(orgId, id).orElseThrow();
    }

    public Optional<RevisionRow> revisionById(long orgId, long id) {
        return jdbc.sql(REVISION + " WHERE v.organization_id = :org AND v.id = :id").param("org", orgId).param("id", id)
                .query(ComplianceReportRepository::revision).optional();
    }

    public Optional<RevisionRow> revision(long orgId, long reportId, int revisionNo) {
        return jdbc.sql(REVISION + " WHERE v.organization_id = :org AND v.report_id = :report AND v.revision_no = :no")
                .param("org", orgId).param("report", reportId).param("no", revisionNo).query(ComplianceReportRepository::revision).optional();
    }

    /** 판 목록(번호 내림차순) */
    public List<RevisionRow> revisions(long orgId, Collection<Long> reportIds) {
        if (reportIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(REVISION + " WHERE v.organization_id = :org AND v.report_id = ANY(CAST(:ids AS bigint[])) ORDER BY v.report_id, v.revision_no DESC")
                .param("org", orgId).param("ids", Pg.bigintArray(reportIds)).query(ComplianceReportRepository::revision).list();
    }

    /** 같은 리포트에서 이 판 앞의 마지막 완료 판(DRAFT·PUBLISHED)의 행 수(BR-CMP-12 늦게 온 데이터) */
    public Optional<Long> previousRows(long orgId, long reportId, int revisionNo) {
        return jdbc.sql("""
                        SELECT CAST(data_range ->> 'rows' AS bigint) FROM data2flow_core.compliance_report_revisions
                         WHERE organization_id = :org AND report_id = :report AND revision_no < :no AND status IN ('DRAFT', 'PUBLISHED')
                           AND data_range IS NOT NULL
                         ORDER BY revision_no DESC LIMIT 1""")
                .param("org", orgId).param("report", reportId).param("no", revisionNo).query(Long.class).optional();
    }

    /** 생성할 판 하나를 잡는다(여러 파드 중 하나만, SKIP LOCKED). staleBefore보다 먼저 잡혀 멈춘 판도 다시 잡는다 */
    @OrganizationScopeExempt("비동기 생성기: 모든 조직의 생성 대기 판을 순서대로 처리")
    public Optional<Claimed> claimNext(Instant now, Instant staleBefore) {
        return jdbc.sql("""
                        UPDATE data2flow_core.compliance_report_revisions v SET claimed_at = :now, attempts = attempts + 1, progress = 10,
                               updated_at = :now
                         WHERE v.id = (SELECT id FROM data2flow_core.compliance_report_revisions
                                        WHERE status = 'GENERATING' AND (claimed_at IS NULL OR claimed_at < :stale)
                                        ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
                        RETURNING v.organization_id, v.report_id, v.id, v.revision_no""")
                .param("now", Pg.ts(now)).param("stale", Pg.ts(staleBefore))
                .query((rs, n) -> new Claimed(rs.getLong("organization_id"), rs.getLong("report_id"), rs.getLong("id"), rs.getInt("revision_no")))
                .optional();
    }

    public void progress(long orgId, long revisionId, int progress, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.compliance_report_revisions SET progress = :p, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'GENERATING'""")
                .param("org", orgId).param("id", revisionId).param("p", progress).param("now", Pg.ts(now)).update();
    }

    /** GENERATING → DRAFT. 이미 다른 상태면 false */
    public boolean markDraft(long orgId, long revisionId, String pdfKey, String sha256, long size, String dataRangeJson,
                             Collection<Long> versionIds, String modelJson, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.compliance_report_revisions
                           SET status = 'DRAFT', progress = 100, pdf_object_key = :pdf, sha256 = :sha, size_bytes = :size,
                               data_range = CAST(:range AS jsonb), profile_version_ids = CAST(:versions AS bigint[]), model = CAST(:model AS jsonb),
                               failure_reason = NULL, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'GENERATING'""")
                .param("org", orgId).param("id", revisionId).param("pdf", pdfKey).param("sha", sha256).param("size", size)
                .param("range", dataRangeJson).param("versions", Pg.bigintArray(versionIds)).param("model", modelJson).param("now", Pg.ts(now))
                .update() == 1;
    }

    public boolean markFailed(long orgId, long revisionId, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.compliance_report_revisions SET status = 'FAILED', failure_reason = :reason, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'GENERATING'""")
                .param("org", orgId).param("id", revisionId).param("reason", reason.length() > 300 ? reason.substring(0, 300) : reason)
                .param("now", Pg.ts(now)).update() == 1;
    }

    /** 판의 저장된 모델(JSON) */
    public Optional<String> model(long orgId, long revisionId) {
        return jdbc.sql("SELECT CAST(model AS text) FROM data2flow_core.compliance_report_revisions WHERE organization_id = :org AND id = :id")
                .param("org", orgId).param("id", revisionId).query(String.class).optional();
    }

    /** DRAFT → PUBLISHED(발행본 파일로 바꾼다). 이미 다른 상태면 false */
    public boolean publish(long orgId, long revisionId, String pdfKey, String sha256, long size, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.compliance_report_revisions
                           SET status = 'PUBLISHED', pdf_object_key = :pdf, sha256 = :sha, size_bytes = :size, published_by = :by,
                               published_at = :now, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'DRAFT'""")
                .param("org", orgId).param("id", revisionId).param("pdf", pdfKey).param("sha", sha256).param("size", size).param("by", userId)
                .param("now", Pg.ts(now)).update() == 1;
    }

    /** DRAFT·FAILED 판 지우기. 지웠으면 true */
    public boolean deleteRevision(long orgId, long revisionId) {
        return jdbc.sql("""
                        DELETE FROM data2flow_core.compliance_report_revisions
                         WHERE organization_id = :org AND id = :id AND status IN ('DRAFT', 'FAILED')""")
                .param("org", orgId).param("id", revisionId).update() == 1;
    }

    public void touchReportUpdated(long orgId, long reportId, Instant now) {
        jdbc.sql("UPDATE data2flow_core.compliance_reports SET updated_at = :now WHERE organization_id = :org AND id = :id")
                .param("org", orgId).param("id", reportId).param("now", Pg.ts(now)).update();
    }

    /** 만든 지 오래된 DRAFT(7일, domain-model §3) */
    @OrganizationScopeExempt("정리 작업: 모든 조직의 오래된 초안")
    public List<Expired> expiredDrafts(Instant before) {
        return jdbc.sql("""
                        SELECT organization_id, report_id, id, pdf_object_key, csv_object_key FROM data2flow_core.compliance_report_revisions
                         WHERE status = 'DRAFT' AND updated_at < :before ORDER BY id LIMIT 200""")
                .param("before", Pg.ts(before))
                .query((rs, n) -> new Expired(rs.getLong("organization_id"), rs.getLong("report_id"), rs.getLong("id"), rs.getString("pdf_object_key"),
                        rs.getString("csv_object_key"))).list();
    }

    /** 해시가 같은 판(해시 검증 API-CMP-20) */
    public Optional<RevisionRow> findBySha(long orgId, String sha256) {
        return jdbc.sql(REVISION + " WHERE v.organization_id = :org AND v.sha256 = :sha ORDER BY v.id DESC LIMIT 1")
                .param("org", orgId).param("sha", sha256).query(ComplianceReportRepository::revision).optional();
    }

    // ---- 리포트 수치

    /** 일 집계를 (공간, 버전, 항목)으로 더한다. 프로필이 profileIds에 드는 버전만 */
    public List<Aggregate> aggregates(long orgId, Collection<Long> spaceIds, Collection<Long> profileIds, LocalDate from, LocalDate to) {
        if (spaceIds.isEmpty() || profileIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT c.space_id, c.profile_version_id, c.metric_key, sum(c.met_minutes) AS met, sum(c.exceeded_minutes) AS exceeded,
                               sum(c.missing_minutes) AS missing, max(c.max_value) AS max_value,
                               sum(c.avg_value * (c.met_minutes + c.exceeded_minutes)) AS weighted_avg, count(*) AS days
                          FROM data2flow_core.compliance_daily c
                          JOIN data2flow_core.compliance_profile_versions v ON v.id = c.profile_version_id
                         WHERE c.organization_id = :org AND c.day BETWEEN :from AND :to AND c.space_id = ANY(CAST(:spaces AS bigint[]))
                           AND v.profile_id = ANY(CAST(:profiles AS bigint[]))
                         GROUP BY c.space_id, c.profile_version_id, c.metric_key
                         ORDER BY c.space_id, c.profile_version_id, c.metric_key""")
                .param("org", orgId).param("from", from).param("to", to).param("spaces", Pg.bigintArray(spaceIds))
                .param("profiles", Pg.bigintArray(profileIds))
                .query((rs, n) -> new Aggregate(rs.getLong("space_id"), rs.getLong("profile_version_id"), rs.getString("metric_key"),
                        rs.getLong("met"), rs.getLong("exceeded"), rs.getLong("missing"), rs.getBigDecimal("max_value"),
                        rs.getBigDecimal("weighted_avg"), rs.getInt("days"))).list();
    }

    /** 기간과 겹치는 이탈 구간(버전이 versionIds에 드는 것) */
    public List<Excursion> excursions(long orgId, Collection<Long> spaceIds, Collection<Long> versionIds, Instant from, Instant to) {
        if (spaceIds.isEmpty() || versionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT space_id, profile_version_id, metric_key, started_at, ended_at, peak_value FROM data2flow_core.compliance_excursions
                         WHERE organization_id = :org AND space_id = ANY(CAST(:spaces AS bigint[]))
                           AND profile_version_id = ANY(CAST(:versions AS bigint[])) AND started_at < :to AND (ended_at IS NULL OR ended_at > :from)
                         ORDER BY space_id, metric_key, started_at""")
                .param("org", orgId).param("spaces", Pg.bigintArray(spaceIds)).param("versions", Pg.bigintArray(versionIds))
                .param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> new Excursion(rs.getLong("space_id"), rs.getLong("profile_version_id"), rs.getString("metric_key"),
                        Pg.instant(rs, "started_at"), Pg.instant(rs, "ended_at"), rs.getBigDecimal("peak_value"))).list();
    }

    /** 기간의 1분 집계 행 수(실제 기기만) */
    public long telemetryRows(long orgId, Collection<Long> spaceIds, Instant from, Instant to) {
        if (spaceIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_pipeline.telemetry_1m t
                          JOIN data2flow_core.devices d ON d.id = t.device_id
                         WHERE t.organization_id = :org AND d.organization_id = :org AND d.space_id = ANY(CAST(:spaces AS bigint[]))
                           AND NOT d.is_virtual AND NOT t.is_virtual AND t.bucket >= :from AND t.bucket < :to""")
                .param("org", orgId).param("spaces", Pg.bigintArray(spaceIds)).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query(Long.class).single();
    }

    /** 공간들에 놓인 기기 수: [ACTIVE 실제, 가상] */
    public int[] deviceCounts(long orgId, Collection<Long> spaceIds) {
        if (spaceIds.isEmpty()) {
            return new int[]{0, 0};
        }
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE NOT is_virtual AND status = 'ACTIVE') AS real_count, count(*) FILTER (WHERE is_virtual) AS virtual_count
                          FROM data2flow_core.devices WHERE organization_id = :org AND space_id = ANY(CAST(:spaces AS bigint[]))""")
                .param("org", orgId).param("spaces", Pg.bigintArray(spaceIds))
                .query((rs, n) -> new int[]{rs.getInt("real_count"), rs.getInt("virtual_count")}).single();
    }

    /** 기기 모델 이름과 마지막 교정일(완료한 CALIBRATION 작업 지시의 완료일, 조직 시간대) */
    public List<DeviceCalibration> calibrations(long orgId, Collection<Long> deviceIds, String zone) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT d.id, m.name AS model_name,
                               (SELECT CAST(max(w.completed_at AT TIME ZONE :zone) AS date)
                                  FROM data2flow_core.work_order_targets t
                                  JOIN data2flow_core.work_orders w ON w.id = t.work_order_id
                                 WHERE t.organization_id = :org AND t.device_id = d.id AND w.type = 'CALIBRATION' AND w.status = 'DONE') AS last_calibrated
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id
                         WHERE d.organization_id = :org AND d.id = ANY(CAST(:ids AS bigint[]))""")
                .param("org", orgId).param("ids", Pg.bigintArray(deviceIds)).param("zone", zone)
                .query((rs, n) -> new DeviceCalibration(rs.getLong("id"), rs.getString("model_name"), rs.getObject("last_calibrated", LocalDate.class)))
                .list();
    }

    public record DeviceCalibration(long deviceId, String model, LocalDate lastCalibratedOn) {
    }

    /** 프로필 버전 정보(이 조직 또는 시스템 프로필) */
    public record VersionInfo(long versionId, long profileId, String profileName, int versionNo, String sourceTitle, LocalDate effectiveDate) {
    }

    public List<VersionInfo> versions(long orgId, Collection<Long> versionIds) {
        if (versionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT v.id, v.profile_id, p.name, v.version_no, v.source_title, v.effective_date
                          FROM data2flow_core.compliance_profile_versions v
                          JOIN data2flow_core.compliance_profiles p ON p.id = v.profile_id
                         WHERE p.organization_id IN (:org, 0) AND v.id = ANY(CAST(:ids AS bigint[]))
                         ORDER BY v.id""")
                .param("org", orgId).param("ids", Pg.bigintArray(versionIds))
                .query((rs, n) -> new VersionInfo(rs.getLong("id"), rs.getLong("profile_id"), rs.getString("name"), rs.getInt("version_no"),
                        rs.getString("source_title"), rs.getObject("effective_date", LocalDate.class))).list();
    }

    public Optional<String> userName(long orgId, long userId) {
        return jdbc.sql("SELECT name FROM data2flow_core.app_users WHERE organization_id = :org AND id = :id").param("org", orgId)
                .param("id", userId).query(String.class).optional();
    }

    /** 판이 하나도 남지 않은 리포트를 지운다 */
    public void deleteIfEmpty(long orgId, long reportId) {
        jdbc.sql("""
                        DELETE FROM data2flow_core.compliance_reports r WHERE r.organization_id = :org AND r.id = :id
                           AND NOT EXISTS (SELECT 1 FROM data2flow_core.compliance_report_revisions v WHERE v.report_id = r.id)""")
                .param("org", orgId).param("id", reportId).update();
    }

    private static ReportRow report(ResultSet rs, int n) throws SQLException {
        return new ReportRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("type"), rs.getString("title"), Pg.longList(rs, "space_ids"),
                rs.getString("facility_type"), rs.getObject("period_from", LocalDate.class), rs.getObject("period_to", LocalDate.class),
                rs.getString("options"), rs.getString("dedup_key"), rs.getLong("created_by"), Pg.instant(rs, "created_at"),
                Pg.instant(rs, "updated_at"));
    }

    private static RevisionRow revision(ResultSet rs, int n) throws SQLException {
        long size = rs.getLong("size_bytes");
        Long sizeBytes = rs.wasNull() ? null : size;
        return new RevisionRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("report_id"), rs.getInt("revision_no"),
                rs.getString("status"), rs.getInt("progress"), rs.getString("pdf_object_key"), rs.getString("csv_object_key"), rs.getString("sha256"),
                sizeBytes, rs.getString("data_range"), Pg.longList(rs, "profile_version_ids"), Pg.longOrNull(rs, "generated_by"),
                rs.getString("generated_by_name"), Pg.longOrNull(rs, "published_by"), rs.getString("published_by_name"),
                Pg.instant(rs, "published_at"), rs.getString("failure_reason"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }
}
