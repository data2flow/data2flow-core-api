package net.java21.data2flow.core.retention;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 콜드 보관 파일(TSD-05.02, BR-TSD-18): pipeline 등록 API-TSD-61, 목록 API-TSD-33, 저장 현황 API-TSD-43 */
class ArchiveFileIT extends IntegrationTestSupport {

    static final String CHECKSUM = "a".repeat(64);

    private long org;
    private long admin;

    @BeforeEach
    void setUp() {
        org = fx.organization("arc");
        admin = fx.user(org, "arc.admin", "ADMIN");
    }

    private String register(long orgId, String key, String from, String to) throws Exception {
        return mvc.perform(json(post("/internal/core/archive-files"), """
                        {"organizationId":"%d","dataClass":"TELEMETRY","rangeFrom":"%s","rangeTo":"%s","objectKey":"%s",
                         "format":"PARQUET","rowsCount":120000,"bytes":2400000,"checksum":"%s"}""".formatted(orgId, from, to, key, CHECKSUM)))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("[TSD-05.02][AT-TSD-09.1][API-TSD-61] pipeline 등록 201 {id}, 같은 객체 키 재등록은 같은 ID(멱등), 검증 400, 배포 조직 밖 404 — TC-TSD-129")
    void register() throws Exception {
        mvc.perform(json(post("/internal/core/archive-files"), """
                        {"organizationId":"%d","dataClass":"TELEMETRY","rangeFrom":"2025-09-01T00:00:00Z","rangeTo":"2025-10-01T00:00:00Z",
                         "objectKey":"org/%d/telemetry/2025-09.parquet","rowsCount":10,"bytes":2048,"checksum":"%s"}""".formatted(org, org, CHECKSUM)))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"));
        String first = register(org, "org/1/raw.parquet", "2025-08-01T00:00:00Z", "2025-09-01T00:00:00Z");
        String again = register(org, "org/1/raw.parquet", "2025-08-01T00:00:00Z", "2025-09-01T00:00:00Z");
        assertThat((String) JsonPath.read(again, "$.response.id")).isEqualTo(JsonPath.read(first, "$.response.id"));
        mvc.perform(json(post("/internal/core/archive-files"), """
                        {"organizationId":"x","dataClass":"NOPE","rangeFrom":"2025-09-01T00:00:00Z","rangeTo":"2025-08-01T00:00:00Z",
                         "objectKey":"","format":"CSV","rowsCount":-1,"bytes":-1,"checksum":"zz"}"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(8));
        mvc.perform(json(post("/internal/core/archive-files"), """
                        {"organizationId":"99999999","dataClass":"TELEMETRY","rangeFrom":"2025-09-01T00:00:00Z","rangeTo":"2025-10-01T00:00:00Z",
                         "objectKey":"k","rowsCount":1,"bytes":1,"checksum":"%s"}""".formatted(CHECKSUM)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[TSD-05.02][API-TSD-33] 목록(종류·기간 겹침 필터, 최신 구간부터)·상세, 다른 조직 404 ARCHIVE_NOT_FOUND, VIEWER 403 — TC-TSD-128·130")
    void listAndGet() throws Exception {
        register(org, "a.parquet", "2025-08-01T00:00:00Z", "2025-09-01T00:00:00Z");
        String id = JsonPath.read(register(org, "b.parquet", "2025-09-01T00:00:00Z", "2025-10-01T00:00:00Z"), "$.response.id");
        mvc.perform(as(org, admin, get("/core/archives")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].objectKey").value("b.parquet"))
                .andExpect(jsonPath("$.responses[0].rowsCount").value(120000))
                .andExpect(jsonPath("$.responses[0].restoredJobId").doesNotExist());
        mvc.perform(as(org, admin, get("/core/archives").param("dataClass", "telemetry").param("from", "2025-09-15T00:00:00Z")
                        .param("to", "2025-12-01T00:00:00Z")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(id));
        mvc.perform(as(org, admin, get("/core/archives").param("dataClass", "RAW_MESSAGE"))).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(as(org, admin, get("/core/archives").param("dataClass", "X"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/archives").param("from", "2025-10-01T00:00:00Z").param("to", "2025-09-01T00:00:00Z")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/archives/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.checksum").value(CHECKSUM)).andExpect(jsonPath("$.response.format").value("PARQUET"));
        long other = fx.organization("arc2");
        long otherAdmin = fx.user(other, "arc2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/archives/" + id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("ARCHIVE_NOT_FOUND"));
        mvc.perform(as(other, otherAdmin, get("/core/archives"))).andExpect(jsonPath("$.totalCount").value(0));
        long viewer = fx.user(org, "arc.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/archives"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[TSD-02.03][API-TSD-43] 저장 현황: 파티션별 상태·행 수·크기·콜드 압축 비율, 지운 파티션은 합계에서 뺌 — TC-TSD-030·134")
    void storageStats() throws Exception {
        insertPartition("telemetry_y2026m09", "telemetry", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z", "COMPRESSED", 5000L, 800000L, 0.18);
        insertPartition("telemetry_y2026m10", "telemetry", "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z", "CREATED", 100L, null, null);
        insertPartition("telemetry_y2025m01", "telemetry", "2025-01-01T00:00:00Z", "2025-02-01T00:00:00Z", "DROPPED", 0L, 999L, null);
        String body = mvc.perform(as(org, admin, get("/core/storage-stats"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.partitions.length()").value(3))
                .andExpect(jsonPath("$.response.partitions[?(@.name=='telemetry_y2026m09')].compressionRatio").value(0.18))
                .andExpect(jsonPath("$.response.partitions[?(@.name=='telemetry_y2026m09')].state").value("COMPRESSED"))
                .andExpect(jsonPath("$.response.partitions[?(@.name=='telemetry_y2026m09')].range.from").value("2026-09-01T00:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        long total = ((Number) JsonPath.read(body, "$.response.totalBytes")).longValue();
        // y2026m10은 기록된 크기가 없어 실제 파티션 크기(테스트 스키마에 있음)를 잰다. DROPPED는 합계에서 뺀다
        assertThat(total).isGreaterThanOrEqualTo(800000L).isLessThan(800000L + 999L + 10_000_000L);
    }

    private void insertPartition(String name, String table, String from, String to, String state, Long rows, Long bytes, Double ratio) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.partition_registries (partition_name, table_name, range_from, range_to, state, rows_estimate,
                                                                             bytes, archive_ratio)
                        VALUES (:n, :t, :f, :to, :s, :r, :b, :ratio)""")
                .param("n", name).param("t", table).param("f", Pg.ts(Instant.parse(from))).param("to", Pg.ts(Instant.parse(to)))
                .param("s", state).param("r", rows).param("b", bytes).param("ratio", ratio).update();
    }
}
