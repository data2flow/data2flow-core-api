package net.java21.data2flow.core.source.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 무중단 자격증명 교체 기록({@code source_secret_rotations}, DSC-07.02·BR-DSC-09) */
@Repository
public class SourceRotationRepository {

    private final JdbcClient jdbc;

    public SourceRotationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record RotationRow(long id, long organizationId, long sourceId, String rotationId, String state, List<String> kinds, String instances,
                              String error, long startedBy, Instant startedAt, Instant finishedAt) {
    }

    public void insert(long organizationId, long sourceId, String rotationId, String state, Collection<String> kinds, long userId, Instant now,
                       Instant finishedAt) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.source_secret_rotations (organization_id, source_id, rotation_id, state, kinds, started_by,
                                                                           started_at, finished_at)
                        VALUES (:org, :source, :rid, :state, CAST(:kinds AS text[]), :user, :now, :fin)""")
                .param("org", organizationId).param("source", sourceId).param("rid", rotationId).param("state", state)
                .param("kinds", Pg.textArray(kinds)).param("user", userId).param("now", Pg.ts(now))
                .param("fin", finishedAt == null ? null : Pg.ts(finishedAt)).update();
    }

    public Optional<RotationRow> findByRotationId(long organizationId, long sourceId, String rotationId) {
        return jdbc.sql("SELECT * FROM data2flow_core.source_secret_rotations WHERE organization_id = :org AND source_id = :source AND rotation_id = :rid")
                .param("org", organizationId).param("source", sourceId).param("rid", rotationId).query(SourceRotationRepository::map).optional();
    }

    /** 진행 중인 교체(소스당 하나) — 잠금 */
    public Optional<RotationRow> lockActive(long organizationId, long sourceId) {
        return jdbc.sql("""
                        SELECT * FROM data2flow_core.source_secret_rotations
                         WHERE organization_id = :org AND source_id = :source AND state = 'ROTATING' ORDER BY id DESC LIMIT 1 FOR UPDATE""")
                .param("org", organizationId).param("source", sourceId).query(SourceRotationRepository::map).optional();
    }

    /** 여러 소스의 진행 중 교체 ID(실행 설정 API-DSC-50) */
    @OrganizationScopeExempt("ingress 실행 설정(API-DSC-50). 이미 배포 조직으로 좁힌 소스 ID만 넘어온다")
    public Map<Long, String> findActiveOf(Collection<Long> sourceIds) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (sourceIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT source_id, rotation_id FROM data2flow_core.source_secret_rotations
                         WHERE source_id = ANY(CAST(:ids AS bigint[])) AND state = 'ROTATING'""")
                .param("ids", Pg.bigintArray(sourceIds)).query((rs, n) -> out.put(rs.getLong("source_id"), rs.getString("rotation_id"))).list();
        return out;
    }

    /** 오래 걸리는 교체(배포 조직 전체, 주기 작업) */
    @OrganizationScopeExempt("주기 작업: 시작한 지 오래된 교체를 끝낸다. 결과 행에 조직 ID가 있다")
    public List<RotationRow> listStale(Instant startedBefore) {
        return jdbc.sql("SELECT * FROM data2flow_core.source_secret_rotations WHERE state = 'ROTATING' AND started_at < :before ORDER BY id")
                .param("before", Pg.ts(startedBefore)).query(SourceRotationRepository::map).list();
    }

    public int updateInstances(long organizationId, long id, String instancesJson) {
        return jdbc.sql("UPDATE data2flow_core.source_secret_rotations SET instances = CAST(:i AS jsonb) WHERE organization_id = :org AND id = :id")
                .param("i", instancesJson).param("org", organizationId).param("id", id).update();
    }

    public int updateFinished(long organizationId, long id, String state, String error, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.source_secret_rotations SET state = :state, error = :error, finished_at = :now
                         WHERE organization_id = :org AND id = :id AND state = 'ROTATING'""")
                .param("state", state).param("error", error == null ? null : error.length() > 500 ? error.substring(0, 500) : error)
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    static RotationRow map(ResultSet rs, int n) throws SQLException {
        return new RotationRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("source_id"), rs.getString("rotation_id"),
                rs.getString("state"), Pg.stringList(rs, "kinds"), rs.getString("instances"), rs.getString("error"), rs.getLong("started_by"),
                Pg.instant(rs, "started_at"), Pg.instant(rs, "finished_at"));
    }
}
