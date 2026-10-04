package net.java21.data2flow.core.source;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSC-01.03 Webhook 수신 소스(core 쪽): 수신 키·서명 비밀값 1회 표시·수신 주소·ingress 실행 설정 — TC-DSC-021·022 */
class WebhookSourceIT extends SourceItSupport {

    static final String BODY = """
            {"code":"%s","name":"BMS 웹훅","type":"WEBHOOK","connection":{"toleranceSec":300},"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"$.deviceId","metrics":[{"path":"$.t","key":"temperature"}]},"activate":true}""";

    @Test
    @DisplayName("[DSC-01.03][AT-DSC-06.1][TC-DSC-021] 만들면 수신 키(32자)·HMAC 비밀값이 한 번만 보이고, 수신 주소는 data2flow-hook, ingress 실행 설정에 키·비밀값")
    void createWebhookSource() throws Exception {
        MvcResult r = mvc.perform(as(org, integrator, json(post("/core/sources"), BODY.formatted("bms-hook")))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.type").value("WEBHOOK")).andExpect(jsonPath("$.response.lifecycle").value("ACTIVE"))
                .andExpect(jsonPath("$.response.issuedSecret.kind").value("HMAC_KEY"))
                .andExpect(jsonPath("$.response.secret.kind").value("HMAC_KEY")).andExpect(jsonPath("$.response.secret.configured").value(true))
                .andReturn();
        String body = r.getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.response.id");
        String key = JsonPath.read(body, "$.response.connection.sourceKey");
        String hmac = JsonPath.read(body, "$.response.issuedSecret.value");
        assertThat(key).hasSize(32);
        assertThat(hmac).hasSize(64);
        assertThat((String) JsonPath.read(body, "$.response.webhookUrl")).isEqualTo("https://data2flow-hook.java21.net/ingest/webhook/" + key);
        // 다시 열면 비밀값은 지문만
        mvc.perform(as(org, operator, get("/core/sources/" + id))).andExpect(jsonPath("$.response.issuedSecret").doesNotExist())
                .andExpect(jsonPath("$.response.secret.fingerprint").value(Matchers.startsWith("••••")))
                .andExpect(jsonPath("$.response.webhookUrl").value("https://data2flow-hook.java21.net/ingest/webhook/" + key));
        String runtime = mvc.perform(get("/internal/core/sources/runtime-config")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat((List<String>) JsonPath.read(runtime, "$.response.sources[?(@.id == '" + id + "')].secrets.HMAC_KEY")).containsExactly(hmac);
        assertThat((List<String>) JsonPath.read(runtime, "$.response.sources[?(@.id == '" + id + "')].config.sourceKey")).containsExactly(key);
        assertThat((List<String>) JsonPath.read(runtime, "$.response.sources[?(@.id == '" + id + "')].connectorKey")).containsExactly("webhook");
        // 수신 키는 바꿀 수 없다, 다른 조직도 같은 키를 쓸 수 없다
        int version = JsonPath.read(body, "$.response.version");
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"connection\":{\"sourceKey\":\"zzzzzzzzzzzzzzzzzz\"},\"baseVersion\":"
                        + version + "}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("IMMUTABLE"));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"connection\":{\"toleranceSec\":600},\"baseVersion\":" + version + "}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.connection.sourceKey").value(key));
        long other = fx.organization("dsc-other");
        long otherAdmin = fx.user(other, "dsc.other", "ADMIN");
        mvc.perform(as(other, otherAdmin, json(post("/core/sources"),
                        "{\"code\":\"x-hook\",\"name\":\"x\",\"type\":\"WEBHOOK\",\"connection\":{\"sourceKey\":\"" + key + "\"},\"decoderKey\":\"generic-json\",\"decoderConfig\":{\"deviceIdFrom\":\"$.deviceId\",\"metrics\":[{\"path\":\"$.t\",\"key\":\"temperature\"}]}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 사용자가 준 비밀값이면 issuedSecret 없음, 다른 종류는 NOT_FOR_AUTH
        mvc.perform(as(org, integrator, json(post("/core/sources"), BODY.formatted("hook-two")
                        .replace("\"activate\":true", "\"secret\":{\"kind\":\"HMAC_KEY\",\"value\":\"my-shared-secret\"}"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.issuedSecret").doesNotExist());
        mvc.perform(as(org, integrator, json(post("/core/sources"), BODY.formatted("hook-three")
                        .replace("\"activate\":true", "\"secret\":{\"kind\":\"PASSWORD\",\"value\":\"p\"}"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_AUTH_UNSUPPORTED"));
        // 복제하면 수신 키를 새로 만들고 비밀값은 복사하지 않는다
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/clone"), "{\"code\":\"bms-hook-copy\",\"name\":\"복제\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.lifecycle").value("DRAFT"))
                .andExpect(jsonPath("$.response.connection.sourceKey").value(Matchers.not(key)))
                .andExpect(jsonPath("$.response.secrets").isEmpty());
    }

    @Test
    @DisplayName("[DSC-01.03][TC-DSC-022] 권한: OPERATOR는 조회만(만들기 403), 다른 조직 404")
    void permissions() throws Exception {
        long id = createSource(BODY.formatted("perm-hook"));
        mvc.perform(as(org, operator, json(post("/core/sources"), BODY.formatted("op-hook")))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, get("/core/sources/" + id))).andExpect(status().isOk());
        long other = fx.organization("dsc-o2");
        long otherAdmin = fx.user(other, "dsc.o2", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/sources/" + id))).andExpect(status().isNotFound());
    }
}
