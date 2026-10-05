package net.java21.data2flow.core.analytics.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 분석 정의 색인({@code data2flow_core.analysis_refs}). 원천은 analytics({@code data2flow_analytics.analyses})이고, core는 저장을 중계할 때
 * 같은 트랜잭션에서 이 색인을 고친다. 공간 범위 판정(BR-ANA-03)·목록(API-ANA-07)·일정 실행(ANA-04.01)·고정 위젯(DSH-04.04)이 읽는다.
 */
@Repository
public class AnalysisRefRepository {

    private static final String SELECT = """
            SELECT r.id, r.organization_id, r.analysis_id, r.name, r.template_key, r.template_version, r.owner_user_id, r.space_scope_ids,
                   r.target_summary, r.schedule::text AS schedule, r.schedule_state, r.next_run_at, r.last_scheduled_at, r.realtime,
                   r.last_run_id, r.last_run_status, r.last_run_finished_at, r.last_succeeded_run_id, r.last_succeeded_at, r.status,
                   r.created_at, r.updated_at, u.name AS owner_name
              FROM data2flow_core.analysis_refs r
              LEFT JOIN data2flow_core.app_users u ON u.organization_id = r.organization_id AND u.id = r.owner_user_id""";

    private final JdbcClient jdbc;

    public AnalysisRefRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record AnalysisRef(long id, long organizationId, long analysisId, String name, String templateKey, String templateVersion,
                              long ownerUserId, List<Long> spaceScopeIds, String targetSummary, String scheduleJson, String scheduleState,
                              Instant nextRunAt, Instant lastScheduledAt, boolean realtime, Long lastRunId, String lastRunStatus,
                              Instant lastRunFinishedAt, Long lastSucceededRunId, Instant lastSucceededAt, String status, Instant createdAt,
                              Instant updatedAt, String ownerName) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** 저장(만들기·수정) 때 쓰는 값 */
    public record Upsert(long organizationId, long analysisId, String name, String templateKey, String templateVersion, long ownerUserId,
                         List<Long> spaceScopeIds, String targetSummary, String scheduleJson, String scheduleState, Instant nextRunAt,
                         boolean realtime) {
    }

