package net.java21.data2flow.core.script;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스크립트 정의·연결·버전 수명 주기(core-api 쪽): SCR-01.01·01.02·02.03·03.04·04.03·04.05 — TC-SCR-002·007·050·055·072·073·079,
 * TC-SCR-033·034의 연결 정책 저장 부분.
 */
class ScriptLifecycleIT extends ScriptItSupport {

    private long org;
    private long integrator;
    private long source;
    private long model;

    @BeforeEach
    void setUp() {
        org = fx.organization("scr");
        integrator = fx.user(org, "lee.integrator", "INTEGRATOR");
        source = source(org, "chirpstack-s3");
        data.metric(org, "temperature", "°C");
        model = data.model(org, "EM300-TH", List.of("temperature"));
    }

    private String createTransform(String name) throws Exception {
        return create(org, integrator, """
                {"name":"%s","kind":"TRANSFORM","bindings":[{"targetType":"MODEL","targetId":"%d"}]}""".formatted(name, model))
                .get("id").asString();
    }

    private JsonNode detail(String id) throws Exception {
        return body(mvc.perform(as(org, integrator, get("/core/scripts/" + id + "?include=versions,usage,config")))
                .andExpect(status().isOk()).andReturn()).get("response");
    }

    private JsonNode save(String id, String code, int baseVersionNo) throws Exception {
        return body(mvc.perform(as(org, integrator, json(put("/core/scripts/" + id + "/draft"),
                        "{\"code\":" + str(code) + ",\"baseVersionNo\":" + baseVersionNo + "}")))
                .andExpect(status().isOk()).andReturn()).get("response");
    }

    private JsonNode deploy(String id, String versionId, String baseActive) throws Exception {
        String base = baseActive == null ? "null" : "\"" + baseActive + "\"";
        return body(mvc.perform(as(org, integrator, json(post("/core/scripts/" + id + "/deploy"),
                        "{\"versionId\":\"" + versionId + "\",\"memo\":\"보정값 반영\",\"baseActiveVersionId\":" + base + "}")))
                .andExpect(status().isOk()).andReturn()).get("response");
    }

