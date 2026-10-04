package net.java21.data2flow.core.rule;

import net.java21.data2flow.contracts.alarm.AlarmKeys;
import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import net.java21.data2flow.contracts.alarm.AlarmSourceType;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.AlarmSignal;
import net.java21.data2flow.core.alarm.AlarmItSupport;
import net.java21.data2flow.core.rule.service.RuleHealthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuleLifecycleIT extends AlarmItSupport {

    @Autowired
    RuleHealthService health;

    String rule(String name, long spaceId) {
        return """
                {"name":"%s","templateKey":"high-co2","scope":{"type":"SPACE","ids":["%d"],"includeChildren":true},
                 "condition":{"kind":"threshold","metric":"co2","op":">","value":1000,"for":"PT5M","clear":900},
                 "severity":"MAJOR","titleTemplate":"{{space.name}} CO2 {{value}}ppm","autoClear":true}""".formatted(name, spaceId);
    }

    String create(String body) throws Exception {
        MvcResult r = mvc.perform(as(org, operator, json(post("/core/rules"), body))).andExpect(status().isCreated()).andReturn();
        return read(r, "$.response.ruleId");
    }

    @Test
    @DisplayName("[RUL-01.01][TC-RUL-004][TC-RUL-025] 규칙 저장 → 내부 플로우(kind=RULE) v1 ACTIVE·설정 변경 FLOW, 수정하면 v2로 원자 전환(규칙 버전 = 플로우 버전)")
    void createCompilesAndApplies() throws Exception {
        MvcResult r = mvc.perform(as(org, operator, json(post("/core/rules"), rule("실습실 고CO2", building))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.status").value("ACTIVE"))
                .andExpect(jsonPath("$.response.version").value(1)).andExpect(jsonPath("$.response.targetCount").value(3)).andReturn();
        String ruleId = read(r, "$.response.ruleId");
        String flowId = read(r, "$.response.flowId");
        var flow = jdbc.sql("SELECT kind, status, active_version, source_rule_id FROM data2flow_core.flows WHERE id = CAST(:id AS uuid)")
                .param("id", flowId).query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4)}).single();
        assertThat(flow).containsExactly("RULE", "ACTIVE", 1, Long.parseLong(ruleId));
        assertThat(configMessages(org)).anyMatch(m -> m.contains("\"FLOW\"") && m.contains(flowId));
        String def = jdbc.sql("SELECT definition::text FROM data2flow_core.flow_versions WHERE flow_id = CAST(:id AS uuid) AND version_no = 1")
                .param("id", flowId).query(String.class).single();
        assertThat(def).contains("action.alarm").contains("\"ruleId\": \"" + ruleId + "\"");
        // 컴파일은 flow-engine(API-FLW-86, ADR-051)
        assertThat(STUB.received("POST", "/internal/flow/rules/compile")).hasSize(1).first()
                .satisfies(c -> assertThat(c.body()).contains("\"ruleId\":\"" + ruleId + "\"").contains("\"metric\":\"co2\""));
        mvc.perform(as(org, operator, json(put("/core/rules/" + ruleId), rule("실습실 고CO2", building)
                        .replace("\"value\":1000", "\"value\":1200").replace("}\n", ",\"baseVersion\":1}\n").replace("\"autoClear\":true}",
                                "\"autoClear\":true,\"baseVersion\":1}"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(2));
        assertThat(jdbc.sql("SELECT active_version FROM data2flow_core.flows WHERE id = CAST(:id AS uuid)").param("id", flowId)
                .query(Integer.class).single()).isEqualTo(2);
        mvc.perform(as(org, operator, json(put("/core/rules/" + ruleId), rule("실습실 고CO2", building).replace("\"autoClear\":true}",
                        "\"autoClear\":true,\"baseVersion\":1}")))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, analyst, get("/core/rules/" + ruleId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.conditionSummary").value("co2 > 1200 (5m)"))
                .andExpect(jsonPath("$.response.flowId").value(flowId));
        mvc.perform(as(org, analyst, get("/core/rules"))).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.counts.limit").value(1000))
                .andExpect(jsonPath("$.responses[0].scope.targetCount").value(3));
    }

    @Test
    @DisplayName("[RUL-01.10][TC-RUL-026][TC-RUL-014][TC-RUL-112][TC-RUL-116] 이름 중복 409, 조건 오류 400 RULE_CONDITION_INVALID, 없는 규칙 404, 템플릿 7종")
    void validationErrors() throws Exception {
        create(rule("고CO2", lab));
        mvc.perform(as(org, operator, json(post("/core/rules"), rule("고CO2", lab)))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("RULE_NAME_DUPLICATED"))
                .andExpect(jsonPath("$.header.resultMessage").value("같은 이름의 규칙이 있습니다"));
        mvc.perform(as(org, operator, json(post("/core/rules"), rule("x", lab).replace("\"clear\":900", "\"clear\":1100"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("RULE_CONDITION_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("조건이 올바르지 않습니다: condition.clear 해제 기준은 발생 기준보다 덜 엄격해야 합니다"))
                .andExpect(jsonPath("$.errors[0].field").value("condition.clear"));
        mvc.perform(as(org, operator, get("/core/rules/999999"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("RULE_NOT_FOUND"))
                .andExpect(jsonPath("$.header.resultMessage").value("규칙을 찾을 수 없습니다"));
        mvc.perform(as(org, viewer, json(post("/core/rules"), rule("y", lab)))).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, get("/core/rule-templates"))).andExpect(jsonPath("$.totalCount").value(7))
                .andExpect(jsonPath("$.responses[0].key").value("high-co2"))
                .andExpect(jsonPath("$.responses[0].requiredMetrics[0]").value("co2"));
        // 범위 밖·다른 조직 공간은 404
        mvc.perform(as(org, operator, json(post("/core/rules"), rule("z", 999999)))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[RUL-01.01][TC-RUL-026] 엔진이 컴파일을 거절하면(400 RULE_CONDITION_INVALID) 그 오류를 그대로 돌려주고 규칙은 저장되지 않는다, 엔진 장애는 503")
    void engineRejects() throws Exception {
        STUB.fail("POST", "/internal/flow/rules/compile", 400, "RULE_CONDITION_INVALID",
                "\"errors\":[{\"field\":\"condition.metric\",\"code\":\"UNSUPPORTED\",\"message\":null}]");
        mvc.perform(as(org, operator, json(post("/core/rules"), rule("거절", lab)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("RULE_CONDITION_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("condition.metric"));
        assertThat(count("SELECT count(*) FROM data2flow_core.rules WHERE organization_id = :org")).isZero();
        STUB.fail("POST", "/internal/flow/rules/compile", 500, "INTERNAL_ERROR", null);
        mvc.perform(as(org, operator, json(post("/core/rules"), rule("장애", lab)))).andExpect(status().isServiceUnavailable());
        assertThat(count("SELECT count(*) FROM data2flow_core.flows WHERE organization_id = :org")).isZero();
    }

    @Test
    @DisplayName("[RUL-06.03][TC-RUL-105][TC-RUL-023][TC-RUL-110] 범위에 co2 기기가 없으면 ERROR(NO_TARGET), 기기가 들어오면 점검이 ACTIVE로(자동 포함)")
    void noTargetThenRecovered() throws Exception {
        long empty = data.space(org, building, "ROOM", "빈 방");
        MvcResult r = mvc.perform(as(org, operator, json(post("/core/rules"), rule("빈 방 CO2", empty)))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.status").value("ERROR")).andExpect(jsonPath("$.response.errorReason").value("NO_TARGET")).andReturn();
        String ruleId = read(r, "$.response.ruleId");
        mvc.perform(as(org, analyst, get("/core/rules"))).andExpect(jsonPath("$.responses[0].status").value("ERROR"));
        data.device(org, source, "co2-new", "ACTIVE", empty, co2Model);
        health.check(org);
        assertThat(jdbc.sql("SELECT status FROM data2flow_core.rules WHERE id = :id").param("id", Long.parseLong(ruleId)).query(String.class)
                .single()).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key = 'system:RULE_ERROR:" + ruleId
                + "' AND status = 'CLEARED'")).isZero();
        // 기기를 지우면 다시 ERROR + 시스템 알람
        jdbc.sql("UPDATE data2flow_core.devices SET status = 'INACTIVE' WHERE organization_id = :org AND space_id = :s").param("org", org)
                .param("s", empty).update();
        health.check(org);
        assertThat(count("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key = 'system:RULE_ERROR:" + ruleId
                + "' AND status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("[RUL-06.03][TC-RUL-108][TC-RUL-109][TC-RUL-111][TC-RUL-113] 비활성화(열린 알람 유지)·활성화, 플로우로 변환(CONVERTED는 수정 409), 삭제는 열린 알람 RULE_DELETED")
    void lifecycle() throws Exception {
        String ruleId = create(rule("고CO2", lab));
        String flowId = jdbc.sql("SELECT flow_id::text FROM data2flow_core.rules WHERE id = :id").param("id", Long.parseLong(ruleId))
                .query(String.class).single();
        signal(ruleId, sensor1, 1100);
        mvc.perform(as(org, operator, post("/core/rules/" + ruleId + "/deactivate"))).andExpect(jsonPath("$.response.status").value("INACTIVE"));
        assertThat(count("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org AND status = 'ACTIVE'")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM data2flow_core.flows WHERE id = CAST(:id AS uuid)").param("id", flowId).query(String.class).single())
                .isEqualTo("DISABLED");
        signal(ruleId, sensor2, 1100);
        assertThat(count("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org")).isEqualTo(1);
        mvc.perform(as(org, operator, post("/core/rules/" + ruleId + "/activate"))).andExpect(jsonPath("$.response.status").value("ACTIVE"));
        mvc.perform(as(org, operator, post("/core/rules/" + ruleId + "/activate"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("RULE_STATE_CONFLICT"));
        String other = create(rule("고CO2-2", lab));
        mvc.perform(as(org, operator, post("/core/rules/" + other + "/convert-to-flow"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.flowId").exists());
        mvc.perform(as(org, operator, json(put("/core/rules/" + other), rule("고CO2-2", lab).replace("\"autoClear\":true}",
                        "\"autoClear\":true,\"baseVersion\":1}")))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("RULE_STATE_CONFLICT"))
                .andExpect(jsonPath("$.header.resultMessage").value("지금 상태에서는 할 수 없습니다"));
        mvc.perform(as(org, analyst, get("/core/rules"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, operator, delete("/core/rules/" + ruleId))).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT clear_reason FROM data2flow_core.alarms WHERE organization_id = :org").param("org", org)
                .query(String.class).single()).isEqualTo("RULE_DELETED");
        mvc.perform(as(org, operator, get("/core/rules/" + ruleId))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[RUL-01.01][TC-RUL-006] 규칙 알람 신호 → 제목은 규칙 템플릿으로({{space.name}}·{{value}}), 심각도는 규칙 값, autoClear 꺼지면 CLEAR 무시")
    void ruleSignalTitle() throws Exception {
        String ruleId = create(rule("고CO2", lab).replace("\"autoClear\":true", "\"autoClear\":false"));
        signal(ruleId, sensor1, 1050);
        var row = jdbc.sql("SELECT title, severity, rule_id FROM data2flow_core.alarms WHERE organization_id = :org").param("org", org)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3)}).single();
        assertThat(row).containsExactly("실습실 CO2 1050.0ppm", "MAJOR", Long.parseLong(ruleId));
        AlarmSignal clear = AlarmSignal.clear(AlarmKeys.rule(Long.parseLong(ruleId), Long.toString(sensor1)), AlarmSourceType.RULE,
                Long.parseLong(ruleId), UUID.randomUUID().toString(), 1, "n-alarm-clear", sensor1, null, "co2", 800.0, clock.instant(), "m");
        deliver(EventType.ALARM_SIGNAL, org, clear, clock.instant());
        assertThat(count("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org AND status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("[RUL-01.11][TC-RUL-031][TC-RUL-032] 과거 7일 시뮬레이션: 예상 알람 수·기기별·히트맵, 알람·알림 행 0건, 31일은 400 RULE_SIMULATION_RANGE_INVALID")
    void simulation() throws Exception {
        Instant t = clock.instant().minus(Duration.ofDays(1));
        for (int i = 0; i < 10; i++) {
            data.telemetry(org, sensor1, "co2", t.plus(Duration.ofMinutes(i)), i < 7 ? 1100 : 800, 0, false);
        }
        String body = "{\"rule\":" + rule("시뮬", lab) + "}";
        mvc.perform(as(org, operator, json(post("/core/rules/simulate"), body))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.alarms").value(1)).andExpect(jsonPath("$.response.byDevice[0].deviceId").value(Long.toString(sensor1)))
                .andExpect(jsonPath("$.response.byDevice[0].longestSec").value(120))
                .andExpect(jsonPath("$.response.coverage.dataRatio").value(0.5));
        assertThat(count("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org")).isZero();
        assertThat(notifyRequests()).isEmpty();
        String tooLong = "{\"rule\":" + rule("시뮬", lab) + ",\"from\":\"" + clock.instant().minus(Duration.ofDays(31)) + "\",\"to\":\""
                + clock.instant() + "\"}";
        mvc.perform(as(org, operator, json(post("/core/rules/simulate"), tooLong))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("RULE_SIMULATION_RANGE_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("시뮬레이션 기간은 30일까지입니다"));
        String ruleId = create(rule("저장된 규칙", lab));
        mvc.perform(as(org, operator, json(post("/core/rules/" + ruleId + "/simulate"), "{}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.alarms").value(1));
    }

    @Test
    @DisplayName("[RUL-01.13][TC-RUL-035] 차트 기준선 y=1000 → 규칙 폼 기본값(metric=co2, op=>, value=1000, 대상=실습실), 저장하지 않음")
    void draftFromChart() throws Exception {
        mvc.perform(as(org, operator, json(post("/core/rules/draft-from-chart"),
                        "{\"metric\":\"co2\",\"value\":1000,\"op\":\">\",\"target\":{\"spaceId\":\"" + lab + "\"}}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.condition.metric").value("co2"))
                .andExpect(jsonPath("$.response.condition.value").value(1000))
                .andExpect(jsonPath("$.response.scope.ids[0]").value(Long.toString(lab)));
        mvc.perform(as(org, operator, json(post("/core/rules/draft-from-chart"),
                        "{\"metric\":\"co2\",\"value\":1000,\"op\":\">\",\"target\":{\"deviceIds\":[\"" + sensor1 + "\"]}}")))
                .andExpect(jsonPath("$.response.scope.type").value("DEVICE"));
        assertThat(count("SELECT count(*) FROM data2flow_core.rules WHERE organization_id = :org")).isZero();
    }

    @Test
    @DisplayName("[RUL-06.02][TC-RUL-103][TC-RUL-104] 하루 20회 넘게 울린 규칙 → 튜닝 제안(지속 5분→15분, 전·후 시뮬레이션), 적용 전 규칙 불변, 적용하면 새 버전")
    void tuning() throws Exception {
        String ruleId = create(rule("잦은 규칙", lab));
        for (int i = 0; i < 141; i++) {
            raise(AlarmKeys.rule(Long.parseLong(ruleId), Long.toString(sensor1)) + i, AlarmSourceType.RULE, Long.parseLong(ruleId),
                    AlarmSeverity.MAJOR, sensor1, null, 1100, clock.instant().minus(Duration.ofHours(i)));
        }
        assertThat(jobs.tuning()).isTrue();
        MvcResult r = mvc.perform(as(org, analyst, get("/core/rule-tuning-suggestions").param("status", "OPEN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].problem").value("TOO_FREQUENT"))
                .andExpect(jsonPath("$.responses[0].proposed.for").value("PT15M")).andReturn();
        assertThat(jdbc.sql("SELECT version FROM data2flow_core.rules WHERE id = :id").param("id", Long.parseLong(ruleId)).query(Integer.class)
                .single()).isEqualTo(1);
        String sid = read(r, "$.responses[0].tuningSuggestionId");
        mvc.perform(as(org, operator, post("/core/rule-tuning-suggestions/" + sid + "/apply"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.ruleVersion").value(2));
        mvc.perform(as(org, operator, post("/core/rule-tuning-suggestions/" + sid + "/dismiss"))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("[RUL-06.04][TC-RUL-115] 규칙 1,000개면 저장 409 RULE_LIMIT_EXCEEDED")
    void ruleLimit() throws Exception {
        String flowId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO data2flow_core.flows (id, organization_id, name, created_by, updated_by) VALUES (CAST(:id AS uuid), :org, 'x', 0, 0)")
                .param("id", flowId).param("org", org).update();
        jdbc.sql("""
                        INSERT INTO data2flow_core.rules (organization_id, name, scope_type, scope_ids, condition, severity, title_template, flow_id,
                               created_by, updated_by)
                        SELECT :org, 'r' || g, 'DEVICE', '["1"]', '{}', 'INFO', 't', CAST(:flow AS uuid), 0, 0 FROM generate_series(1, 1000) g""")
                .param("org", org).param("flow", flowId).update();
        mvc.perform(as(org, operator, json(post("/core/rules"), rule("하나 더", lab)))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("RULE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("규칙은 1,000개까지 만들 수 있습니다"));
    }

    void signal(String ruleId, long deviceId, double value) {
        AlarmSignal s = AlarmSignal.raise(AlarmKeys.rule(Long.parseLong(ruleId), Long.toString(deviceId)), AlarmSourceType.RULE,
                Long.parseLong(ruleId), UUID.randomUUID().toString(), 1, "n-alarm-raise", AlarmSeverity.MINOR, "엔진 제목", deviceId, null, "co2",
                value, new AlarmSignal.Threshold(1000.0, 900.0), clock.instant(), UUID.randomUUID().toString());
        deliver(EventType.ALARM_SIGNAL, org, s, clock.instant());
    }
}
