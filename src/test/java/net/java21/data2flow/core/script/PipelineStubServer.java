package net.java21.data2flow.core.script;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * data2flow-pipeline 대역(MockWebServer 역할, TC-SCR-079): API-SCR-30 정적 검사와 API-SCR-31 테스트 실행.
 * 검사는 {@code require(}·{@code fetch(}를 줄·열과 함께 오류로, 필수 함수가 없으면 오류로 돌려준다. 받은 본문과 호출자 헤더를 기록하고,
 * {@link #down(boolean)}이면 503으로 응답한다.
 */
final class PipelineStubServer {

    private static final Pattern FORBIDDEN = Pattern.compile("\\b(require|fetch|eval|setTimeout)\\s*\\(");

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicBoolean down = new AtomicBoolean();

    PipelineStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.createContext("/internal/pipeline/scripts/check", ex -> handle(ex, this::check));
        server.createContext("/internal/pipeline/scripts/test-run", ex -> handle(ex, body ->
                "{\"ok\":true,\"output\":{\"metrics\":[{\"key\":\"dew_point\",\"value\":9.4}]},"
                        + "\"diff\":{\"added\":[{\"key\":\"dew_point\",\"value\":9.4}],\"removed\":[],\"changed\":[]},"
                        + "\"logs\":[{\"at\":\"2026-10-03T00:00:00Z\",\"message\":\"x 3\"}],\"durationMs\":0.4,\"outputBytes\":42}"));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Received> received(String pathSuffix) {
        return received.stream().filter(r -> r.path().endsWith(pathSuffix)).toList();
    }

    void reset() {
        received.clear();
        down.set(false);
    }

    void down(boolean value) {
        down.set(value);
    }

    private void handle(HttpExchange exchange, java.util.function.Function<String, String> responder) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestURI().getPath(), body, exchange.getRequestHeaders().getFirst("X-CALLER-SERVICE")));
        if (down.get()) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        byte[] out = ("{\"header\":{\"isSuccessful\":true,\"resultCode\":\"SUCCESS\",\"resultMessage\":\"\"},\"response\":"
                + responder.apply(body) + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    /** 아주 단순한 검사 흉내: 금지 API 줄·열, 필수 함수 */
    private String check(String body) {
        String code = body.replaceAll("(?s).*\"code\":\"((?:[^\"\\\\]|\\\\.)*)\".*", "$1").replace("\\n", "\n");
        String kind = body.contains("\"kind\":\"DECODE\"") ? "decode" : "transform";
        StringBuilder problems = new StringBuilder();
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = FORBIDDEN.matcher(lines[i]);
            while (m.find()) {
                if (!problems.isEmpty()) {
                    problems.append(',');
                }
                problems.append("{\"line\":").append(i + 1).append(",\"col\":").append(m.start() + 1)
                        .append(",\"severity\":\"ERROR\",\"code\":\"SCRIPT_FORBIDDEN_API\",\"message\":\"금지된 API: ")
                        .append(m.group(1)).append("\"}");
            }
        }
        boolean hasFunction = code.contains("function " + kind + "(");
        if (!hasFunction) {
            if (!problems.isEmpty()) {
                problems.append(',');
            }
            problems.append("{\"line\":1,\"col\":1,\"severity\":\"ERROR\",\"code\":\"SCRIPT_FUNCTION_MISSING\",\"message\":\"")
                    .append(kind).append("\"}");
        }
        return "{\"ok\":" + problems.isEmpty() + ",\"problems\":[" + problems + "]}";
    }

    record Received(String path, String body, String callerService) {
    }
}
