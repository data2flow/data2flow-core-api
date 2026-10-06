package net.java21.data2flow.core.analytics;

import net.java21.data2flow.core.common.InternalHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANA-04.01: core→analytics POST 본문이 실제 uvicorn(httptools, analytics 이미지와 같은 서버)에 도착한다.
 * 예전 core 내부 클라이언트(JDK HttpClient 기본 HTTP/2)는 평문 http에 {@code Upgrade: h2c}를 붙였고, uvicorn httptools는 그 요청의
 * 본문을 버려 analytics가 400을 돌려줬다. 이제 내부 호출은 HTTP/1.1로 고정한다(ADR-060).
 */
class AnalyticsHttp11IT {

    /** FastAPI처럼 빈 본문이면 400을 주고, 받은 본문 길이와 Upgrade 헤더를 돌려주는 ASGI 앱 */
    private static final String APP = """
            import json
            async def app(scope, receive, send):
                if scope["type"] != "http":
                    return
                body = b""
                while True:
                    m = await receive()
                    if m["type"] == "http.disconnect":
                        break
                    body += m.get("body", b"")
                    if not m.get("more_body"):
                        break
                h = {k.decode().lower(): v.decode() for k, v in scope["headers"]}
                status = 200 if body else 400
                out = json.dumps({"header": {"isSuccessful": status == 200, "resultCode": "SUCCESS" if body else "INVALID_REQUEST",
                                  "resultMessage": ""}, "response": {"bodyLength": len(body), "upgrade": h.get("upgrade")}}).encode()
                await send({"type": "http.response.start", "status": status,
                            "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(out)).encode())]})
                await send({"type": "http.response.body", "body": out})
            """;

    static final GenericContainer<?> UVICORN = new GenericContainer<>("python:3.12-slim")
            .withCopyToContainer(Transferable.of(APP), "/app/echo_app.py")
            .withWorkingDirectory("/app")
            .withCommand("sh", "-c", "pip install -q --no-cache-dir uvicorn==0.54.0 httptools==0.8.0 && "
                    + "uvicorn echo_app:app --host 0.0.0.0 --port 8080 --http httptools")
            .withExposedPorts(8080)
            .waitingFor(Wait.forLogMessage(".*Uvicorn running.*", 1).withStartupTimeout(Duration.ofMinutes(5)));

    @BeforeAll
    static void start() {
        UVICORN.start();
    }

    @AfterAll
    static void stop() {
        UVICORN.stop();
    }

    private static String baseUrl() {
        return "http://" + UVICORN.getHost() + ":" + UVICORN.getMappedPort(8080);
    }

    @Test
    @DisplayName("[ANA-04.01] 재현: JDK HttpClient 기본값(HTTP/2 → Upgrade: h2c)으로 보낸 POST는 uvicorn httptools에서 본문이 사라져 400")
    void defaultHttp2ClientLosesBody() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/internal/analytics/runs"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"analysisId\":\"1\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body()).contains("\"bodyLength\": 0").contains("\"upgrade\": \"h2c\"");
        }
    }

    @Test
    @DisplayName("[ANA-04.01] core InternalHttp(analytics 클라이언트)의 POST 본문은 uvicorn httptools에 그대로 도착한다")
    void internalHttpDeliversBody() {
        InternalHttp analytics = new InternalHttp("analytics", baseUrl(), Duration.ofSeconds(10), JsonMapper.builder().build());
        InternalHttp.Result result = analytics.send(HttpMethod.POST, "/internal/analytics/runs", null,
                Map.of("analysisId", "1", "trigger", "MANUAL"), null);
        JsonNode response = result.response();
        assertThat(result.status()).isEqualTo(200);
        assertThat(response.get("bodyLength").asInt()).isGreaterThan(0);
        assertThat(response.get("upgrade").isNull()).isTrue();
    }
}
