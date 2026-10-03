package net.java21.data2flow.core.ingest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * data2flow-pipeline 내부 API 대역(API-ING-22·23, 폐기). 받은 요청(경로·본문·호출자 헤더)을 기록하고, 경로별 응답을 테스트가 정한다.
 * {@link #holdNext()}로 다음 재처리 요청을 붙잡아 두면 동시 요청 시험(TC-ING-089)을 할 수 있다.
 */
public final class PipelineStubServer {

    /** 응답 */
    public record Reply(int status, String body) {
    }

    /** 받은 요청 */
    public record Received(String path, String body, String callerService) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Function<Received, Reply> responder = PipelineStubServer::defaults;
    private volatile CountDownLatch hold;

    public PipelineStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/internal/pipeline/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Received request = new Received(exchange.getRequestURI().getPath(), body, exchange.getRequestHeaders().getFirst("X-CALLER-SERVICE"));
        received.add(request);
        CountDownLatch latch = hold;
        if (latch != null && request.path().endsWith("/reprocess-items")) {
            hold = null;
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        Reply reply = responder.apply(request);
        String text = reply.status() / 100 == 2 ? envelope(reply.body()) : reply.body();
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    /** 기본 응답: 재처리 항목은 모두 OK, 작업 생성 202 QUEUED, 취소 CANCELLING, 폐기는 요청 수만큼 */
    static Reply defaults(Received request) {
        String path = request.path();
        if (path.endsWith("/reprocess-items")) {
            return new Reply(200, itemResults(request.body(), "OK", null));
        }
        if (path.endsWith("/cancel")) {
            String id = path.replaceAll(".*/reprocess-jobs/(\\d+)/cancel", "$1");
            return new Reply(200, "{\"jobId\":" + id + ",\"status\":\"CANCELLING\"}");
        }
        if (path.endsWith("/reprocess-jobs")) {
            return new Reply(202, "{\"jobId\":4242,\"status\":\"QUEUED\",\"estimatedCount\":120}");
        }
        if (path.endsWith("/dlq-items/discard")) {
            int start = request.body().indexOf("\"dlqItemIds\":[");
            String ids = request.body().substring(start + 14, request.body().indexOf(']', start));
            return new Reply(200, "{\"discarded\":" + (ids.isBlank() ? 0 : ids.split(",").length) + "}");
        }
        return new Reply(404, null);
    }

    /** pipeline 실제 응답 모양: ApiResponse.success(...) → {@code {header:{isSuccessful,resultCode,resultMessage}, response}} */
    public static String envelope(String body) {
        if (body == null || body.startsWith("{\"header\"")) {
            return body;
        }
        return "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"SUCCESS\",\"resultMessage\":\"SUCCESS\"},\"response\":" + body + "}";
    }

    /** 재처리 요청 본문의 항목 ID마다 같은 결과를 만든다 */
    public static String itemResults(String body, String outcome, String errorCode) {
        StringBuilder out = new StringBuilder("{\"results\":[");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(body);
        int n = 0;
        while (m.find()) {
            out.append(n++ == 0 ? "" : ",").append("{\"id\":").append(m.group(1)).append(",\"outcome\":\"").append(outcome).append('"');
            if (errorCode != null) {
                out.append(",\"errorCode\":\"").append(errorCode).append('"');
            }
            out.append('}');
        }
        return out.append("],\"ok\":").append("OK".equals(outcome) ? n : 0).append(",\"failed\":").append("FAILED".equals(outcome) ? n : 0)
                .append(",\"skipped\":0}").toString();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public void respond(Function<Received, Reply> responder) {
        this.responder = responder;
    }

    /** 다음 재처리 요청을 release() 때까지 붙잡는다 */
    public CountDownLatch holdNext() {
        CountDownLatch latch = new CountDownLatch(1);
        hold = latch;
        return latch;
    }

    public void reset() {
        received.clear();
        responder = PipelineStubServer::defaults;
        CountDownLatch latch = hold;
        hold = null;
        if (latch != null) {
            latch.countDown();
        }
    }
}
