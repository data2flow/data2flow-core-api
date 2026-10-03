package net.java21.data2flow.core.control.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 사용자 정의 기능 {@code custom.*}({@code data2flow_core.capabilities}, ACT-01.01·BR-ACT-22). 표준 기능은 contracts JSON */
@Repository
public class CapabilityRepository {

    private static final String COLUMNS = """
            id, organization_id, name, version_no, attributes::text AS attributes, commands::text AS commands,
            expected_effects::text AS expected_effects, matter_cluster, updated_at""";

    private final JdbcClient jdbc;

    public CapabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record CapabilityRow(long id, long organizationId, String name, int versionNo, String attributes, String commands,
                                String expectedEffects, String matterCluster, Instant updatedAt) {
    }

    public List<CapabilityRow> listByOrganization(long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.capabilities WHERE organization_id = :org ORDER BY name")
                .param("org", organizationId).query(CapabilityRepository::map).list();
    }

    public Optional<CapabilityRow> findByName(long organizationId, String name) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.capabilities WHERE organization_id = :org AND name = :name")
                .param("org", organizationId).param("name", name).query(CapabilityRepository::map).optional();
    }

    public void insert(long organizationId, String name, String attributes, String commands, String expectedEffects,
                       String matterCluster, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.capabilities (organization_id, name, version_no, standard, attributes, commands,
                                                                 expected_effects, matter_cluster, created_at, updated_at)
                        VALUES (:org, :name, 1, false, CAST(:attrs AS jsonb), CAST(:cmds AS jsonb), CAST(:effects AS jsonb), :matter,
                                :now, :now)""")
                .param("org", organizationId).param("name", name).param("attrs", attributes).param("cmds", commands)
                .param("effects", expectedEffects).param("matter", matterCluster).param("now", Pg.ts(now)).update();
    }

    public int update(long organizationId, String name, String attributes, String commands, String expectedEffects,
                      String matterCluster, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.capabilities SET attributes = CAST(:attrs AS jsonb), commands = CAST(:cmds AS jsonb),
                               expected_effects = CAST(:effects AS jsonb), matter_cluster = :matter, version_no = version_no + 1,
                               updated_at = :now
                         WHERE organization_id = :org AND name = :name""")
                .param("org", organizationId).param("name", name).param("attrs", attributes).param("cmds", commands)
                .param("effects", expectedEffects).param("matter", matterCluster).param("now", Pg.ts(now)).update();
    }

    private static CapabilityRow map(ResultSet rs, int row) throws SQLException {
        return new CapabilityRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getInt("version_no"),
                rs.getString("attributes"), rs.getString("commands"), rs.getString("expected_effects"), rs.getString("matter_cluster"),
                Pg.instant(rs, "updated_at"));
    }
}
