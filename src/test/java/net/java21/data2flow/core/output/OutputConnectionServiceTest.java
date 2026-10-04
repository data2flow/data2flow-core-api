package net.java21.data2flow.core.output;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.message.CanonicalTelemetry;
import net.java21.data2flow.core.output.domain.OutputConnectionRules;
import net.java21.data2flow.core.output.domain.OutputConnectionRules.Filter;
import net.java21.data2flow.core.output.domain.OutputSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 출력 연결 규칙(DSC-04.01, BR-DSC-19) — TC-DSC-126: 필터·형식·대상 검증, 공용 브로커 거부, 샘플(AT-DSC-10.1 co2만) */
class OutputConnectionServiceTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant NOW = Instant.parse("2026-10-04T01:00:00Z");

    private static JsonNode json(String s) {
        return JSON.readTree(s);
    }

    private static List<String> codes(List<FieldErrorDetail> errors) {
        return errors.stream().map(e -> e.field() + ":" + e.code()).toList();
    }

    @Test
    @DisplayName("[DSC-04.01][TC-DSC-126] MQTT 대상: 기본값(qos 1·retain false), 허용 밖 변수·와일드카드 토픽 거부, 공용 브로커 iot-data.java21.net 거부")
    void mqttTarget() {
        List<FieldErrorDetail> errors = new ArrayList<>();
        ObjectNode t = OutputConnectionRules.target("MQTT_PUBLISH",
                json("{\"url\":\"mqtts://broker.partner.example:8883\",\"topicTemplate\":\"d2f/{spaceCode}/{deviceName}/{metric}\"}"), errors);
        assertThat(errors).isEmpty();
        assertThat(t.get("qos").asInt()).isEqualTo(1);
        assertThat(t.get("retain").asBoolean()).isFalse();
        assertThat(t.get("topicTemplate").asString()).isEqualTo("d2f/{spaceCode}/{deviceName}/{metric}");

        errors.clear();
        OutputConnectionRules.target("MQTT_PUBLISH", json("{\"url\":\"wss://IOT-DATA.java21.net:443/mqtt\",\"topicTemplate\":\"d2f/{site}\",\"qos\":2,\"x\":1}"),
                errors);
        assertThat(codes(errors)).contains("target.url:FORBIDDEN_HOST", "target.topicTemplate:UNKNOWN_VARIABLE", "target.qos:Range",
                "target.x:UNKNOWN_FIELD");
        errors.clear();
        OutputConnectionRules.target("MQTT_PUBLISH", json("{\"url\":\"http://x\",\"topicTemplate\":\"a/#\"}"), errors);
        assertThat(codes(errors)).contains("target.url:INVALID", "target.topicTemplate:INVALID");
        assertThat(OutputConnectionRules.forbiddenHost("iot-data.java21.net.")).isTrue();
        assertThat(OutputConnectionRules.forbiddenHost("data2flow.java21.net")).isFalse();
    }

    @Test
    @DisplayName("[DSC-04.01][TC-DSC-126] Webhook 대상: 기본값(POST·배치 100·1초·10초), 비밀 헤더는 비밀값으로만, 범위 검사")
    void webhookTarget() {
        List<FieldErrorDetail> errors = new ArrayList<>();
        ObjectNode t = OutputConnectionRules.target("WEBHOOK", json("{\"url\":\"https://hooks.example.com/d2f\",\"headers\":{\"X-Tenant\":\"lab\"}}"),
                errors);
        assertThat(errors).isEmpty();
        assertThat(t.get("method").asString()).isEqualTo("POST");
        assertThat(t.get("batchSize").asInt()).isEqualTo(100);
        assertThat(t.get("batchWaitMs").asInt()).isEqualTo(1000);
        assertThat(t.get("timeoutMs").asInt()).isEqualTo(10000);
        assertThat(t.get("authHeaderName").asString()).isEqualTo("Authorization");
        errors.clear();
        OutputConnectionRules.target("WEBHOOK", json("""
                {"url":"ftp://x","method":"GET","headers":{"Authorization":"Bearer x","bad name":"v"},"batchSize":0,"timeoutMs":100,
                 "batchWaitMs":20000,"authHeaderName":"bad name"}"""), errors);
        assertThat(codes(errors)).contains("target.url:INVALID", "target.method:INVALID", "target.headers.Authorization:USE_SECRET",
                "target.headers.bad name:INVALID", "target.batchSize:Range", "target.timeoutMs:Range", "target.batchWaitMs:Range",
                "target.authHeaderName:Pattern");
        errors.clear();
        OutputConnectionRules.target("WEBHOOK", json("{\"url\":\"https://iot-data.java21.net/hook\"}"), errors);
        assertThat(codes(errors)).containsExactly("target.url:FORBIDDEN_HOST");
        errors.clear();
        OutputConnectionRules.target("WEBHOOK", json("[]"), errors);
        assertThat(codes(errors)).containsExactly("target:Type");
    }

    @Test
    @DisplayName("[DSC-04.01][TC-DSC-126] 필터: ID는 문자열·숫자 모두 받아 숫자로 저장, 응답은 문자열, 측정 항목·최소 품질 검사")
    void filter() {
        List<FieldErrorDetail> errors = new ArrayList<>();
        Filter f = OutputConnectionRules.filter(json("{\"deviceIds\":[\"7\",7,8],\"metrics\":[\"co2\",\"co2\"],\"qualityMin\":0}"), errors);
        assertThat(errors).isEmpty();
        assertThat(f.deviceIds()).containsExactly(7L, 8L);
        assertThat(f.metrics()).containsExactly("co2");
        assertThat(f.toJson(true).get("deviceIds").get(0).asString()).isEqualTo("7");
        assertThat(f.toJson(false).get("deviceIds").get(0).isNumber()).isTrue();
        assertThat(f.toContract().qualityMin()).isZero();
        errors.clear();
        OutputConnectionRules.filter(json("{\"deviceIds\":[\"x\"],\"metrics\":[\"co 2\"],\"qualityMin\":9,\"other\":1}"), errors);
        assertThat(codes(errors)).contains("filter.deviceIds:Type", "filter.metrics:INVALID", "filter.qualityMin:Range", "filter.other:UNKNOWN_FIELD");
        assertThat(OutputConnectionRules.filter(null, errors).deviceIds()).isEmpty();
    }

    @Test
    @DisplayName("[DSC-04.01][TC-DSC-126] 형식·템플릿·비밀값 종류: TEMPLATE은 템플릿 필수, CANONICAL이면 템플릿 버림, 종류가 유형에 맞아야 함, null은 지우기")
    void formatTemplateSecrets() {
        List<FieldErrorDetail> errors = new ArrayList<>();
        assertThat(OutputConnectionRules.format(null, errors)).isEqualTo("CANONICAL");
        assertThat(OutputConnectionRules.template("CANONICAL", json("\"{{value}}\""), errors)).isNull();
        assertThat(OutputConnectionRules.template("TEMPLATE", json("\"{{deviceName}}={{value}}\""), errors)).isEqualTo("{{deviceName}}={{value}}");
        OutputConnectionRules.template("TEMPLATE", null, errors);
        OutputConnectionRules.format(json("\"XML\""), errors);
        OutputConnectionRules.type(json("\"KAFKA\""), errors);
        Map<String, String> s = OutputConnectionRules.secrets("WEBHOOK", json("{\"HMAC_KEY\":\"k\",\"header_value\":null,\"PASSWORD\":\"p\"}"), errors);
        assertThat(s).containsEntry("HMAC_KEY", "k").containsKey("HEADER_VALUE");
        assertThat(s.get("HEADER_VALUE")).isNull();
        assertThat(codes(errors)).contains("template:NotBlank", "format:INVALID", "type:INVALID", "secret.PASSWORD:NOT_FOR_TYPE");
        assertThatThrownBy(() -> OutputConnectionRules.throwIfInvalid(errors)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().code()).isEqualTo("SOURCE_CONFIG_INVALID");
        assertThat(OutputConnectionRules.name(json("\"  출력  \""), new ArrayList<>())).isEqualTo("출력");
    }

    @Test
    @DisplayName("[DSC-04.01][AT-DSC-10.1][TC-DSC-126] 샘플: 필터 metrics=[co2]면 co2만, 남는 것이 없으면 모든 현재값, 현재값이 없으면 빈 값")
    void sample() {
        Map<String, Object> latest = Map.of(
                "co2", Map.of("v", 812, "t", "2026-10-04T00:59:00Z", "q", 0, "unit", "ppm"),
                "temperature", Map.of("v", 23.4, "t", "2026-10-04T00:58:00Z", "q", 0),
                "bad", Map.of("v", "x"));
        Filter co2 = OutputConnectionRules.filter(json("{\"metrics\":[\"co2\"]}"), new ArrayList<>());
        CanonicalTelemetry t = OutputSample.build(1, 2, "dev-1", 3, "ACTIVE", 4L, 5L, latest, co2.toContract(), NOW).orElseThrow();
        assertThat(t.metrics()).extracting(CanonicalTelemetry.Metric::key).containsExactly("co2");
        assertThat(t.metrics().getFirst().unit()).isEqualTo("ppm");
        assertThat(t.measuredAt()).isEqualTo(Instant.parse("2026-10-04T00:59:00Z"));
        assertThat(t.modelId()).isEqualTo("4");
        Filter humidity = OutputConnectionRules.filter(json("{\"metrics\":[\"humidity\"]}"), new ArrayList<>());
        assertThat(OutputSample.build(1, 2, "dev-1", 3, "weird", null, null, latest, humidity.toContract(), NOW).orElseThrow().metrics())
                .hasSize(2);
        assertThat(OutputSample.build(1, 2, "dev-1", 3, null, null, null, Map.of(), null, NOW)).isEmpty();
        assertThat(OutputSample.pathIds("/1/4/9/")).containsExactly(1L, 4L, 9L);
        assertThat(OutputSample.pathIds(null)).isEmpty();
    }
}
