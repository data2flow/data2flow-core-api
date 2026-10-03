package net.java21.data2flow.core.space;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 공간 테스트 데이터 보조. 데이터 소스 행은 공용 {@code M2Data#source}가 기본값(unknown_device_policy 'AUTO_REGISTER', 13자)이
 * varchar(12)를 넘어 넣지 못하므로 여기서 정책을 지정해 만든다(소스 기능 마이그레이션이 열 길이를 고치면 공용 도우미를 쓴다).
 */
final class SpaceFixtures {

    private SpaceFixtures() {
    }

    static long source(JdbcClient jdbc, long orgId, String code) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.data_sources (organization_id, code, name, type, lifecycle, connection, decoder_key,
                                                                unknown_device_policy, created_by, updated_by)
                        VALUES (:org, :code, :name, 'MQTT_SUBSCRIBE', 'ACTIVE', CAST('{}' AS jsonb), 'chirpstack-v4', 'REJECT', 0, 0)
                        RETURNING id""")
                .param("org", orgId).param("code", code).param("name", "소스 " + code).query(Long.class).single();
    }
}
