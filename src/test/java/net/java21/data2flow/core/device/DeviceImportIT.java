package net.java21.data2flow.core.device;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-02.04 CSV 일괄 등록·내보내기(API-DEV-19·20) */
class DeviceImportIT extends IntegrationTestSupport {

    private static final String HEADER = "sourceId,externalId,name,kind,modelCode,spaceId,expectedIntervalSec,offlineMultiplier,tags,virtual";

    private long org;
    private long admin;
    private long room;
    private long other;
    private long source;

    @BeforeEach
    void setUp() {
        org = fx.organization("imp");
        admin = fx.user(org, "imp.admin", "ADMIN");
        long site = data.site(org, "본관");
        room = data.space(org, site, "ROOM", "실습실");
        other = data.space(org, site, "ROOM", "교무실");
        data.metric(org, "temperature", "℃");
        data.model(org, "EM300-TH", List.of("temperature"));
        source = data.source(org, "cs");
    }

    /** 10행 중 2행 오류(없는 모델, 범위 밖 주기) */
    private String tenRows() {
        StringBuilder sb = new StringBuilder("﻿" + HEADER + "\r\n");
        for (int i = 0; i < 10; i++) {
            String modelCode = i == 3 ? "EM500-CO3" : "EM300-TH";
            String interval = i == 7 ? "5" : "600";
            sb.append(source).append(",DEV-").append(i).append(",\"센서, ").append(i).append("\",SENSOR,").append(modelCode).append(',')
                    .append(room).append(',').append(interval).append(",3.0,pilot;3층,false\r\n");
        }
        return sb.toString();
    }

    private MockMultipartFile file(String csv) {
        return new MockMultipartFile("file", "devices.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("[DEV-02.04][AT-DEV-04.2] 10행 중 2행 오류 dryRun: 성공 8, 오류 2(줄 번호·사유), 저장 0 — TC-DEV-031·050")
    void dryRun() throws Exception {
        mvc.perform(upload(admin, tenRows()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.total").value(10))
                .andExpect(jsonPath("$.response.succeeded").value(8))
                .andExpect(jsonPath("$.response.failed").value(2))
                .andExpect(jsonPath("$.response.rows[3].line").value(5))
                .andExpect(jsonPath("$.response.rows[3].errorCode").value("MODEL_NOT_FOUND"))
                .andExpect(jsonPath("$.response.rows[7].errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.response.rows[7].message", containsString("expectedIntervalSec")));
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("[DEV-02.04][AT-DEV-04.3] ALL_OR_NOTHING은 0건 등록·오류 보고, SKIP_ERRORS는 맞는 8건 ACTIVE 등록 — TC-DEV-031·053")
    void importModes() throws Exception {
        mvc.perform(upload(admin, tenRows()).param("dryRun", "false").param("mode", "ALL_OR_NOTHING"))
                .andExpect(jsonPath("$.response.succeeded").value(0)).andExpect(jsonPath("$.response.failed").value(10));
        assertThat(count()).isZero();
        mvc.perform(upload(admin, tenRows()).param("dryRun", "false").param("mode", "SKIP_ERRORS"))
                .andExpect(jsonPath("$.response.succeeded").value(8));
        assertThat(count()).isEqualTo(8);
        assertThat(auditCount(org, "DEVICE_IMPORTED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.device_tags WHERE organization_id = :o").param("o", org)
                .query(Long.class).single()).isEqualTo(16);
        // 같은 파일을 다시 가져오면 모두 중복
        mvc.perform(upload(admin, tenRows()))
                .andExpect(jsonPath("$.response.rows[0].errorCode").value("DEVICE_DUPLICATE"));
    }

    @Test
    @DisplayName("[DEV-02.04] 파일 안 중복·형식 오류는 행 단위, 머리글 오류·빈 파일·잘못된 mode는 400 — TC-DEV-051")
    void rowAndHeaderErrors() throws Exception {
        String csv = HEADER + "\n" + source + ",a1,n,SENSOR,EM300-TH," + room + ",,,,\n" + source + ",A1,n,SENSOR,EM300-TH," + room + ",,,,\n"
                + "x,a2,n,ROBOT,EM300-TH,y,,abc,,maybe\n" + source + ",a3,n,SENSOR,EM300-TH,999999,,,,\n";
        mvc.perform(upload(admin, csv))
                .andExpect(jsonPath("$.response.rows[0].ok").value(true))
                .andExpect(jsonPath("$.response.rows[1].errorCode").value("DEVICE_DUPLICATE"))
                .andExpect(jsonPath("$.response.rows[2].errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.response.rows[3].errorCode").value("SPACE_NOT_FOUND"));
        mvc.perform(upload(admin, "sourceId,color\n1,red\n"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_COLUMN"));
        mvc.perform(upload(admin, "")).andExpect(status().isBadRequest());
        mvc.perform(upload(admin, csv).param("mode", "SOMETIMES"))
                .andExpect(status().isBadRequest());
        long op = fx.user(org, "imp.op", "OPERATOR");
        mvc.perform(upload(op, csv)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[DEV-02.04][AT-DEV-04.4] 공간 '실습실' 필터로 내보내면 그 공간 기기만, 템플릿과 같은 머리글(다시 가져올 수 있음) — TC-DEV-052")
    void export() throws Exception {
        long model = jdbc.sql("SELECT id FROM data2flow_core.device_models WHERE organization_id = :o").param("o", org).query(Long.class).single();
        long a = data.device(org, source, "in-room", "ACTIVE", room, model);
        data.device(org, source, "elsewhere", "ACTIVE", other, model);
        new DeviceTestData(jdbc).tag(org, a, "pilot");
        jdbc.sql("UPDATE data2flow_core.devices SET name = '=cmd' WHERE id = :id").param("id", a).update();
        String csv = mvc.perform(as(org, admin, get("/core/devices/export").param("spaceId", Long.toString(room))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("devices.csv")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿" + HEADER + "\r\n").contains("in-room").contains("'=cmd").contains("pilot")
                .doesNotContain("elsewhere");
        mvc.perform(as(org, admin, get("/core/devices/export").param("format", "xlsx"))).andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(long user, String csv) {
        var builder = multipart("/core/devices/import").file(file(csv));
        builder.header("X-USER-ID", Long.toString(user)).header("X-ORG-ID", Long.toString(org)).header("Accept-Language", "ko");
        return builder;
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.devices WHERE organization_id = :o").param("o", org).query(Long.class).single();
    }
}