    @Test
    @DisplayName("[SCR-01.02][AT-SCR-01.1] INTEGRATOR가 TRANSFORM 생성 → DRAFT v1, 모델 EM300-TH에 연결(배포 전이라 실행 묶음에 없음), 같은 이름 409, 없는 ID 404 — TC-SCR-007")
    void createTransform() throws Exception {
        String body = """
                {"name":"온도 보정","kind":"TRANSFORM","description":"EM300-TH 보정","bindings":[{"targetType":"MODEL","targetId":"%d"}]}"""
                .formatted(model);
        String id = body(mvc.perform(as(org, integrator, json(post("/core/scripts"), body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.startsWith("/api/v1/core/scripts/")))
                .andExpect(jsonPath("$.response.kind").value("TRANSFORM"))
                .andExpect(jsonPath("$.response.status").value("ENABLED"))
                .andExpect(jsonPath("$.response.draftVersionId").isString())
                .andReturn()).get("response").get("id").asString();

        JsonNode d = detail(id);
        assertThat(d.get("draft").get("versionNo").asInt()).isEqualTo(1);
        assertThat(d.get("draft").get("code").asString()).contains("function transform(msg, ctx)");
        assertThat(d.get("draft").get("staticCheck").get("ok").asBoolean()).isTrue();
        assertThat(d.has("activeVersion")).isFalse();
        assertThat(d.get("bindings").get(0).get("targetType").asString()).isEqualTo("MODEL");
        assertThat(d.get("bindings").get(0).get("targetId").asString()).isEqualTo("EM300-TH");
        assertThat(d.get("bindings").get(0).get("targetName").asString()).isEqualTo("EM300-TH");
        assertThat(d.get("bindings").get(0).get("failurePolicy").asString()).isEqualTo("FAIL_OPEN");
        assertThat(d.get("usage").get("bindings").get(0).get("deviceCount").asLong()).isZero();
        assertThat(PIPELINE.received("/check")).hasSize(1);
        assertThat(PIPELINE.received("/check").getFirst().callerService()).isEqualTo("data2flow-core-api");

        mvc.perform(as(org, integrator, get("/core/scripts?kind=TRANSFORM"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].hasDraft").value(true))
                .andExpect(jsonPath("$.responses[0].bindingsSummary.models").value(1))
                .andExpect(jsonPath("$.responses[0].activeVersion").value(Matchers.nullValue()));
        mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + org)).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.scripts.length()").value(0));

        mvc.perform(as(org, integrator, json(post("/core/scripts"), "{\"name\":\"온도 보정\",\"kind\":\"DECODE\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_NAME_DUPLICATED"))
                .andExpect(jsonPath("$.header.resultMessage").value("이미 같은 이름의 스크립트가 있습니다"));
        mvc.perform(as(org, integrator, get("/core/scripts/999999"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SCRIPT_NOT_FOUND"));
        assertThat(auditCount(org, "SCRIPT_CREATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[SCR-01.01][SCR-02.03][AT-SCR-05.1] DECODE는 소스에만(모델 → 400 SCRIPT_BINDING_INVALID), 같은 소스에 두 번째 DECODE → 409 SCRIPT_BINDING_DUPLICATED — TC-SCR-002")
    void decodeBindingRules() throws Exception {
        mvc.perform(as(org, integrator, json(post("/core/scripts"), """
                        {"name":"디코더 A","kind":"DECODE","bindings":[{"targetType":"MODEL","targetId":"EM300-TH"}]}""")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_BINDING_INVALID"));
        String decode = create(org, integrator, """
                {"name":"디코더 A","kind":"DECODE","bindings":[{"targetType":"SOURCE","targetId":"%d","failurePolicy":"FAIL_CLOSED"}]}"""
                .formatted(source)).get("id").asString();
        mvc.perform(as(org, integrator, json(post("/core/scripts"), """
                        {"name":"디코더 B","kind":"DECODE","bindings":[{"targetType":"SOURCE","targetId":"%d"}]}""".formatted(source))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_BINDING_DUPLICATED"));
        String[] invalid = {
                "{\"bindings\":[{\"targetType\":\"SOURCE\",\"targetId\":\"999999\"}]}",
                "{\"bindings\":[{\"targetType\":\"SOURCE\",\"targetId\":\"abc\"}]}",
                "{\"bindings\":[{\"targetType\":\"DEVICE\",\"targetId\":\"1\"}]}"};
        for (String b : invalid) {
            mvc.perform(as(org, integrator, json(put("/core/scripts/" + decode + "/bindings"), b)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_BINDING_INVALID"));
        }
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + decode + "/bindings"),
                        "{\"bindings\":[{\"targetType\":\"PLANET\",\"targetId\":\"1\"}]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + decode + "/bindings"),
                        "{\"bindings\":[{\"targetType\":\"SOURCE\",\"targetId\":\"%d\"},{\"targetType\":\"SOURCE\",\"targetId\":\"%d\"}]}"
                                .formatted(source, source))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_BINDING_DUPLICATED"));
        assertThat(jdbc.sql("SELECT failure_policy FROM data2flow_core.script_bindings WHERE script_id = :s")
                .param("s", Long.parseLong(decode)).query(String.class).single()).isEqualTo("FAIL_CLOSED");
    }

    @Test
    @DisplayName("[SCR-01.02][SCR-02.03][AT-SCR-04.1][AT-SCR-04.3] 연결 교체: TRANSFORM은 모델·기기(소스 400), 기기별 FAIL_CLOSED, 감사·EVT-SCR-01 — TC-SCR-033·034(core)")
    void replaceBindings() throws Exception {
        long site = data.site(org, "본관");
        long device = data.device(org, source, "24e124136d151606", "ACTIVE", site, model);
        String id = createTransform("보정");
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + id + "/bindings"),
                        "{\"bindings\":[{\"targetType\":\"SOURCE\",\"targetId\":\"%d\"}]}".formatted(source))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_BINDING_INVALID"));
        long before = outboxConfigCount(org);
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + id + "/bindings"), """
                        {"bindings":[{"targetType":"MODEL","targetId":"EM300-TH","failurePolicy":"FAIL_OPEN"},
                                     {"targetType":"DEVICE","targetId":"%d","failurePolicy":"FAIL_CLOSED","enabled":false}]}""".formatted(device))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.scriptId").value(id))
                .andExpect(jsonPath("$.response.bindings.length()").value(2))
                .andExpect(jsonPath("$.response.bindings[0].targetType").value("DEVICE"))
                .andExpect(jsonPath("$.response.bindings[0].targetName").value("기기 24e124136d151606"))
                .andExpect(jsonPath("$.response.bindings[0].failurePolicy").value("FAIL_CLOSED"))
                .andExpect(jsonPath("$.response.bindings[0].enabled").value(false))
                .andExpect(jsonPath("$.response.bindings[1].targetId").value("EM300-TH"));
        assertThat(outboxConfigCount(org)).isEqualTo(before + 1);
        assertThat(auditCount(org, "SCRIPT_BINDING_CHANGED")).isEqualTo(1);
        JsonNode d = detail(id);
        assertThat(d.get("usage").get("bindings").get(0).get("deviceCount").asLong()).isEqualTo(1);
        assertThat(d.get("usage").get("bindings").get(1).get("deviceCount").asLong()).isEqualTo(1);
        // 연결이 있으면 삭제할 수 없다(BR-SCR-15)
        mvc.perform(as(org, integrator, delete("/core/scripts/" + id))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("SCRIPT_IN_USE"));
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + id + "/bindings"), "{\"bindings\":[]}"))).andExpect(status().isOk());
        mvc.perform(as(org, integrator, delete("/core/scripts/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, integrator, get("/core/scripts/" + id))).andExpect(status().isNotFound());
        assertThat(auditCount(org, "SCRIPT_DELETED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[SCR-03.04][AT-SCR-03.1][AT-SCR-03.2] DRAFT→ACTIVE 배포 시 이전 ACTIVE→ARCHIVED, 롤백은 같은 행 재활성, 기준 버전 불일치 409 — TC-SCR-050")
    void versionLifecycle() throws Exception {
        String id = createTransform("수명 주기");
        JsonNode d = detail(id);
        String v1 = d.get("draft").get("versionId").asString();
        // DRAFT 다시 저장: 같은 행(v1) 갱신
        JsonNode saved = save(id, TRANSFORM_OK, 1);
        assertThat(saved.get("versionId").asString()).isEqualTo(v1);
        assertThat(saved.get("versionNo").asInt()).isEqualTo(1);
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + id + "/draft"), "{\"code\":" + str(TRANSFORM_OK) + ",\"baseVersionNo\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.header.resultMessage").value("다른 사용자가 먼저 변경했습니다"));

        long configBefore = outboxConfigCount(org);
        JsonNode first = deploy(id, v1, null);
        assertThat(first.get("activeVersionId").asString()).isEqualTo(v1);
        assertThat(first.get("applied").get("reported").asLong()).isZero();
        assertThat(outboxConfigCount(org)).isEqualTo(configBefore + 1);

        // 배포 뒤 저장 → 새 DRAFT v2
        JsonNode v2 = save(id, TRANSFORM_OK.replace("return msg;", "return msg; // v2"), 1);
        assertThat(v2.get("versionNo").asInt()).isEqualTo(2);
        String v2Id = v2.get("versionId").asString();
        // 다른 사용자가 먼저 배포한 상태를 가정: 기준 ACTIVE가 틀림
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + id + "/deploy"),
                        "{\"versionId\":\"" + v2Id + "\",\"memo\":\"동시 배포\",\"baseActiveVersionId\":null}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_VERSION_CONFLICT"));
        deploy(id, v2Id, v1);
        assertThat(versionStatus(v1)).isEqualTo("ARCHIVED");
        assertThat(versionStatus(v2Id)).isEqualTo("ACTIVE");
        // 이미 ACTIVE인 버전을 다시 배포 → 409
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + id + "/deploy"),
                        "{\"versionId\":\"" + v2Id + "\",\"memo\":\"다시 배포\",\"baseActiveVersionId\":\"" + v2Id + "\"}")))
                .andExpect(status().isConflict());
        // 롤백: v1을 다시 ACTIVE로(코드 복사 없이)
        JsonNode rolled = deploy(id, v1, v2Id);
        assertThat(rolled.get("versionNo").asInt()).isEqualTo(1);
        assertThat(versionStatus(v1)).isEqualTo("ACTIVE");
        assertThat(versionStatus(v2Id)).isEqualTo("ARCHIVED");
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.script_versions WHERE script_id = :s").param("s", Long.parseLong(id))
                .query(Long.class).single()).isEqualTo(2);
        assertThat(auditCount(org, "SCRIPT_DEPLOYED")).isEqualTo(2);
        assertThat(auditCount(org, "SCRIPT_ROLLED_BACK")).isEqualTo(1);
        assertThat(auditCount(org, "SCRIPT_VERSION_SAVED")).isEqualTo(2);

        JsonNode after = detail(id);
        assertThat(after.get("activeVersion").get("versionNo").asInt()).isEqualTo(1);
        assertThat(after.get("activeVersion").get("deployMemo").asString()).isEqualTo("보정값 반영");
        assertThat(after.get("versions").size()).isEqualTo(2);
        assertThat(after.get("versions").get(0).get("versionNo").asInt()).isEqualTo(2);
        assertThat(after.get("versions").get(0).get("savedBy").asString()).isEqualTo("이름 lee.integrator");
        assertThat(after.has("draft")).isFalse();
        mvc.perform(as(org, integrator, get("/core/scripts/" + id + "/versions/" + v2Id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.versionNo").value(2))
                .andExpect(jsonPath("$.response.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.response.code").value(Matchers.containsString("// v2")));
        mvc.perform(as(org, integrator, get("/core/scripts/" + id + "/versions/999999"))).andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, get("/core/scripts?keyword=" + "수명"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].activeVersion").value(1))
                .andExpect(jsonPath("$.responses[0].lastDeployedBy").value("이름 lee.integrator"))
                .andExpect(jsonPath("$.responses[0].hasDraft").value(false));
        // 롤백한 버전 코드로 새 DRAFT 만들기(copyFromVersionId)
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + id + "/draft"),
                        "{\"baseVersionNo\":2,\"copyFromVersionId\":\"" + v2Id + "\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.versionNo").value(3));
    }

    private String versionStatus(String versionId) {
        return jdbc.sql("SELECT status FROM data2flow_core.script_versions WHERE id = :id").param("id", Long.parseLong(versionId))
                .query(String.class).single();
    }

    @Test
    @DisplayName("[SCR-04.05][AT-SCR-01.2] 저장 시 pipeline 검사 결과 저장, 검사 오류 상태로 배포 → 400 SCRIPT_STATIC_CHECK_FAILED(ADMIN 강제도 불가) — TC-SCR-079")
    void staticCheckBlocksDeploy() throws Exception {
        long admin = fx.user(org, "boss.admin", "ADMIN");
        String id = createTransform("검사");
        String bad = "function transform(msg, ctx) {\n  // 파일 읽기\n  const fs = require('fs');\n  return msg;\n}\n";
        JsonNode saved = save(id, bad, 1);
        assertThat(saved.get("staticCheck").get("ok").asBoolean()).isFalse();
        JsonNode problem = saved.get("staticCheck").get("problems").get(0);
        assertThat(problem.get("line").asInt()).isEqualTo(3);
        assertThat(problem.get("col").asInt()).isEqualTo(14);
        assertThat(problem.get("message").asString()).isEqualTo("금지된 API: require");
        mvc.perform(as(org, integrator, get("/core/scripts?checkFailed=true"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].checkFailed").value(true));
        String versionId = saved.get("versionId").asString();
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + id + "/deploy"),
                        "{\"versionId\":\"" + versionId + "\",\"memo\":\"배포\",\"baseActiveVersionId\":null}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_STATIC_CHECK_FAILED"));
        mvc.perform(as(org, admin, json(post("/core/scripts/" + id + "/deploy"),
                        "{\"versionId\":\"" + versionId + "\",\"memo\":\"강제\",\"force\":true,\"forceReason\":\"급함\",\"baseActiveVersionId\":null}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_STATIC_CHECK_FAILED"));

        // API-SCR-07 편집 중 검사(pipeline 전달)
        mvc.perform(as(org, integrator, json(post("/core/scripts/check"), "{\"kind\":\"DECODE\",\"code\":\"function decode(i, c) { fetch('x'); }\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.ok").value(false))
                .andExpect(jsonPath("$.response.problems[0].col").value(25));
        // pipeline이 응답하지 않으면: 검사 API 503, 저장은 "검사 못 함" 오류로 저장되어 배포가 막힌다
        PIPELINE.down(true);
        mvc.perform(as(org, integrator, json(post("/core/scripts/check"), "{\"kind\":\"TRANSFORM\",\"code\":\"x\"}")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.header.resultCode").value("SERVICE_UNAVAILABLE"));
        JsonNode offline = save(id, TRANSFORM_OK, 1);
        assertThat(offline.get("staticCheck").get("ok").asBoolean()).isFalse();
        assertThat(offline.get("staticCheck").get("problems").get(0).get("code").asString()).isEqualTo("SCRIPT_CHECK_UNAVAILABLE");
        PIPELINE.down(false);
        JsonNode fixed = save(id, TRANSFORM_OK, 1);
        assertThat(fixed.get("staticCheck").get("ok").asBoolean()).isTrue();
        deploy(id, fixed.get("versionId").asString(), null);
    }

    @Test
    @DisplayName("[SCR-04.03][AT-SCR-03.5] 코드 65,536바이트 저장 성공·65,537바이트(한글 주석 포함 UTF-8) 400 SCRIPT_CODE_TOO_LARGE, 301번째 스크립트 409 SCRIPT_QUOTA_EXCEEDED — TC-SCR-072·073")
    void limits() throws Exception {
        String id = createTransform("한도");
        String head = "function transform(msg, ctx) { return msg; }\n// 한글 주석\n";
        int headBytes = head.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String exact = head + "x".repeat(65536 - headBytes);
        save(id, exact, 1);
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + id + "/draft"), "{\"code\":" + str(exact + "x") + ",\"baseVersionNo\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_CODE_TOO_LARGE"))
                .andExpect(jsonPath("$.header.resultMessage").value("코드가 너무 큽니다(최대 64KB)"));
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + id + "/draft"), "{\"code\":\"x\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        mvc.perform(as(org, integrator, json(post("/core/scripts"), "{\"name\":\"x\",\"kind\":\"TRANSFORM\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"));
        mvc.perform(as(org, integrator, json(post("/core/scripts"), "{\"name\":\"종류 없음\",\"kind\":\"MAGIC\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));

        jdbc.sql("""
                        INSERT INTO data2flow_core.scripts (organization_id, name, kind, created_by, updated_by)
                        SELECT :o, 'bulk-' || g, 'TRANSFORM', :u, :u FROM generate_series(1, 299) g""")
                .param("o", org).param("u", integrator).update();
        mvc.perform(as(org, integrator, get("/core/scripts?size=5"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(300)).andExpect(jsonPath("$.totalPages").value(60));
        mvc.perform(as(org, integrator, json(post("/core/scripts"), "{\"name\":\"301번째\",\"kind\":\"TRANSFORM\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("스크립트를 더 만들 수 없습니다(최대 300개)"));
    }

    @Test
    @DisplayName("[SCR-04.03][AT-SCR-03.5] 51번째 버전 저장 → 가장 오래된 ARCHIVED 1개 정리, 최근 50개 유지, 가장 오래된 ACTIVE는 유지 — TC-SCR-055")
    void versionRetention() throws Exception {
        String id = createTransform("보관");
        long sid = Long.parseLong(id);
        String v1 = detail(id).get("draft").get("versionId").asString();
        deploy(id, v1, null); // v1 ACTIVE(가장 오래된 버전)
        // v2~v50 ARCHIVED를 직접 만든다
        jdbc.sql("""
                        INSERT INTO data2flow_core.script_versions (organization_id, script_id, version_no, code, code_sha256, status,
                                                                    static_check, created_by)
                        SELECT :o, :s, g, 'function transform(m){return m;}', repeat('0', 64), 'ARCHIVED', '{"ok":true,"problems":[]}', :u
                          FROM generate_series(2, 50) g""")
                .param("o", org).param("s", sid).param("u", integrator).update();
        save(id, TRANSFORM_OK, 50); // v51 DRAFT → 51개 → v2(가장 오래된 ARCHIVED) 정리
        List<Integer> numbers = jdbc.sql("SELECT version_no FROM data2flow_core.script_versions WHERE script_id = :s ORDER BY version_no")
                .param("s", sid).query(Integer.class).list();
        assertThat(numbers).hasSize(50).contains(1, 51).doesNotContain(2);
        assertThat(detail(id).get("versions").size()).isEqualTo(50);
    }

    @Test
    @DisplayName("[SCR-01.05][AT-SCR-01.5] 템플릿 목록과 '보정 오프셋'으로 생성 → ctx.device.attributes.tempOffset 코드·설정 기본값, PATCH 이름 변경(baseVersion)")
    void templatesAndPatch() throws Exception {
        mvc.perform(as(org, integrator, get("/core/scripts/templates"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(6));
        mvc.perform(as(org, integrator, get("/core/scripts/templates?kind=DECODE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].key").value("milesight-decoder"));
        String id = create(org, integrator, "{\"name\":\"보정 템플릿\",\"kind\":\"TRANSFORM\",\"templateKey\":\"calibration-offset\"}")
                .get("id").asString();
        JsonNode d = detail(id);
        assertThat(d.get("draft").get("code").asString()).contains("ctx.device.attributes.tempOffset");
        assertThat(d.get("config").get("tempOffset").asInt()).isZero();
        mvc.perform(as(org, integrator, json(post("/core/scripts"), "{\"name\":\"잘못된 템플릿\",\"kind\":\"DECODE\",\"templateKey\":\"calibration-offset\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("templateKey"));

        mvc.perform(as(org, integrator, json(patch("/core/scripts/" + id), "{\"name\":\"보정 템플릿 2\",\"description\":\"설명\",\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.name").value("보정 템플릿 2"))
                .andExpect(jsonPath("$.response.description").value("설명")).andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, integrator, json(patch("/core/scripts/" + id), "{\"name\":\"또\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_VERSION_CONFLICT"));
        mvc.perform(as(org, integrator, json(patch("/core/scripts/" + id), "{\"name\":\"x\",\"baseVersion\":1}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, integrator, json(patch("/core/scripts/" + id), "{\"description\":null}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("baseVersion"));
        createTransform("다른 이름");
        mvc.perform(as(org, integrator, json(patch("/core/scripts/" + id), "{\"name\":\"다른 이름\",\"baseVersion\":1}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_NAME_DUPLICATED"));
        mvc.perform(as(org, integrator, json(patch("/core/scripts/" + id), "{\"description\":\"\",\"baseVersion\":1}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.description").doesNotExist());
        assertThat(auditCount(org, "SCRIPT_UPDATED")).isEqualTo(2);
    }
}
