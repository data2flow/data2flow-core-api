package net.java21.data2flow.core.device;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IAM-04.05 권한 매트릭스: 기기 조회(DEV_READ)·등록(DEV_ADMIN)·승인(DEV_PLACE)·서버 속성(DEV_ADMIN)·그룹 쓰기(DEV_ADMIN) */
class DevicePermissionIT extends IntegrationTestSupport {

    @ParameterizedTest(name = "{0}: 조회 {1}, 등록 {2}, 승인 {3}, 속성 {4}, 그룹 {5}")
    @CsvSource({
            "ADMIN,      200, 201, 200, 200, 201",
            "INTEGRATOR, 200, 201, 200, 200, 201",
            "OPERATOR,   200, 403, 200, 403, 403",
            "ANALYST,    200, 403, 403, 403, 403",
            "VIEWER,     200, 403, 403, 403, 403"})
    @DisplayName("[DEV-02.01][IAM-04.05][AT-DEV-03.7] 역할별 허용·403 — TC-DEV-035·042·046·054·084·165·185")
    void matrix(String role, int read, int create, int approve, int attribute, int group) throws Exception {
        long org = fx.organization("perm");
        long user = fx.user(org, "perm." + role.toLowerCase(), role);
        long room = data.space(org, data.site(org, "본관"), "ROOM", "실습실");
        data.metric(org, "temperature", "℃");
        long model = data.model(org, "EM300-TH", List.of("temperature"));
        long source = data.source(org, "cs");
        long active = data.device(org, source, "a1", "ACTIVE", room, model);
        long pending = data.device(org, source, "p1", "PENDING", null, null);

        mvc.perform(as(org, user, get("/core/devices/" + active))).andExpect(status().is(read));
        mvc.perform(as(org, user, json(post("/core/devices"), """
                        {"sourceId":"%d","externalId":"n1","name":"n","kind":"SENSOR","modelId":"%d","spaceId":"%d"}"""
                        .formatted(source, model, room))))
                .andExpect(status().is(create));
        mvc.perform(as(org, user, json(post("/core/devices/approve"), "{\"items\":[{\"deviceId\":\"%d\",\"baseVersion\":0}],\"modelId\":\"%d\",\"spaceId\":\"%d\"}"
                        .formatted(pending, model, room))))
                .andExpect(status().is(approve));
        mvc.perform(as(org, user, json(put("/core/devices/" + active + "/attributes/SERVER/maxTemp"), "{\"value\":28}")))
                .andExpect(status().is(attribute));
        mvc.perform(as(org, user, json(post("/core/device-groups"), "{\"name\":\"g\",\"type\":\"STATIC\"}"))).andExpect(status().is(group));

        long other = fx.organization("perm-other");
        long stranger = fx.user(other, "perm.stranger." + role.toLowerCase(), role);
        mvc.perform(as(other, stranger, get("/core/devices/" + active))).andExpect(status().isNotFound());
    }
}
