package net.java21.data2flow.core.source;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.SourceRotationProgress;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.source.service.SourceRotationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSC-07.02 무중단 자격증명 교체(BR-DSC-09)·DSC-07.05 복제 — TC-DSC-172·173·190·191 */
@TestPropertySource(properties = "data2flow.core.source.zero-downtime-rotation=true")
class SourceLifecycleIT extends SourceItSupport {

    @Autowired
    SourceRotationService rotations;

    long source;

    @BeforeEach
    void activeSource() throws Exception {
        source = createSource(mqttBody("rot-src", "\"activate\":true"));
        for (String instance : List.of("ingress-0", "ingress-1")) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.source_runtimes (source_id, instance_id, organization_id, state, reported_at)
                            VALUES (:s, :i, :org, 'CONNECTED', :now)""")
                    .param("s", source).param("i", instance).param("org", org).param("now", Pg.ts(clock.instant())).update();
        }
    }

    private String rotate(String value) throws Exception {
        String body = mvc.perform(as(org, integrator, json(put("/core/sources/" + source + "/secret"),
                        "{\"kind\":\"HEADER\",\"value\":\"" + value + "\"}")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.response.rotationId");
    }

    private String runtimeOf(String path) throws Exception {
        String body = mvc.perform(get("/internal/core/sources/runtime-config")).andReturn().getResponse().getContentAsString();
        List<Object> v = JsonPath.read(body, "$.response.sources[?(@.id == '" + source + "')]" + path);
        return v.isEmpty() || v.getFirst() == null ? null : v.getFirst().toString();
    }

    private void report(String rotationId, String instance, boolean ok) {
        deliver(EventType.SOURCE_ROTATION_PROGRESS, org, new SourceRotationProgress(source, rotationId, instance, ok, ok ? null : "AUTH 거부"),
                clock.instant());
    }

    @Test
    @DisplayName("[DSC-07.02][AT-DSC-05.1][TC-DSC-172] 연결 중인 ACTIVE 소스: 새 값은 pending(ROTATING)·실행 설정 rotation으로 알리고, 연결된 인스턴스가 모두 성공하면 확정(DONE)")
    void rotationCommitsWhenAllInstancesSucceed() throws Exception {
        String rid = rotate("student:new-Pa55");
        mvc.perform(as(org, operator, get("/core/sources/" + source + "/secret-rotations/" + rid)))
                .andExpect(jsonPath("$.response.state").value("ROTATING")).andExpect(jsonPath("$.response.kinds[0]").value("HEADER_VALUE"));
        mvc.perform(as(org, operator, get("/core/sources/" + source))).andExpect(jsonPath("$.response.secret.rotating").value(true));
        assertThat(runtimeOf(".secrets.HEADER_VALUE")).isEqualTo("student:s3cr3t-Pa55");
        assertThat(runtimeOf(".rotation.secrets.HEADER_VALUE")).isEqualTo("student:new-Pa55");
        assertThat(runtimeOf(".rotation.rotationId")).isEqualTo(rid);
        // 같은 소스에 두 번째 교체는 진행 중이라 409
        mvc.perform(as(org, integrator, json(put("/core/sources/" + source + "/secret"), "{\"kind\":\"HEADER\",\"value\":\"x:y\"}")))
                .andExpect(status().isConflict());
        report(rid, "ingress-0", true);
        report("other-rotation", "ingress-1", false);
        mvc.perform(as(org, operator, get("/core/sources/" + source + "/secret-rotations/" + rid)))
                .andExpect(jsonPath("$.response.state").value("ROTATING")).andExpect(jsonPath("$.response.instances.length()").value(1));
        report(rid, "ingress-1", true);
        mvc.perform(as(org, operator, get("/core/sources/" + source + "/secret-rotations/" + rid)))
                .andExpect(jsonPath("$.response.state").value("DONE")).andExpect(jsonPath("$.response.finishedAt").isNotEmpty());
        assertThat(runtimeOf(".secrets.HEADER_VALUE")).isEqualTo("student:new-Pa55");
        assertThat(runtimeOf(".rotation")).isNull();
        assertThat(auditCount(org, "SOURCE_SECRET_ROTATED")).isEqualTo(1);
        mvc.perform(as(org, operator, get("/core/sources/" + source + "/secret-rotations/nope"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DSC-07.02][AT-DSC-05.2][TC-DSC-172] 첫 인스턴스가 실패하면 교체를 멈추고 이전 값 유지(FAILED), 보고 없이 10분 지나면 실패로 끝냄")
    void rotationFailsAndTimesOut() throws Exception {
        String rid = rotate("student:wrong");
        report(rid, "ingress-0", false);
        mvc.perform(as(org, operator, get("/core/sources/" + source + "/secret-rotations/" + rid)))
                .andExpect(jsonPath("$.response.state").value("FAILED")).andExpect(jsonPath("$.response.error").value("AUTH 거부"))
                .andExpect(jsonPath("$.response.instances[0].ok").value(false));
        assertThat(runtimeOf(".secrets.HEADER_VALUE")).isEqualTo("student:s3cr3t-Pa55");
        assertThat(runtimeOf(".rotation")).isNull();
        assertThat(auditCount(org, "SOURCE_SECRET_ROTATION_FAILED")).isEqualTo(1);

        String rid2 = rotate("student:slow");
        clock.advance(Duration.ofMinutes(11));
        assertThat(rotations.expireStale()).isEqualTo(1);
        mvc.perform(as(org, operator, get("/core/sources/" + source + "/secret-rotations/" + rid2)))
                .andExpect(jsonPath("$.response.state").value("FAILED"));
        assertThat(runtimeOf(".secrets.HEADER_VALUE")).isEqualTo("student:s3cr3t-Pa55");

        // 연결된 인스턴스가 없으면(90초 넘게 보고 없음) 바로 저장(DONE)
        String rid3 = rotate("student:direct");
        mvc.perform(as(org, operator, get("/core/sources/" + source + "/secret-rotations/" + rid3)))
                .andExpect(jsonPath("$.response.state").value("DONE"));
        assertThat(runtimeOf(".secrets.HEADER_VALUE")).isEqualTo("student:direct");
    }

    @Test
    @DisplayName("[DSC-07.05][AT-DSC-08.1][TC-DSC-190] 복제: 새 소스는 DRAFT, 비밀값·기기는 복사하지 않음, 같은 코드 409")
    void cloneSource() throws Exception {
        mvc.perform(as(org, integrator, json(post("/core/sources/" + source + "/clone"), "{\"code\":\"rot-copy\",\"name\":\"복제본\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.lifecycle").value("DRAFT"))
                .andExpect(jsonPath("$.response.secrets").isEmpty()).andExpect(jsonPath("$.response.topics[0].topic")
                        .value("application/+/device/+/event/up"));
        mvc.perform(as(org, integrator, json(post("/core/sources/" + source + "/clone"), "{\"code\":\"rot-copy\",\"name\":\"복제본\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CODE_DUPLICATE"));
    }

    @Test
    @DisplayName("[DSC-07.02][DSC-07.05][TC-DSC-173·191] 권한: OPERATOR 교체·복제 403, 다른 조직 404")
    void permissions() throws Exception {
        mvc.perform(as(org, operator, json(put("/core/sources/" + source + "/secret"), "{\"kind\":\"HEADER\",\"value\":\"a:b\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, operator, json(post("/core/sources/" + source + "/clone"), "{\"code\":\"op-copy\",\"name\":\"x\"}")))
                .andExpect(status().isForbidden());
        long other = fx.organization("dsc-rot-other");
        long otherAdmin = fx.user(other, "dsc.rot.other", "ADMIN");
        mvc.perform(as(other, otherAdmin, json(put("/core/sources/" + source + "/secret"), "{\"kind\":\"HEADER\",\"value\":\"a:b\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, get("/core/sources/" + source + "/secret-rotations/x"))).andExpect(status().isNotFound());
    }
}
