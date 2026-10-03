package net.java21.data2flow.core.device.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.device.domain.Device;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 서비스 간 내부 조회(pipeline·flow-engine): 요청에 조직이 없으므로 기기 ID·변경 시각으로 찾되, 배포 조직으로 좁힌다
 * (ADR-030: staging과 prod가 DB를 함께 쓴다 → {@code DeploymentOrganization#restriction()}).
 */
@Repository
public class InternalDeviceRepository {

    private final JdbcClient jdbc;

    public InternalDeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @OrganizationScopeExempt("내부 API-DEV-122: 기기 ID로 조직을 찾는다. 배포 조직으로 좁힌다")
    public Optional<Device> findAnyOrganization(long deviceId, OptionalLong onlyOrganization) {
        Long org = onlyOrganization.isPresent() ? onlyOrganization.getAsLong() : null;
        return jdbc.sql("SELECT " + DeviceRepository.COLUMNS + " FROM data2flow_core.devices d WHERE d.id = :id"
                        + " AND (CAST(:org AS bigint) IS NULL OR d.organization_id = :org)")
                .param("id", deviceId).param("org", org).query(DeviceRepository::map).optional();
    }

    /** API-DEV-130: 변경 시각·ID 순 페이지. statuses가 비면 삭제를 포함한 모든 상태(캐시에서 지울 기기도 알려 준다) */
    @OrganizationScopeExempt("내부 API-DEV-130 캐시 예열: 배포 조직 전체를 페이지로 읽는다")
    public List<Device> listChanged(OptionalLong onlyOrganization, List<String> statuses, Instant updatedAfter, int limit, long offset) {
        Long org = onlyOrganization.isPresent() ? onlyOrganization.getAsLong() : null;
        return jdbc.sql("SELECT " + DeviceRepository.COLUMNS + " FROM data2flow_core.devices d"
                        + " WHERE (CAST(:org AS bigint) IS NULL OR d.organization_id = :org)"
                        + " AND (cardinality(CAST(:statuses AS text[])) = 0 OR d.status = ANY(CAST(:statuses AS text[])))"
                        + " AND (CAST(:after AS timestamptz) IS NULL OR d.updated_at > :after)"
                        + " ORDER BY d.updated_at, d.id LIMIT :limit OFFSET :offset")
                .param("org", org).param("statuses", Pg.textArray(statuses)).param("after", Pg.ts(updatedAfter)).param("limit", limit)
                .param("offset", offset).query(DeviceRepository::map).list();
    }

    @OrganizationScopeExempt("내부 API-DEV-130 캐시 예열: 배포 조직 전체 개수")
    public long countChanged(OptionalLong onlyOrganization, List<String> statuses, Instant updatedAfter) {
        Long org = onlyOrganization.isPresent() ? onlyOrganization.getAsLong() : null;
        return jdbc.sql("SELECT count(*) FROM data2flow_core.devices d"
                        + " WHERE (CAST(:org AS bigint) IS NULL OR d.organization_id = :org)"
                        + " AND (cardinality(CAST(:statuses AS text[])) = 0 OR d.status = ANY(CAST(:statuses AS text[])))"
                        + " AND (CAST(:after AS timestamptz) IS NULL OR d.updated_at > :after)")
                .param("org", org).param("statuses", Pg.textArray(statuses)).param("after", Pg.ts(updatedAfter))
                .query(Long.class).single();
    }

    /** 기기·모델에 연결된 TRANSFORM 스크립트(script_bindings, WP-D 소유 — 읽기만). [scope, scriptId, versionNo] */
    public List<Object[]> findTransformBindings(long organizationId, long deviceId, Long modelId) {
        return jdbc.sql("""
                        SELECT b.target_type, b.script_id, v.version_no
                          FROM data2flow_core.script_bindings b
                          JOIN data2flow_core.scripts s ON s.id = b.script_id AND s.organization_id = b.organization_id
                          LEFT JOIN data2flow_core.script_versions v ON v.id = s.active_version_id
                         WHERE b.organization_id = :org AND b.kind = 'TRANSFORM' AND b.enabled AND s.status = 'ENABLED'
                           AND ((b.target_type = 'MODEL' AND b.target_id = :model) OR (b.target_type = 'DEVICE' AND b.target_id = :device))
                         ORDER BY CASE b.target_type WHEN 'MODEL' THEN 0 ELSE 1 END, b.id""")
                .param("org", organizationId).param("model", modelId == null ? "" : Long.toString(modelId))
                .param("device", Long.toString(deviceId))
                .query((rs, n) -> new Object[] {rs.getString("target_type"), rs.getLong("script_id"), Pg.longOrNull(rs, "version_no")})
                .list();
    }
}
