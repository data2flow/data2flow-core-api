package net.java21.data2flow.core.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSC-01.01·01.02·01.04~01.07 소스 등록·수정·삭제(API-DSC-01~05·07·12·58): 저장·조회 일관성, EVT-DSC-01 발행, 감사, 조직 조건,
 * 오류 경로 — TC-DSC-004·013·028·035·039·043·051
 */
class DataSourceIT extends SourceItSupport {

    @Test
    @DisplayName("[DSC-01.01][AT-DSC-01.4] 생성 201 + Location, 상세는 비밀값 원문 없이 지문만, EVT-DSC-01 SOURCE·감사 SOURCE_CREATED — TC-DSC-004")
    void createAndGet() throws Exception {
        MvcResult r = mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("chirpstack-s3", null))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/core/sources/")))
                .andExpect(jsonPath("$.response.lifecycle").value("DRAFT"))
                .andExpect(jsonPath("$.response.connectorKey").value("mqtt"))
                .andExpect(jsonPath("$.response.connectorVersion").value("1.0.0"))
                .andExpect(jsonPath("$.response.topics[0].topic").value("application/+/device/+/event/up"))
                .andExpect(jsonPath("$.response.secret.kind").value("HEADER_VALUE"))
                .andExpect(jsonPath("$.response.secret.configured").value(true))
                .andExpect(jsonPath("$.response.secret.fingerprint").value(startsWith("••••")))
                .andExpect(jsonPath("$.response.clientIdBase").value("data2flow-chirpstack-s3"))
                .andExpect(jsonPath("$.response.state").value("DISABLED"))
                .andExpect(jsonPath("$.response.version").value(0))
                .andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("s3cr3t-Pa55");
        long id = Long.parseLong(read(r, "$.response.id"));

