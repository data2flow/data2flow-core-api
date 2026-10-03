package net.java21.data2flow.core.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.OptionalLong;

/**
 * 조직·범위별 설정 버전({@code data2flow_core.config_versions}). 내부 전체 조회의 {@code sinceVersion}(API-DEV-123 측정 항목,
 * API-DSC-50 소스 실행 설정, API-SCR-32 스크립트 묶음)에 쓴다. 바꾸는 트랜잭션 안에서 {@link #bump}하면 커밋과 함께 버전이 오른다.
 */
@Component
public class ConfigVersions {

    /** 측정 항목·별칭(API-DEV-123) */
    public static final String METRICS = "METRICS";
    /** 데이터 소스 실행 설정(API-DSC-50) */
    public static final String SOURCES = "SOURCES";
    /** 스크립트·연결(API-SCR-32) */
    public static final String SCRIPTS = "SCRIPTS";
    /** 기기 식별 정보(API-DEV-130) */
    public static final String DEVICES = "DEVICES";
    public static final String MODELS = "MODELS";
    public static final String SPACES = "SPACES";
    public static final String GROUPS = "GROUPS";

    private final JdbcClient jdbc;
    private final Clock clock;

    public ConfigVersions(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 버전을 1 올리고 새 버전을 돌려준다 */
    public long bump(long organizationId, String scope) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.config_versions (organization_id, scope, version, updated_at)
                        VALUES (:org, :scope, 1, :now)
                        ON CONFLICT (organization_id, scope) DO UPDATE
                           SET version = data2flow_core.config_versions.version + 1, updated_at = EXCLUDED.updated_at
                        RETURNING version""")
                .param("org", organizationId).param("scope", scope).param("now", Pg.ts(clock.instant()))
                .query(Long.class).single();
    }

    /** 현재 버전(없으면 0) */
    public long current(long organizationId, String scope) {
        return jdbc.sql("SELECT version FROM data2flow_core.config_versions WHERE organization_id = :org AND scope = :scope")
                .param("org", organizationId).param("scope", scope).query(Long.class).optional().orElse(0L);
    }

    /**
     * 여러 조직의 버전 합(조직 구분 없는 전체 조회, 예: ingress 소스 실행 설정). 하나라도 오르면 커진다.
     *
     * @param onlyOrganization 배포 조직으로 좁힐 때(ADR-030, {@code DeploymentOrganization#restriction()}). 비면 모든 조직
     */
    public long sum(String scope, OptionalLong onlyOrganization) {
        Long org = onlyOrganization.isPresent() ? onlyOrganization.getAsLong() : null;
        return jdbc.sql("""
                        SELECT coalesce(sum(version), 0) FROM data2flow_core.config_versions
                         WHERE scope = :scope AND (CAST(:org AS bigint) IS NULL OR organization_id = :org)""")
                .param("scope", scope).param("org", org).query(Long.class).single();
    }
}
