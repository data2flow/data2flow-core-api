package net.java21.data2flow.core.script;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스크립트 통합 테스트 기반: pipeline 대역(API-SCR-30·31)을 {@code data2flow.core.pipeline-base-url}로, ai 내부 API 대역을
 * {@code data2flow.core.analytics.ai-base-url}로 연결한다.
 * 스크립트 IT는 모두 이 클래스를 상속해 스프링 컨텍스트 하나를 함께 쓴다.
 */
abstract class ScriptItSupport extends IntegrationTestSupport {

    static final PipelineStubServer PIPELINE = new PipelineStubServer();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TRANSFORM_OK = "function transform(msg, ctx) {\n  return msg;\n}\n";

    @DynamicPropertySource
    static void pipeline(DynamicPropertyRegistry registry) {
        registry.add("data2flow.core.pipeline-base-url", PIPELINE::baseUrl);
        // ai 내부 API(API-SCR-16 위임 /internal/ai/script-drafts)도 같은 대역이 받는다
        registry.add("data2flow.core.analytics.ai-base-url", PIPELINE::baseUrl);
    }

    @BeforeEach
    void resetPipeline() {
        PIPELINE.reset();
    }

    static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 스크립트 생성 → 응답 response */
    JsonNode create(long org, long user, String json) throws Exception {
        return body(mvc.perform(as(org, user, json(post("/core/scripts"), json))).andExpect(status().isCreated()).andReturn())
                .get("response");
    }

    static String str(String value) {
        return JSON.writeValueAsString(value);
    }

    /**
     * 데이터 소스 한 행. 공용 M2Data.source는 unknown_device_policy 기본값 'AUTO_REGISTER'(13자)가 varchar(12) 열에 들어가지 않아
     * (기반 마이그레이션 문제, 보고함) 여기서는 REJECT로 직접 넣는다.
     */
    long source(long org, String code) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.data_sources (organization_id, code, name, type, lifecycle, connection, decoder_key,
                                                                unknown_device_policy, created_by, updated_by)
                        VALUES (:org, :code, :name, 'MQTT_SUBSCRIBE', 'ACTIVE', CAST(:conn AS jsonb), 'chirpstack-v4', 'REJECT', 0, 0)
                        RETURNING id""")
                .param("org", org).param("code", code).param("name", "소스 " + code)
                .param("conn", "{\"url\":\"wss://broker.test/mqtt\"}").query(Long.class).single();
    }

    long outboxConfigCount(long org) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :o AND exchange = 'data2flow.config'")
                .param("o", org).query(Long.class).single();
    }
}
