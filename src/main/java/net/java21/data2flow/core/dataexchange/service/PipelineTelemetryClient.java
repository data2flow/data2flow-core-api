package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.config.CoreProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pipeline 시계열 쓰기 내부 API(TSD-04.02). 시계열은 pipeline 소유라 core는 직접 쓰지 않는다(conventions §6).
 * <ul>
 *   <li>API-TSD-52 {@code POST /internal/pipeline/telemetry/bulk-insert} — 묶음 ≤10,000행, {@code ON CONFLICT DO NOTHING}, flags=imported</li>
 *   <li>API-TSD-50 {@code POST /internal/pipeline/aggregates/mark-dirty} — 가져온 구간 집계 재계산(BR-TSD-06, AT-TSD-05.2)</li>
 * </ul>
 */
@Component
public class PipelineTelemetryClient {

    /** 넣을 점 하나 */
    public record Row(long deviceId, String metricKey, Instant measuredAt, double value) {
    }

    /** 다시 계산할 구간 */
    public record DirtyRange(long deviceId, String metricKey, Instant from, Instant to) {
    }

    /** 넣은 수와 이미 있어 건너뛴 수 */
    public record Inserted(long inserted, long skipped) {
    }

    private final InternalHttp http;

    public PipelineTelemetryClient(CoreProperties core, ExchangeProperties properties, JsonMapper json) {
        this.http = new InternalHttp("pipeline", core.pipelineBaseUrl(), properties.pipelineTimeout(), json);
    }

    public Inserted bulkInsert(long organizationId, long importJobId, List<Row> rows) {
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Row r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", r.deviceId());
            item.put("metricKey", r.metricKey());
            item.put("measuredAt", r.measuredAt().toString());
            item.put("value", r.value());
            item.put("quality", 0);
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId);
        body.put("importJobId", importJobId);
        body.put("rows", items);
        JsonNode r = http.call(HttpMethod.POST, "/internal/pipeline/telemetry/bulk-insert", null, body);
        return new Inserted(r == null ? 0 : r.path("inserted").asLong(0), r == null ? 0 : r.path("skipped").asLong(0));
    }

    public void markDirty(long organizationId, List<DirtyRange> ranges) {
        for (int i = 0; i < ranges.size(); i += 1000) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (DirtyRange d : ranges.subList(i, Math.min(ranges.size(), i + 1000))) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("deviceId", d.deviceId());
                item.put("metricKey", d.metricKey());
                item.put("from", d.from().toString());
                item.put("to", d.to().toString());
                item.put("reason", "IMPORT");
                items.add(item);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("organizationId", organizationId);
            body.put("items", items);
            http.call(HttpMethod.POST, "/internal/pipeline/aggregates/mark-dirty", null, body);
        }
    }
}
