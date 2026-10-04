package net.java21.data2flow.core.control.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 제어 창구가 명령을 검증·실행할 때 필요한 기기 정의(API-ACT-40 내부 제어 정보): 기기·모델 지원 기능과 제약·드라이버·샌드박스 여부.
 * 샌드박스는 기기 공간이나 조상 공간 중 하나라도 {@code sandbox=true}면 참이다(SIM-07.03 "별도 공간 트리").
 */
@Repository
public class ControlProfileRepository {

    private final JdbcClient jdbc;

    public ControlProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ProfileRow(long deviceId, long organizationId, String name, String status, boolean virtual, Long spaceId,
                             boolean sandbox, Long modelId, String modelCode, String modelCapabilities, String externalId,
                             long sourceId, Long driverId, Long encoderScriptId, String spacePath, Integer reportIntervalSec) {
    }

    @OrganizationScopeExempt("기기 ID는 전역 고유이고 응답에 organizationId를 담는다(action 내부 호출, ADR-021)")
    public Optional<ProfileRow> findByDeviceId(long deviceId) {
        return jdbc.sql("""
                        SELECT d.id, d.organization_id, d.name, d.status, d.is_virtual, d.space_id,
                               EXISTS (SELECT 1 FROM data2flow_core.spaces a WHERE a.organization_id = d.organization_id AND a.sandbox
                                         AND s.path LIKE a.path || '%') AS sandbox,
                               d.model_id, m.code AS model_code, m.capabilities::text AS model_capabilities, d.external_id, d.source_id,
                               b.driver_id, b.encoder_script_id, s.path AS space_path,
                               coalesce(d.expected_interval_sec, m.default_interval_sec) AS report_interval_sec
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.spaces s ON s.id = d.space_id AND s.organization_id = d.organization_id
                          LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id AND m.organization_id = d.organization_id
                          LEFT JOIN data2flow_core.driver_bindings b ON b.model_id = d.model_id AND b.organization_id = d.organization_id
                         WHERE d.id = :id""")
                .param("id", deviceId).query(ControlProfileRepository::map).optional();
    }

    /** 샌드박스 공간과 그 하위 공간 ID(action 시작 시 전체 읽기) */
    public List<Long> listSandboxSpaceIds(long organizationId) {
        return jdbc.sql("""
                        SELECT DISTINCT s.id FROM data2flow_core.spaces s
                          JOIN data2flow_core.spaces a ON a.organization_id = s.organization_id AND a.sandbox AND s.path LIKE a.path || '%'
                         WHERE s.organization_id = :org ORDER BY s.id""")
                .param("org", organizationId).query(Long.class).list();
    }

    private static ProfileRow map(ResultSet rs, int row) throws SQLException {
        return new ProfileRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("status"),
                rs.getBoolean("is_virtual"), Pg.longOrNull(rs, "space_id"), rs.getBoolean("sandbox"), Pg.longOrNull(rs, "model_id"),
                rs.getString("model_code"), rs.getString("model_capabilities"), rs.getString("external_id"), rs.getLong("source_id"),
                Pg.longOrNull(rs, "driver_id"), Pg.longOrNull(rs, "encoder_script_id"), rs.getString("space_path"),
                (Integer) rs.getObject("report_interval_sec"));
    }
}
