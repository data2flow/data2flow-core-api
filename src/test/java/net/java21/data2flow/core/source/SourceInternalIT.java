package net.java21.data2flow.core.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내부 API: API-DSC-50 ingress 소스 실행 설정(복호화한 비밀값·토픽·client-id base·한도, sinceVersion 같으면 204),
 * API-ING-21 pipeline 수집 맥락(디코더·정책·기본값·측정 항목과 별칭·스크립트, contextVersion) — DSC-01.01·02.02, ING-02.01·03.02
 */
class SourceInternalIT extends SourceItSupport {

    @Test
    @DisplayName("[DSC-01.01][API-DSC-50] ACTIVE·PAUSED만, 비밀값은 복호화한 값, config에 topics·clientIdBase, 바뀌지 않았으면 204")
    void runtimeConfig() throws Exception {
        long active = createSource(mqttBody("run-a", "\"activate\":true"));
        createSource(mqttBody("run-draft", null));
        MvcResult r = mvc.perform(get("/internal/core/sources/runtime-config").header("X-CALLER-SERVICE", "data2flow-ingress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sources.length()").value(1))
                .andExpect(jsonPath("$.response.sources[0].id").value(Long.toString(active)))
                .andExpect(jsonPath("$.response.sources[0].organizationId").value(Long.toString(org)))
                .andExpect(jsonPath("$.response.sources[0].type").value("MQTT_SUBSCRIBE"))
                .andExpect(jsonPath("$.response.sources[0].connectorKey").value("mqtt"))
                .andExpect(jsonPath("$.response.sources[0].secrets.HEADER_VALUE").value("student:s3cr3t-Pa55"))
                .andExpect(jsonPath("$.response.sources[0].config.topics[0].topic").value("application/+/device/+/event/up"))
                .andExpect(jsonPath("$.response.sources[0].config.clientIdBase").value("data2flow-run-a"))
                .andExpect(jsonPath("$.response.sources[0].config.version").value("5.0"))
                .andExpect(jsonPath("$.response.sources[0].clientIdBase").value("data2flow-run-a"))
                .andExpect(jsonPath("$.response.sources[0].clientId").value("data2flow-run-a"))
                .andExpect(jsonPath("$.response.sources[0].qos").value(1))
                .andExpect(jsonPath("$.response.sources[0].rateLimit.maxMessagesPerSec").value(500))
                .andExpect(jsonPath("$.response.sources[0].rateLimit.maxMessageBytes").value(262144))
                .andExpect(jsonPath("$.response.sources[0].unknownDevicePolicy").value("AUTO_REGISTER"))
                .andReturn();
        long version = Long.parseLong(read(r, "$.response.version"));
        mvc.perform(get("/internal/core/sources/runtime-config").param("sinceVersion", Long.toString(version)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/internal/core/sources/runtime-config").param("lifecycle", "DRAFT,ACTIVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.sources.length()").value(2));
        mvc.perform(get("/internal/core/sources/runtime-config").param("lifecycle", "ZZZ")).andExpect(status().isBadRequest());
        // 설정이 바뀌면 버전이 오르고 전체 스냅샷을 다시 준다
        mvc.perform(as(org, integrator, json(post("/core/sources/" + active + "/pause"), "{\"baseVersion\":0}"))).andExpect(status().isOk());
        mvc.perform(get("/internal/core/sources/runtime-config").param("sinceVersion", Long.toString(version)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.sources[0].lifecycle").value("PAUSED"))
                .andExpect(jsonPath("$.response.version").value((int) version + 1));
    }

    @Test
    @DisplayName("[ING-02.01][ING-03.02][API-ING-21] 수집 맥락: 디코더(script:{id}@v{n})·정책·기본값·측정 항목 별칭·연결 스크립트, 없는 소스 404")
    void ingestContext() throws Exception {
        long site = data.site(org, "본관");
        data.metric(org, "temperature", "°C");
        data.metric(org, "TVOC", "ppb");
        jdbc.sql("INSERT INTO data2flow_core.metric_aliases (organization_id, alias, metric_key) VALUES (:o, 'temp', 'temperature')")
                .param("o", org).update();
        long model = data.model(org, "EM300-TH", List.of("temperature"));
        long script = jdbc.sql("INSERT INTO data2flow_core.scripts (organization_id, name, kind, created_by, updated_by) VALUES (:o, 'dec', 'DECODE', 0, 0) RETURNING id")
                .param("o", org).query(Long.class).single();
        long ver = jdbc.sql("""
                        INSERT INTO data2flow_core.script_versions (organization_id, script_id, version_no, code, code_sha256, status, static_check, created_by)
                        VALUES (:o, :s, 3, 'x', repeat('a', 64), 'ACTIVE', '{}'::jsonb, 0) RETURNING id""")
                .param("o", org).param("s", script).query(Long.class).single();
        jdbc.sql("UPDATE data2flow_core.scripts SET active_version_id = :v WHERE id = :s").param("v", ver).param("s", script).update();
        long id = createSource(mqttBody("ctx", "\"defaultSpaceId\":\"%d\",\"defaultModelId\":%d,\"autoregLimitPerHour\":5".formatted(site, model))
                .replace("\"decoderKey\":\"chirpstack-v4\"", "\"decoderKey\":\"script\",\"decodeScriptId\":" + script));
        jdbc.sql("""
                        INSERT INTO data2flow_core.script_bindings (organization_id, script_id, kind, target_type, target_id)
                        VALUES (:o, :s, 'DECODE', 'SOURCE', :t)""").param("o", org).param("s", script).param("t", Long.toString(id)).update();

        mvc.perform(get("/internal/core/ingest-context").param("sourceId", Long.toString(id)).header("X-CALLER-SERVICE", "data2flow-pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sourceId").value(Long.toString(id)))
                .andExpect(jsonPath("$.response.sourceType").value("MQTT_SUBSCRIBE"))
                .andExpect(jsonPath("$.response.decoder.type").value("script"))
                .andExpect(jsonPath("$.response.decoder.key").value("script:" + script + "@v3"))
                .andExpect(jsonPath("$.response.decoder.scriptId").value(Long.toString(script)))
                .andExpect(jsonPath("$.response.unknownDevicePolicy").value("AUTO_REGISTER"))
                .andExpect(jsonPath("$.response.autoRegisterHourlyLimit").value(5))
                .andExpect(jsonPath("$.response.defaultSpaceId").value(Long.toString(site)))
                .andExpect(jsonPath("$.response.defaultModelId").value(Long.toString(model)))
                .andExpect(jsonPath("$.response.metrics.length()").value(2))
                .andExpect(jsonPath("$.response.metrics[?(@.key=='temperature')].aliases[0]").value("temp"))
                .andExpect(jsonPath("$.response.scriptIds.length()").value(1))
                .andExpect(jsonPath("$.response.contextVersion").value(1));
        long plain = createSource(mqttBody("ctx2", null));
        mvc.perform(get("/internal/core/ingest-context").param("sourceId", Long.toString(plain)))
                .andExpect(jsonPath("$.response.decoder.key").value("chirpstack-v4"))
                .andExpect(jsonPath("$.response.decoder.scriptId").doesNotExist())
                .andExpect(jsonPath("$.response.contextVersion").value(2));
        mvc.perform(get("/internal/core/ingest-context").param("sourceId", "999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SOURCE_NOT_FOUND"));
    }
}