    /** 색인 넣기·고치기(analysis_id 기준). 실행 기록 열은 건드리지 않는다 */
    public void upsert(Upsert u, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.analysis_refs (organization_id, analysis_id, name, template_key, template_version, owner_user_id,
                               space_scope_ids, target_summary, schedule, schedule_state, next_run_at, realtime, status, created_at, updated_at)
                        VALUES (:org, :analysis, :name, :templateKey, :templateVersion, :owner, CAST(:spaces AS bigint[]), :summary,
                                CAST(:schedule AS jsonb), :scheduleState, :nextRunAt, :realtime, 'ACTIVE', :now, :now)
                        ON CONFLICT (organization_id, analysis_id) DO UPDATE SET
                               name = EXCLUDED.name, template_key = EXCLUDED.template_key, template_version = EXCLUDED.template_version,
                               owner_user_id = EXCLUDED.owner_user_id, space_scope_ids = EXCLUDED.space_scope_ids,
                               target_summary = EXCLUDED.target_summary, schedule = EXCLUDED.schedule,
                               schedule_state = EXCLUDED.schedule_state, next_run_at = EXCLUDED.next_run_at,
                               realtime = EXCLUDED.realtime, status = 'ACTIVE', updated_at = EXCLUDED.updated_at""")
                .param("org", u.organizationId()).param("analysis", u.analysisId()).param("name", u.name())
                .param("templateKey", u.templateKey()).param("templateVersion", u.templateVersion()).param("owner", u.ownerUserId())
                .param("spaces", Pg.bigintArray(u.spaceScopeIds())).param("summary", u.targetSummary())
                .param("schedule", u.scheduleJson()).param("scheduleState", u.scheduleState()).param("nextRunAt", Pg.ts(u.nextRunAt()))
                .param("realtime", u.realtime()).param("now", Pg.ts(now)).update();
    }

    public Optional<AnalysisRef> find(long organizationId, long analysisId) {
        return jdbc.sql(SELECT + " WHERE r.organization_id = :org AND r.analysis_id = :id")
                .param("org", organizationId).param("id", analysisId).query(AnalysisRefRepository::row).optional();
    }

    /** 목록 조건 */
    public record Filter(long organizationId, Long ownerUserId, String templateKey, String scheduleState, Boolean realtime, String keyword,
                         List<Long> allowedSpaceIds) {
    }

    /** 목록(API-ANA-07). allowedSpaceIds가 있으면 바인딩 공간이 모두 그 안인 분석만(BR-ANA-03) */
    public List<AnalysisRef> list(Filter f, int limit, long offset) {
        return bind(jdbc.sql(SELECT + where(f) + " ORDER BY r.updated_at DESC, r.id DESC LIMIT :limit OFFSET :offset"), f)
                .param("limit", limit).param("offset", offset).query(AnalysisRefRepository::row).list();
    }

    public long count(Filter f) {
        return bind(jdbc.sql("SELECT count(*) FROM data2flow_core.analysis_refs r" + where(f)), f).query(Long.class).single();
    }

    private static String where(Filter f) {
        StringBuilder sb = new StringBuilder(" WHERE r.organization_id = :org AND r.status = 'ACTIVE'");
        if (f.ownerUserId() != null) {
            sb.append(" AND r.owner_user_id = :owner");
        }
        if (f.templateKey() != null) {
            sb.append(" AND r.template_key = :templateKey");
        }
        if (f.scheduleState() != null) {
            sb.append(" AND r.schedule_state = :scheduleState");
        }
        if (f.realtime() != null) {
            sb.append(" AND r.realtime = :realtime");
        }
        if (f.keyword() != null) {
            sb.append(" AND r.name ILIKE :keyword");
        }
        if (f.allowedSpaceIds() != null) {
            sb.append(" AND r.space_scope_ids <@ CAST(:allowed AS bigint[])");
        }
        return sb.toString();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Filter f) {
        spec = spec.param("org", f.organizationId());
        if (f.ownerUserId() != null) {
            spec = spec.param("owner", f.ownerUserId());
        }
        if (f.templateKey() != null) {
            spec = spec.param("templateKey", f.templateKey());
        }
        if (f.scheduleState() != null) {
            spec = spec.param("scheduleState", f.scheduleState());
        }
        if (f.realtime() != null) {
            spec = spec.param("realtime", f.realtime());
        }
        if (f.keyword() != null) {
            spec = spec.param("keyword", "%" + f.keyword().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.allowedSpaceIds() != null) {
            spec = spec.param("allowed", Pg.bigintArray(f.allowedSpaceIds()));
        }
        return spec;
    }

    /** 삭제(API-ANA-22): ARCHIVED, 일정 끔, 실시간 끔 */
    public int archive(long organizationId, long analysisId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.analysis_refs SET status = 'ARCHIVED', schedule_state = NULL, next_run_at = NULL, realtime = false,
                               updated_at = :now
                         WHERE organization_id = :org AND analysis_id = :id AND status = 'ACTIVE'""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", analysisId).update();
    }

    public int updateRealtime(long organizationId, long analysisId, boolean realtime, Instant now) {
        return jdbc.sql("UPDATE data2flow_core.analysis_refs SET realtime = :rt, updated_at = :now WHERE organization_id = :org AND analysis_id = :id")
                .param("rt", realtime).param("now", Pg.ts(now)).param("org", organizationId).param("id", analysisId).update();
    }

    /**
     * 실행 상태 반영(실행 요청 응답·EVT-ANA-01). 더 오래된 실행의 늦은 이벤트가 최근 실행을 덮지 않도록 run ID가 같거나 큰 것만 바꾼다.
     * SUCCEEDED면 최근 성공 실행도 바꾼다(고정 위젯은 실패 실행을 무시, BR-DSH-11)
     */
    public int recordRun(long organizationId, long analysisId, long runId, String status, Instant finishedAt) {
        int n = jdbc.sql("""
                        UPDATE data2flow_core.analysis_refs SET last_run_id = :run, last_run_status = :status, last_run_finished_at = :finished
                         WHERE organization_id = :org AND analysis_id = :id AND (last_run_id IS NULL OR last_run_id <= :run)""")
                .param("run", runId).param("status", status).param("finished", Pg.ts(finishedAt))
                .param("org", organizationId).param("id", analysisId).update();
        if ("SUCCEEDED".equals(status)) {
            jdbc.sql("""
                            UPDATE data2flow_core.analysis_refs SET last_succeeded_run_id = :run, last_succeeded_at = :finished
                             WHERE organization_id = :org AND analysis_id = :id AND (last_succeeded_run_id IS NULL OR last_succeeded_run_id <= :run)""")
                    .param("run", runId).param("finished", Pg.ts(finishedAt)).param("org", organizationId).param("id", analysisId).update();
        }
        return n;
    }

    /** 일정 상태 바꾸기(EVT-ANA-05 연속 실패 중지, 템플릿 비활성화 BR-ANA-17) */
    public int updateScheduleState(long organizationId, long analysisId, String state, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.analysis_refs SET schedule_state = :state, updated_at = :now
                         WHERE organization_id = :org AND analysis_id = :id AND schedule IS NOT NULL""")
                .param("state", state).param("now", Pg.ts(now)).param("org", organizationId).param("id", analysisId).update();
    }

    /** 템플릿 비활성화(BR-ANA-17): 그 템플릿의 활성 일정 PAUSED·실시간 끔. 바뀐 분석 ID */
    public List<Long> pauseTemplate(long organizationId, String templateKey, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.analysis_refs
                           SET schedule_state = CASE WHEN schedule_state = 'ACTIVE' THEN 'PAUSED' ELSE schedule_state END,
                               realtime = false, updated_at = :now
                         WHERE organization_id = :org AND template_key = :key AND status = 'ACTIVE'
                           AND (schedule_state = 'ACTIVE' OR realtime)
                        RETURNING analysis_id""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("key", templateKey).query(Long.class).list();
    }

    /** 때가 된 일정(행 잠금, 다른 파드가 잡은 행은 건너뜀). 배포 조직 제한이 있으면 그 조직만 */
    @OrganizationScopeExempt("1분 일정 실행기가 배포 조직(restriction) 안의 모든 분석을 본다")
    public List<AnalysisRef> lockDue(Instant now, Long restrictOrganizationId, int limit) {
        String sql = SELECT.replace("LEFT JOIN data2flow_core.app_users u ON u.organization_id = r.organization_id AND u.id = r.owner_user_id",
                "LEFT JOIN data2flow_core.app_users u ON false")
                + " WHERE r.status = 'ACTIVE' AND r.schedule_state = 'ACTIVE' AND r.next_run_at <= :now"
                + (restrictOrganizationId == null ? "" : " AND r.organization_id = :restrict")
                + " ORDER BY r.next_run_at LIMIT :limit FOR UPDATE OF r SKIP LOCKED";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("now", Pg.ts(now)).param("limit", limit);
        if (restrictOrganizationId != null) {
            spec = spec.param("restrict", restrictOrganizationId);
        }
        return spec.query(AnalysisRefRepository::row).list();
    }

    /** 일정 한 번 실행했음(다음 시각으로 옮김) */
    public void advanceSchedule(long organizationId, long analysisId, Instant scheduledAt, Instant next) {
        jdbc.sql("""
                        UPDATE data2flow_core.analysis_refs SET last_scheduled_at = :at, next_run_at = :next
                         WHERE organization_id = :org AND analysis_id = :id""")
                .param("at", Pg.ts(scheduledAt)).param("next", Pg.ts(next)).param("org", organizationId).param("id", analysisId).update();
    }

    /** 사이트 쾌적도(DEV-10.02): 그 템플릿의 최근 성공 실행이 있는 분석(최근 성공 순) */
    public List<AnalysisRef> listSucceeded(long organizationId, String templateKey) {
        return jdbc.sql(SELECT + " " + """
                         WHERE r.organization_id = :org AND r.template_key = :key AND r.status = 'ACTIVE' AND r.last_succeeded_run_id IS NOT NULL
                         ORDER BY r.last_succeeded_at DESC NULLS LAST""")
                .param("org", organizationId).param("key", templateKey).query(AnalysisRefRepository::row).list();
    }

    static AnalysisRef row(ResultSet rs, int n) throws SQLException {
        return new AnalysisRef(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("analysis_id"), rs.getString("name"),
                rs.getString("template_key"), rs.getString("template_version"), rs.getLong("owner_user_id"), Pg.longList(rs, "space_scope_ids"),
                rs.getString("target_summary"), rs.getString("schedule"), rs.getString("schedule_state"), Pg.instant(rs, "next_run_at"),
                Pg.instant(rs, "last_scheduled_at"), rs.getBoolean("realtime"), Pg.longOrNull(rs, "last_run_id"),
                rs.getString("last_run_status"), Pg.instant(rs, "last_run_finished_at"), Pg.longOrNull(rs, "last_succeeded_run_id"),
                Pg.instant(rs, "last_succeeded_at"), rs.getString("status"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"),
                rs.getString("owner_name"));
    }
}
