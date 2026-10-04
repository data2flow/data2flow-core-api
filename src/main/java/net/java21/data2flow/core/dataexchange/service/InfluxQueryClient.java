package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.core.dataexchange.domain.CsvReader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 외부 InfluxDB 2.x 읽기(TSD-04.02, BR-TSD-16, 예: 아카데미 iot-bucket). Flux 쿼리를 {@code POST /api/v2/query?org=}로 보내고
 * 주석 달린 CSV(annotated CSV)를 줄 단위로 흘려 읽는다. 토큰은 {@code Authorization: Token …}. 기간은 호출자가 1일 단위로 나눈다.
 * 시험은 대역 HTTP 서버와 InfluxDB 문서의 예시 응답으로 한다(실제 s3 InfluxDB에 붙지 않음).
 */
@Component
public class InfluxQueryClient {

    /** 한 점: 시각, 값, 필드, 태그(열 이름 → 값) */
    public record Point(Instant time, Double value, String field, Map<String, String> tags) {
    }

    public interface PointHandler {
        void point(Point p) throws IOException;
    }

    private final ExchangeProperties properties;

    public InfluxQueryClient(ExchangeProperties properties) {
        this.properties = properties;
    }

    /** {@code GET /ping}(204) — 접속 확인. 실패면 IOException */
    public void ping(String url) throws IOException {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(properties.targetTimeout()).build()) {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(base(url) + "/ping")).timeout(properties.targetTimeout()).GET()
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() / 100 != 2) {
                throw new IOException("ping " + r.statusCode());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("중단됨");
        } catch (IllegalArgumentException ex) {
            throw new IOException("주소가 올바르지 않습니다");
        }
    }

    /** Flux 쿼리 실행 → 점마다 처리기 */
    public void query(String url, String org, String token, String flux, PointHandler handler) throws IOException {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(properties.targetTimeout()).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base(url) + "/api/v2/query?org=" + URLEncoder.encode(org, StandardCharsets.UTF_8)))
                    .timeout(properties.targetTimeout().multipliedBy(20))
                    .header("Authorization", "Token " + (token == null ? "" : token))
                    .header("Content-Type", "application/vnd.flux").header("Accept", "application/csv")
                    .POST(HttpRequest.BodyPublishers.ofString(flux)).build();
            HttpResponse<InputStream> r = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = r.body()) {
                if (r.statusCode() / 100 != 2) {
                    String detail = new String(body.readNBytes(300), StandardCharsets.UTF_8);
                    throw new IOException("InfluxDB " + r.statusCode() + " " + detail);
                }
                parse(body, handler);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("중단됨");
        }
    }

    /** 주석 달린 CSV 읽기: #로 시작하는 주석 줄은 건너뛰고, 빈 줄 뒤 첫 줄은 머리글. _time·_value·_field 외 열은 태그로 본다 */
    static void parse(InputStream body, PointHandler handler) throws IOException {
        CsvReader reader = new CsvReader(new InputStreamReader(body, StandardCharsets.UTF_8), ',');
        Map<String, Integer> header = null;
        CsvReader.Record rec;
        while ((rec = reader.next()) != null) {
            List<String> cells = rec.cells();
            if (cells.size() == 1 && cells.getFirst().isEmpty()) {
                header = null;
                continue;
            }
            if (!cells.isEmpty() && cells.getFirst().startsWith("#")) {
                header = null;
                continue;
            }
            if (header == null) {
                header = new HashMap<>();
                for (int i = 0; i < cells.size(); i++) {
                    header.put(cells.get(i), i);
                }
                continue;
            }
            Integer ti = header.get("_time");
            Integer vi = header.get("_value");
            if (ti == null || vi == null || ti >= cells.size() || vi >= cells.size()) {
                continue;
            }
            Map<String, String> tags = new HashMap<>();
            for (Map.Entry<String, Integer> e : header.entrySet()) {
                String name = e.getKey();
                if (!name.isEmpty() && !name.startsWith("_") && !"result".equals(name) && !"table".equals(name) && e.getValue() < cells.size()) {
                    tags.put(name, cells.get(e.getValue()));
                }
            }
            Integer fi = header.get("_field");
            Double value;
            try {
                value = Double.parseDouble(cells.get(vi));
            } catch (NumberFormatException ex) {
                value = null;
            }
            handler.point(new Point(Instant.parse(cells.get(ti)), value, fi == null ? null : cells.get(fi), tags));
        }
    }

    private static String base(String url) {
        return url.replaceAll("/+$", "");
    }
}
