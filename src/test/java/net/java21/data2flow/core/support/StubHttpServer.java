package net.java21.data2flow.core.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 내부 서비스 대역 HTTP 서버(MockWebServer 역할, design/testing/backend.md): action·flow-engine·simulator 내부 API를 흉내 낸다.
 * {@link #on}으로 (메서드, 경로 정규식)마다 응답을 정하고, 받은 요청(경로·쿼리·본문·호출자·조직 헤더)을 기록한다.
 * 정한 응답이 없으면 404 {@code RESOURCE_NOT_FOUND}. 나중에 정한 규칙이 먼저 맞는다.
 */
public final class StubHttpServer {

    private final HttpServer server;
    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /** 받은 요청 */
    public record Received(String method, String path, String query, String body, Map<String, List<String>> headers) {
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().getFirst();
                }
            }
            return null;
        }
    }

    /** 응답 */
    public record Reply(int status, String body, Map<String, String> headers) {
    }

    private record Rule(String method, Pattern path, Function<Received, Reply> responder) {
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        rules.clear();
        received.clear();
    }

    /** 성공 봉투 {@code {header, response}}로 답한다 */
    public StubHttpServer ok(String method, String pathRegex, int status, String responseJson) {
        return on(method, pathRegex, r -> new Reply(status, success(responseJson), Map.of()));
    }

    public StubHttpServer on(String method, String pathRegex, Function<Received, Reply> responder) {
        rules.addFirst(new Rule(method, Pattern.compile(pathRegex), responder));
        return this;
    }

    /** 실패 봉투(상태·코드·추가 본문 조각) */
    public StubHttpServer fail(String method, String pathRegex, int status, String code, String extraJson) {
        String body = "{\"header\":{\"isSuccessful\":false,\"resultCode\":\"" + code + "\",\"resultMessage\":\"" + code + "\"}"
                + (extraJson == null ? "" : "," + extraJson) + "}";
        return on(method, pathRegex, r -> new Reply(status, body, Map.of()));
    }

    public static String success(String responseJson) {
        return "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"SUCCESS\",\"resultMessage\":\"SUCCESS\"}"
                + (responseJson == null ? "" : ",\"response\":" + responseJson) + "}";
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public List<Received> received(String method, String pathRegex) {
        Pattern p = Pattern.compile(pathRegex);
        List<Received> out = new ArrayList<>();
        for (Received r : received) {
            if (r.method().equals(method) && p.matcher(r.path()).matches()) {
                out.add(r);
            }
        }
        return out;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Received r = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                body, Map.copyOf(exchange.getRequestHeaders()));
        received.add(r);
        Reply reply = null;
        for (Rule rule : rules) {
            if (rule.method().equals(r.method()) && rule.path().matcher(r.path()).matches()) {
                reply = rule.responder().apply(r);
                break;
            }
        }
        if (reply == null) {
            reply = new Reply(404, "{\"header\":{\"isSuccessful\":false,\"resultCode\":\"RESOURCE_NOT_FOUND\",\"resultMessage\":\"none\"}}",
                    Map.of());
        }
        byte[] out = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
        if (reply.status() == 204 || out.length == 0) {
            exchange.sendResponseHeaders(reply.status(), -1);
        } else {
            exchange.sendResponseHeaders(reply.status(), out.length);
            exchange.getResponseBody().write(out);
        }
        exchange.close();
    }
}