        mvc.perform(as(org, operator, get("/core/sources/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.code").value("chirpstack-s3"))
                .andExpect(jsonPath("$.response.connection.auth").value("HEADER"))
                .andExpect(jsonPath("$.response.secrets.length()").value(1))
                .andExpect(jsonPath("$.response.decoderKey").value("chirpstack-v4"))
                .andExpect(jsonPath("$.response.unknownDevicePolicy").value("AUTO_REGISTER"))
                .andExpect(jsonPath("$.response.autoregLimitPerHour").value(100))
                .andExpect(jsonPath("$.response.noDataAlarmAfterSec").value(600));

        assertThat(outbox(org, "CONFIG")).hasSize(1).first().asString().contains("\"entityType\":\"SOURCE\"", "\"id\":\"" + id + "\"");
        assertThat(auditCount(org, "SOURCE_CREATED")).isEqualTo(1);
        String audit = jdbc.sql("SELECT detail::text FROM data2flow_core.audit_logs WHERE organization_id = :o AND action = 'SOURCE_CREATED'")
                .param("o", org).query(String.class).single();
        assertThat(audit).doesNotContain("s3cr3t-Pa55").contains("HEADER_VALUE");

        // 다른 조직에서는 없는 것처럼 404
        long other = fx.organization("other");
        long otherAdmin = fx.user(other, "other.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/sources/" + id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.header.resultMessage").value("데이터 소스를 찾을 수 없습니다"));
    }

    @Test
    @DisplayName("[DSC-01.05][BR-DSC-02] 비밀값은 DB에 암호문만(평문 0), 응답·감사에도 평문 없음 — TC-DSC-039 SourceSecretMasking")
    void secretsAreEncrypted() throws Exception {
        long id = createSource(mqttBody("enc", null));
        byte[] stored = jdbc.sql("SELECT ciphertext FROM data2flow_core.source_secrets WHERE source_id = :id").param("id", id)
                .query(byte[].class).single();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("s3cr3t-Pa55").doesNotContain("student");
        String kid = jdbc.sql("SELECT kid FROM data2flow_core.source_secrets WHERE source_id = :id").param("id", id).query(String.class).single();
        assertThat(kid).isEqualTo("test1");
        String all = jdbc.sql("SELECT string_agg(detail::text, ' ') FROM data2flow_core.audit_logs WHERE organization_id = :o")
                .param("o", org).query(String.class).single();
        assertThat(all).doesNotContain("s3cr3t-Pa55");

        // API-DSC-58 종류별 교체: 지문만 돌려준다
        mvc.perform(as(org, integrator, json(put("/core/sources/" + id + "/secrets/header_value"), "{\"value\":\"student:new-Pa55\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.kind").value("HEADER_VALUE"))
                .andExpect(jsonPath("$.response.fingerprint").value(startsWith("••••")))
                .andExpect(jsonPath("$.response.value").doesNotExist());
        // API-DSC-05 교체(인증 방식에 맞지 않는 종류는 거부)
        mvc.perform(as(org, integrator, json(put("/core/sources/" + id + "/secret"), "{\"kind\":\"PASSWORD\",\"value\":\"x\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_AUTH_UNSUPPORTED"));
        mvc.perform(as(org, integrator, json(put("/core/sources/" + id + "/secret"), "{\"kind\":\"HEADER\",\"value\":\"\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_SECRET_REQUIRED"));
        mvc.perform(as(org, integrator, json(put("/core/sources/" + id + "/secret"), "{\"kind\":\"HEADER\",\"value\":\"student:third\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.state").value("DONE"))
                .andExpect(jsonPath("$.response.secrets[0].kind").value("HEADER_VALUE"));
        mvc.perform(as(org, integrator, json(put("/core/sources/" + id + "/secrets/BOGUS"), "{\"value\":\"x\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CONFIG_INVALID"));
        assertThat(auditCount(org, "SOURCE_SECRET_CHANGED")).isEqualTo(2);
        mvc.perform(as(org, operator, get("/core/sources/" + id))).andExpect(jsonPath("$.response.version").value(2));
    }

    @Test
    @DisplayName("[DSC-01.04][AT-DSC-01.5][BR-DSC-01] client-id base 중복 409, 비우면 data2flow-{code}, 코드 중복 409 — TC-DSC-025·028")
    void clientIdAndCodeDuplicates() throws Exception {
        createSource(mqttBody("first", null).replace("\"auth\":\"HEADER\"", "\"clientIdBase\":\"data2flow-x\",\"auth\":\"HEADER\""));
        mvc.perform(as(org, integrator, json(post("/core/sources"),
                        mqttBody("second", null).replace("\"auth\":\"HEADER\"", "\"clientIdBase\":\"data2flow-x\",\"auth\":\"HEADER\""))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CLIENT_ID_DUPLICATE"));
        // 기본 base data2flow-first와 겹치게
        mvc.perform(as(org, integrator, json(post("/core/sources"),
                        mqttBody("third", null).replace("\"auth\":\"HEADER\"", "\"clientIdBase\":\"data2flow-x\",\"auth\":\"HEADER\""))))
                .andExpect(status().isConflict());
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("first", null))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CODE_DUPLICATE"));
        mvc.perform(as(org, integrator, json(post("/core/sources"),
                        mqttBody("upper", null).replace("\"auth\":\"HEADER\"", "\"clientIdBase\":\"Data2Flow_X\",\"auth\":\"HEADER\""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("connection.clientIdBase"));
    }

    @Test
    @DisplayName("[DSC-01.04][BR-DSC-06] MQTT 옵션 검증: 주소·토픽 형식·QoS·keepalive·모르는 키·비밀값 키 400, 토픽 21개 429 — TC-DSC-026·028")
    void mqttValidation() throws Exception {
        String[][] cases = {
                {"\"url\":\"wss://broker.test:443/mqtt\"", "\"url\":\"http://x\"", "connection.url"},
                {"\"keepaliveSec\":60", "\"keepaliveSec\":5", "connection.keepaliveSec"},
                {"\"qos\":1,", "\"qos\":3,", "connection.qos"},
                {"\"cleanStart\":false", "\"cleanStart\":false,\"bogus\":1", "connection.bogus"},
                {"\"cleanStart\":false", "\"cleanStart\":false,\"password\":\"x\"", "connection.password"},
                {"\"application/+/device/+/event/up\",\"qos\":1", "\"a/#/b\",\"qos\":1", "topics[0].topic"},
                {"\"application/+/device/+/event/up\",\"qos\":1", "\"a/b+\",\"qos\":1", "topics[0].topic"},
                {"\"application/+/device/+/event/up\",\"qos\":1", "\"a/b\",\"qos\":5", "topics[0].qos"},
                {"\"protocolVersion\":\"5.0\"", "\"protocolVersion\":\"4\"", "connection.protocolVersion"},
                {"\"protocolVersion\":\"5.0\"", "\"protocolVersion\":\"3.1.1\",\"sharedGroup\":\"g1\"", "connection.sharedGroup"},
        };
        for (String[] c : cases) {
            mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("bad", null).replace(c[0], c[1]))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CONFIG_INVALID"))
                    .andExpect(jsonPath("$.errors[0].field").value(c[2]));
        }
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("bad", null).replace("\"topics\":[{\"topic\":\"application/+/device/+/event/up\",\"qos\":1}]", "\"topics\":[]"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("topics"));
        StringBuilder topics = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            topics.append(i == 0 ? "" : ",").append("{\"topic\":\"t/").append(i).append("\",\"qos\":1}");
        }
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("many", null)
                        .replace("[{\"topic\":\"application/+/device/+/event/up\",\"qos\":1}]", "[" + topics + "]"))))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_LIMIT_EXCEEDED"));
        // HTTP 헤더 인증은 ws·wss에서만
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("tcp", null).replace("wss://broker.test:443/mqtt", "tcp://broker.test:1883"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_AUTH_UNSUPPORTED"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("au", null).replace("\"auth\":\"HEADER\"", "\"auth\":\"KERBEROS\""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_AUTH_UNSUPPORTED"));
        // 운영 소스는 TLS 검증을 끌 수 없다(BR-DSC-29), 개발 소스는 허용
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("tls", null).replace("\"cleanStart\":false", "\"cleanStart\":false,\"tlsInsecure\":true"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_TLS_VERIFY_REQUIRED"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("tls-dev", "\"isDev\":true").replace("\"cleanStart\":false", "\"cleanStart\":false,\"tlsInsecure\":true"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.isDev").value(true));
        // 기본 필드: 코드 형식, 이름, 유형
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("Bad_Code", null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("code"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), "{\"code\":\"x1\",\"name\":\"\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("wh", null).replace("MQTT_SUBSCRIBE", "WEBHOOK"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("type"));
    }

    @Test
    @DisplayName("[DSC-01.05] 인증 방식별 비밀값: USERPASS 비밀번호·사용자 이름, mTLS PEM 나누기, 활성화 시 없으면 SOURCE_SECRET_REQUIRED — TC-DSC-032·035")
    void authMethods() throws Exception {
        String userpass = mqttBody("up", null).replace("wss://broker.test:443/mqtt", "ssl://broker.test:8883")
                .replace("\"auth\":\"HEADER\",\"headerName\":\"Authorization\"", "\"auth\":\"USERPASS\",\"username\":\"svc\"")
                .replace("{\"kind\":\"HEADER\",\"value\":\"student:s3cr3t-Pa55\"}", "{\"kind\":\"USERPASS\",\"value\":\"pw-123\"}");
        long up = createSource(userpass);
        mvc.perform(as(org, operator, get("/core/sources/" + up))).andExpect(jsonPath("$.response.secret.kind").value("PASSWORD"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), userpass.replace("\"code\":\"up\"", "\"code\":\"up2\"")
                        .replace(",\"username\":\"svc\"", ""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("connection.username"));
        // 비밀값 없이 바로 활성화하면 거부
        mvc.perform(as(org, integrator, json(post("/core/sources"), userpass.replace("\"code\":\"up\"", "\"code\":\"up3\"")
                        .replace("\"secret\":{\"kind\":\"USERPASS\",\"value\":\"pw-123\"},", "").replace("\"decoderKey\"", "\"activate\":true,\"decoderKey\""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_SECRET_REQUIRED"));

        String pem = "-----BEGIN CERTIFICATE-----\\nMIIBcert\\n-----END CERTIFICATE-----\\n-----BEGIN PRIVATE KEY-----\\nMIIEkey\\n-----END PRIVATE KEY-----";
        String mtls = mqttBody("mtls", null).replace("wss://broker.test:443/mqtt", "ssl://broker.test:8883")
                .replace("\"auth\":\"HEADER\",\"headerName\":\"Authorization\"", "\"auth\":\"MTLS\"")
                .replace("{\"kind\":\"HEADER\",\"value\":\"student:s3cr3t-Pa55\"}", "{\"kind\":\"MTLS\",\"value\":\"" + pem + "\"}");
        long m = createSource(mtls);
        mvc.perform(as(org, operator, get("/core/sources/" + m))).andExpect(jsonPath("$.response.secrets.length()").value(2))
                .andExpect(jsonPath("$.response.secret.kind").value("CLIENT_CERT"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), mtls.replace("\"code\":\"mtls\"", "\"code\":\"mtls2\"").replace(pem, "not-a-pem"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("secret.value"));
        // {cert, key, ca} 모양
        mvc.perform(as(org, integrator, json(post("/core/sources"), mtls.replace("\"code\":\"mtls\"", "\"code\":\"mtls3\"")
                        .replace("{\"kind\":\"MTLS\",\"value\":\"" + pem + "\"}", "{\"cert\":\"C\",\"key\":\"K\",\"ca\":\"A\"}"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.secrets.length()").value(3));
        // 수정에서 인증 방식을 NONE으로 바꾸면 쓰지 않는 비밀값은 지운다
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + m), "{\"connection\":{\"url\":\"ssl://broker.test:8883\",\"auth\":\"NONE\"},\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.secrets.length()").value(0))
                .andExpect(jsonPath("$.response.secret.configured").value(false));
        assertThat(auditCount(org, "SOURCE_SECRET_CHANGED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DSC-01.06][AT-DSC-02.2][BR-DSC-03] 디코더: generic-json 매핑 검증(32KB·JSONPath·키 중복), script는 DECODE 스크립트만 — TC-DSC-040·043")
    void decoders() throws Exception {
        String generic = mqttBody("gj", null).replace("\"decoderKey\":\"chirpstack-v4\"",
                "\"decoderKey\":\"generic-json\",\"decoderConfig\":{\"deviceIdFrom\":\"topic[1]\",\"timePath\":\"$.ts\",\"timeFormat\":\"EPOCH_MS\",\"metrics\":[{\"path\":\"$.temp\",\"key\":\"temperature\",\"unit\":\"°C\"},{\"path\":\"$.sensors[*].v\",\"key\":\"TVOC\"}]}");
        long id = createSource(generic);
        mvc.perform(as(org, operator, get("/core/sources/" + id))).andExpect(jsonPath("$.response.decoderConfig.metrics[1].key").value("TVOC"));
        String[][] bad = {
                {"\"deviceIdFrom\":\"topic[1]\"", "\"deviceIdFrom\":\"dev\"", "decoderConfig.deviceIdFrom"},
                {"\"timePath\":\"$.ts\"", "\"timePath\":\"$..ts\"", "decoderConfig.timePath"},
                {"\"key\":\"TVOC\"", "\"key\":\"temperature\"", "decoderConfig.metrics[1].key"},
                {"\"key\":\"TVOC\"", "\"key\":\"1x\"", "decoderConfig.metrics[1].key"},
                {"\"timeFormat\":\"EPOCH_MS\"", "\"timeFormat\":\"LOCAL\"", "decoderConfig.timeFormat"},
                {"\"timeFormat\":\"EPOCH_MS\"", "\"timeFormat\":\"EPOCH_MS\",\"extra\":1", "decoderConfig.extra"},
        };
        for (String[] c : bad) {
            mvc.perform(as(org, integrator, json(post("/core/sources"), generic.replace("\"code\":\"gj\"", "\"code\":\"gj2\"").replace(c[0], c[1]))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value(c[2]));
        }
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("gj3", null).replace("\"decoderKey\":\"chirpstack-v4\"", "\"decoderKey\":\"generic-json\""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("decoderConfig"));
        String huge = "x".repeat(33 * 1024);
        mvc.perform(as(org, integrator, json(post("/core/sources"), generic.replace("\"code\":\"gj\"", "\"code\":\"gj4\"").replace("°C", huge))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("MAX_SIZE"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("dk", null).replace("chirpstack-v4", "protobuf"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("decoderKey"));
        // single-value: 설정은 선택, 토픽 위치만
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("sv", null).replace("\"decoderKey\":\"chirpstack-v4\"",
                        "\"decoderKey\":\"single-value\",\"decoderConfig\":{\"deviceIdFrom\":\"topic[1]\",\"metricFrom\":\"$.x\"}"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("decoderConfig.metricFrom"));

        // 스크립트 디코더: 없거나 TRANSFORM이면 SCRIPT_NOT_FOUND
        long decode = script(org, "decode-a", "DECODE");
        long transform = script(org, "transform-a", "TRANSFORM");
        String scriptBody = mqttBody("sc", null).replace("\"decoderKey\":\"chirpstack-v4\"", "\"decoderKey\":\"script\",\"decodeScriptId\":\"%d\"");
        mvc.perform(as(org, integrator, json(post("/core/sources"), scriptBody.formatted(transform))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_NOT_FOUND"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), scriptBody.formatted(999999))))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("sc0", null).replace("\"decoderKey\":\"chirpstack-v4\"", "\"decoderKey\":\"script\""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("decodeScriptId"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), scriptBody.formatted(decode))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.decodeScriptId").value(Long.toString(decode)));

        // 디코더 변경(PATCH): 다음 메시지부터 — 설정 변경 이벤트로 pipeline이 다시 읽는다
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"decoderKey\":\"chirpstack-v4\",\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.decoderKey").value("chirpstack-v4"))
                .andExpect(jsonPath("$.response.decoderConfig").doesNotExist());
        assertThat(outbox(org, "CONFIG")).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("[DSC-01.07] 기본 공간·기본 모델: 있는 것만, 공간 범위 밖이면 400, null로 비우기, 범위 검증 — TC-DSC-048·051")
    void defaults() throws Exception {
        long site = data.site(org, "본관");
        long room = data.space(org, site, "ROOM", "실습실");
        long otherSite = data.site(org, "별관");
        data.metric(org, "temperature", "°C");
        long model = data.model(org, "EM300-TH", List.of("temperature"));
        long id = createSource(mqttBody("df", "\"defaultModelId\":\"%d\",\"defaultSpaceId\":%d,\"unknownDevicePolicy\":\"REJECT\",\"autoregLimitPerHour\":0,\"noDataAlarmAfterSec\":60"
                .formatted(model, room)));
        mvc.perform(as(org, operator, get("/core/sources/" + id)))
                .andExpect(jsonPath("$.response.defaultModelId").value(Long.toString(model)))
                .andExpect(jsonPath("$.response.defaultSpaceId").value(Long.toString(room)))
                .andExpect(jsonPath("$.response.unknownDevicePolicy").value("REJECT"))
                .andExpect(jsonPath("$.response.autoregLimitPerHour").value(0));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"defaultModelId\":\"424242\",\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("defaultModelId"));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"defaultSpaceId\":\"abc\",\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("defaultSpaceId"));
        String[] ranges = {"\"autoregLimitPerHour\":10001", "\"noDataAlarmAfterSec\":59", "\"unknownDevicePolicy\":\"IGNORE\""};
        for (String r : ranges) {
            mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{" + r + ",\"baseVersion\":0}"))).andExpect(status().isBadRequest());
        }
        // 공간 범위가 본관으로 좁혀진 통합 담당은 별관을 기본 공간으로 고를 수 없다(존재를 숨김)
        data.spaceScope(org, integrator, List.of(site));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"defaultSpaceId\":%d,\"baseVersion\":0}".formatted(otherSite))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("defaultSpaceId"));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"defaultSpaceId\":null,\"defaultModelId\":\"\",\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.defaultSpaceId").doesNotExist())
                .andExpect(jsonPath("$.response.defaultModelId").doesNotExist()).andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("[DSC-01.01] PATCH: 온 키만, 코드·유형 변경 불가, baseVersion 충돌 409, 토픽 교체, ACTIVE면 필수 설정 유지 — TC-DSC-002·004")
    void patchRules() throws Exception {
        long id = createSource(mqttBody("pt", null));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"code\":\"other\",\"type\":\"SIMULATION\",\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"connectorKey\":\"kafka\",\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("connectorKey"));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"name\":\"x\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("baseVersion"));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"name\":\"x\",\"baseVersion\":7}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"weird\":1,\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("weird"));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id),
                        "{\"name\":\"이름 바꿈\",\"code\":\"pt\",\"connectorKey\":\"mqtt\",\"topics\":[{\"topic\":\"a/+\",\"qos\":0},{\"topic\":\"b/#\"}],\"secret\":{\"kind\":\"HEADER\",\"value\":\"\"},\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.name").value("이름 바꿈"))
                .andExpect(jsonPath("$.response.topics.length()").value(2)).andExpect(jsonPath("$.response.topics[1].qos").value(1))
                .andExpect(jsonPath("$.response.secret.configured").value(true)).andExpect(jsonPath("$.response.version").value(1));
        assertThat(auditCount(org, "SOURCE_UPDATED")).isEqualTo(1);
        assertThat(auditCount(org, "SOURCE_SECRET_CHANGED")).isZero();
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"isDev\":true,\"connection\":{\"url\":\"wss://b.test/mqtt\",\"auth\":\"HEADER\",\"tlsInsecure\":true},\"baseVersion\":1}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"isDev\":false,\"baseVersion\":2}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_TLS_VERIFY_REQUIRED"));
        mvc.perform(as(org, integrator, json(patch("/core/sources/404404"), "{\"name\":\"x\",\"baseVersion\":0}"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DSC-01.01][AT-DSC-03.2][BR-DSC-04] 삭제: 기기 9대면 409 SOURCE_IN_USE(n=9), ACTIVE면 409, DRAFT 0대면 204 + 감사 + 설정 삭제 — TC-DSC-001·004")
    void deleteRules() throws Exception {
        long used = createSource(mqttBody("used", null));
        for (int i = 0; i < 9; i++) {
            data.device(org, used, "dev-" + i, "ACTIVE", null, null);
        }
        mvc.perform(as(org, integrator, delete("/core/sources/" + used))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("SOURCE_IN_USE"))
                .andExpect(jsonPath("$.header.resultMessage").value("이 소스에 연결된 기기가 9대 있습니다"));
        long active = createSource(mqttBody("act", "\"activate\":true"));
        mvc.perform(as(org, integrator, delete("/core/sources/" + active))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("SOURCE_STATE_CONFLICT"));
        long draft = createSource(mqttBody("draft", null));
        mvc.perform(as(org, integrator, delete("/core/sources/" + draft))).andExpect(status().isNoContent());
        mvc.perform(as(org, operator, get("/core/sources/" + draft))).andExpect(status().isNotFound());
        assertThat(auditCount(org, "SOURCE_DELETED")).isEqualTo(1);
        assertThat(outbox(org, "CONFIG")).anySatisfy(p -> assertThat(p).contains("\"op\":\"DELETE\"", "\"id\":\"" + draft + "\""));
    }

    @Test
    @DisplayName("[DSC-07.05][BR-DSC-11] 복제: 비밀값·client-id base 없이 DRAFT, 토픽·디코더는 그대로, 코드 중복 409")
    void cloneSource() throws Exception {
        long id = createSource(mqttBody("orig", "\"activate\":true").replace("\"auth\":\"HEADER\"", "\"clientIdBase\":\"data2flow-orig-x\",\"auth\":\"HEADER\""));
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/clone"), "{\"code\":\"copy\",\"name\":\"복사본\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.lifecycle").value("DRAFT"))
                .andExpect(jsonPath("$.response.secrets.length()").value(0))
                .andExpect(jsonPath("$.response.topics[0].topic").value("application/+/device/+/event/up"))
                .andExpect(jsonPath("$.response.clientIdBase").value("data2flow-copy"));
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/clone"), "{\"code\":\"copy\",\"name\":\"복사본\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CODE_DUPLICATE"));
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/clone"), "{\"code\":\"C\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    @DisplayName("[DSC-01.02] 기본 유형 3개: 플랫폼 브로커·가상 환경은 토픽·비밀값 없이 생성·활성화, 목록 유형·상태 필터 — TC-DSC-010·013·016")
    void basicTypes() throws Exception {
        long pb = createSource("""
                {"code":"platform","name":"플랫폼 브로커","type":"PLATFORM_BROKER","connection":{"deviceKeyPattern":"{externalId}",
                 "allowedFormats":["CANONICAL"]},"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"topic[1]",
                 "metrics":[{"path":"$.temp","key":"temperature"}]},"activate":true}""");
        long sim = createSource("""
                {"code":"sim","name":"가상","type":"SIMULATION","connection":{"scenarioId":"lab-1"},"decoderKey":"generic-json",
                 "decoderConfig":{"deviceIdFrom":"$.dev","metrics":[{"path":"$.v","key":"co2"}]}}""");
        createSource(mqttBody("mq", null));
        mvc.perform(as(org, integrator, json(post("/core/sources"), """
                        {"code":"pb2","name":"x","type":"PLATFORM_BROKER","connection":{"deviceKeyPattern":"fixed"},"decoderKey":"chirpstack-v4"}""")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("connection.deviceKeyPattern"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), """
                        {"code":"pb3","name":"x","type":"PLATFORM_BROKER","connection":{"allowedFormats":["XML"]},"decoderKey":"chirpstack-v4","secret":{"kind":"PASSWORD","value":"x"}}""")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("connection.allowedFormats"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), """
                        {"code":"pb4","name":"x","type":"PLATFORM_BROKER","decoderKey":"chirpstack-v4","secret":{"kind":"PASSWORD","value":"x"}}""")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_AUTH_UNSUPPORTED"));

        mvc.perform(as(org, operator, get("/core/sources").param("type", "SIMULATION"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(Long.toString(sim)))
                .andExpect(jsonPath("$.responses[0].state").value("DISABLED"));
        mvc.perform(as(org, operator, get("/core/sources").param("type", "PLATFORM_BROKER,MQTT_SUBSCRIBE"))).andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(as(org, operator, get("/core/sources").param("state", "CONNECTING")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(Long.toString(pb)));
        mvc.perform(as(org, operator, get("/core/sources").param("q", "가상"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, operator, get("/core/sources").param("lifecycle", "ACTIVE"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, operator, get("/core/sources").param("lifecycle", "GONE"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, get("/core/sources").param("state", "FINE"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, get("/core/sources").param("size", "2"))).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.responses[0].rateSeries.length()").value(60));
    }

    private long script(long orgId, String name, String kind) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.scripts (organization_id, name, kind, created_by, updated_by)
                        VALUES (:org, :name, :kind, 0, 0) RETURNING id""")
                .param("org", orgId).param("name", name).param("kind", kind).query(Long.class).single();
    }
}
