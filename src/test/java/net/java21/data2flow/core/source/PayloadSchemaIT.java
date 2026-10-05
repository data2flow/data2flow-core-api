package net.java21.data2flow.core.source;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * payload 형식·스키마·토픽 템플릿(DSC-09.07·09.08, ADR-056): API-DSC-59 업로드(ingress API-DSC-82 검사 → 보관·불변 schemaRef),
 * API-DSC-81 내부 조회, 토픽 템플릿 미리보기(API-DSC-83 중계), 소스 설정 payload·topicTemplate 판정과 API-DSC-50 전달.
 */
class PayloadSchemaIT extends SourceItSupport {

    static final String PROTO = "syntax = \"proto3\";\nmessage Reading { double temperature = 1; }\n";
    static final String OK = "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"OK\",\"resultMessage\":\"OK\"},\"response\":%s}";

    private MvcResult upload(long user, long sourceId, String fileName, byte[] content) throws Exception {
        return mvc.perform(multipart("/core/sources/" + sourceId + "/payload-schema").header("X-USER-ID", Long.toString(user)).header("X-ORG-ID", Long.toString(org))
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content)).param("messageType", "Reading")).andReturn();
    }

    @Test
    @DisplayName("[DSC-09.07][UC-DSC-16] .proto 업로드 → ingress 검사(API-DSC-82) 뒤 보관, 새 schemaRef가 소스 payload에 들어가고 판이 오르며 API-DSC-81·50으로 ingress에 간다 — TC-DSC-281")
    void uploadProto() throws Exception {
        long source = createSource(mqttBody("pb1", null));
        INGRESS.payloadResponse.put("/internal/ingress/payload-schemas/inspect",
                OK.formatted("{\"format\":\"PROTOBUF\",\"messageTypes\":[\"Reading\"],\"messageType\":\"Reading\"}"));
        mvc.perform(multipart("/core/sources/" + source + "/payload-schema").header("X-USER-ID", Long.toString(operator)).header("X-ORG-ID", Long.toString(org))
                .file(new MockMultipartFile("file", "r.proto", "text/plain", PROTO.getBytes(StandardCharsets.UTF_8)))).andExpect(status().isForbidden());

        MvcResult r = upload(integrator, source, "r.proto", PROTO.getBytes(StandardCharsets.UTF_8));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String ref = JsonPath.read(r.getResponse().getContentAsString(), "$.response.schemaRef");
        assertThat(ref).startsWith("ps_");
        assertThat((List<String>) JsonPath.read(r.getResponse().getContentAsString(), "$.response.messageTypes")).containsExactly("Reading");
        String sent = INGRESS.payloadBodies.getFirst();
        assertThat(sent).contains("\"format\":\"PROTOBUF\"").contains("\"fileName\":\"r.proto\"")
                .contains(Base64.getEncoder().encodeToString(PROTO.getBytes(StandardCharsets.UTF_8)));

        mvc.perform(as(org, integrator, get("/core/sources/" + source))).andExpect(jsonPath("$.response.payload.schemaRef").value(ref))
                .andExpect(jsonPath("$.response.payload.format").value("PROTOBUF")).andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(get("/internal/core/payload-schemas/" + ref).header("X-CALLER-SERVICE", "data2flow-ingress")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.organizationId").value(Long.toString(org)))
                .andExpect(jsonPath("$.response.sourceId").value(Long.toString(source)))
                .andExpect(jsonPath("$.response.content").value(Base64.getEncoder().encodeToString(PROTO.getBytes(StandardCharsets.UTF_8))))
                .andExpect(jsonPath("$.response.messageTypes[0]").value("Reading"));
        mvc.perform(get("/internal/core/payload-schemas/ps_nope")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/core/sources/runtime-config").param("lifecycle", "DRAFT,ACTIVE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sources[?(@.id=='" + source + "')].config.payload.schemaRef").value(ref));
        assertThat(outbox(org, "CONFIG")).isNotEmpty();
    }

    @Test
    @DisplayName("[DSC-09.07] 스키마 오류는 ingress 400 SOURCE_SCHEMA_INVALID 그대로(보관 안 함), 모르는 확장자·1MiB 초과는 core가 400 — TC-DSC-286")
    void invalidSchema() throws Exception {
        long source = createSource(mqttBody("pb2", null));
        INGRESS.payloadStatus.put("/internal/ingress/payload-schemas/inspect", new int[]{400});
        INGRESS.payloadResponse.put("/internal/ingress/payload-schemas/inspect",
                "{\"header\":{\"isSuccessful\":false,\"resultCode\":\"SOURCE_SCHEMA_INVALID\",\"resultMessage\":\"스키마 파일을 읽을 수 없습니다: 2:message\"}}");
        MvcResult r = upload(integrator, source, "bad.proto", "syntax = \"proto3\"; message {".getBytes(StandardCharsets.UTF_8));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("SOURCE_SCHEMA_INVALID").contains("2:message");
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.payload_schemas WHERE organization_id = :org").param("org", org)
                .query(Long.class).single()).isZero();
        MvcResult ext = upload(integrator, source, "x.txt", "a".getBytes(StandardCharsets.UTF_8));
        assertThat(ext.getResponse().getStatus()).isEqualTo(400);
        assertThat(ext.getResponse().getContentAsString()).contains("SOURCE_SCHEMA_INVALID");
        MvcResult big = upload(integrator, source, "big.avsc", new byte[1024 * 1024 + 1]);
        assertThat(big.getResponse().getStatus()).isEqualTo(400);
        mvc.perform(multipart("/core/sources/987654/payload-schema").header("X-USER-ID", Long.toString(integrator)).header("X-ORG-ID", Long.toString(org))
                .file(new MockMultipartFile("file", "r.proto", "text/plain", PROTO.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("[DSC-09.07][DSC-09.08] 소스 설정 payload·topicTemplate: PROTOBUF는 schemaRef 필수, AVRO는 schemaRef 또는 registryUrl, 모르는 키·형식 400, 256자 초과 400, API-DSC-50 config로 전달")
    void payloadSettings() throws Exception {
        String bad = mqttBody("pl1", "\"payload\":{\"format\":\"PROTOBUF\"}");
        mvc.perform(as(org, integrator, json(post("/core/sources"), bad))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("SOURCE_CONFIG_INVALID")).andExpect(jsonPath("$.errors[0].field").value("payload.schemaRef"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("pl2", "\"payload\":{\"format\":\"XML\",\"zip\":true}"))))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("pl3", "\"payload\":{\"format\":\"AVRO\",\"schemaRef\":\"ps_unknown\"}"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("payload.schemaRef"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("pl4", "\"topicTemplate\":\"" + "a/".repeat(130) + "\""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("topicTemplate"));

        long source = createSource(mqttBody("pl5", "\"payload\":{\"format\":\"avro\",\"compression\":\"gzip\",\"registryUrl\":\"https://registry.test/apis/ccompat/v7\"},"
                + "\"topicTemplate\":\"application/{appId}/device/{externalId}/event/up\""));
        mvc.perform(as(org, integrator, get("/core/sources/" + source))).andExpect(jsonPath("$.response.payload.format").value("AVRO"))
                .andExpect(jsonPath("$.response.payload.compression").value("GZIP"))
                .andExpect(jsonPath("$.response.topicTemplate").value("application/{appId}/device/{externalId}/event/up"));
        mvc.perform(get("/internal/core/sources/runtime-config").param("lifecycle", "DRAFT,ACTIVE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sources[?(@.id=='" + source + "')].config.topicTemplate").value("application/{appId}/device/{externalId}/event/up"))
                .andExpect(jsonPath("$.response.sources[?(@.id=='" + source + "')].config.payload.registryUrl").value("https://registry.test/apis/ccompat/v7"));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + source), "{\"baseVersion\":0,\"payload\":{\"format\":\"CSV\",\"csv\":{\"delimiter\":\";\",\"header\":false}},\"topicTemplate\":null}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.payload.csv.delimiter").value(";"))
                .andExpect(jsonPath("$.response.payload.compression").value("NONE")).andExpect(jsonPath("$.response.topicTemplate").isEmpty());
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + source), "{\"baseVersion\":1,\"payload\":{\"format\":\"CSV\",\"csv\":{\"delimiter\":\";;\"}}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("payload.csv.delimiter"));
    }

    @Test
    @DisplayName("[DSC-09.08] 토픽 템플릿 미리보기: SRC_ADMIN만, ingress API-DSC-83 응답 그대로, 문법 오류 400 SOURCE_CONFIG_INVALID 그대로, 토픽 20개 초과 400")
    void preview() throws Exception {
        String body = "{\"template\":\"site/{site}/{deviceId}/{metric}\",\"topics\":[\"site/a/em-1/temperature\",\"x/y\"]}";
        INGRESS.payloadResponse.put("/internal/ingress/topic-templates/preview", OK.formatted(
                "{\"variables\":[\"site\",\"deviceId\",\"metric\"],\"subscriptionFilter\":\"site/+/+/+\",\"results\":[{\"topic\":\"site/a/em-1/temperature\","
                        + "\"matched\":true,\"attributes\":{\"externalId\":\"em-1\"}},{\"topic\":\"x/y\",\"matched\":false,\"status\":\"UNMATCHED_TOPIC\"}]}"));
        mvc.perform(as(org, operator, json(post("/core/sources/topic-templates/preview"), body))).andExpect(status().isForbidden());
        mvc.perform(as(org, integrator, json(post("/core/sources/topic-templates/preview"), body))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.subscriptionFilter").value("site/+/+/+"))
                .andExpect(jsonPath("$.response.results[1].status").value("UNMATCHED_TOPIC"));
        assertThat(INGRESS.payloadBodies.getLast()).contains("\"template\":\"site/{site}/{deviceId}/{metric}\"");
        INGRESS.payloadStatus.put("/internal/ingress/topic-templates/preview", new int[]{400});
        INGRESS.payloadResponse.put("/internal/ingress/topic-templates/preview",
                "{\"header\":{\"isSuccessful\":false,\"resultCode\":\"SOURCE_CONFIG_INVALID\",\"resultMessage\":\"topicTemplate: 닫히지 않은 {\"}}");
        mvc.perform(as(org, integrator, json(post("/core/sources/topic-templates/preview"), "{\"template\":\"a/{b\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CONFIG_INVALID"));
        mvc.perform(as(org, integrator, json(post("/core/sources/topic-templates/preview"), "{\"template\":\"a\",\"topics\":" + "[" + "\"t\",".repeat(20) + "\"t\"]}")))
                .andExpect(status().isBadRequest());
    }
}
