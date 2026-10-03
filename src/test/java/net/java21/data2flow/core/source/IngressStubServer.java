package net.java21.data2flow.core.source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * data2flow-ingress 대역(실제 브로커에 붙지 않는다):
 * <ul>
 *   <li>API-DSC-51 {@code POST /internal/ingress/sources/test}: 받은 본문을 기록하고 {@link #testResponse}를 돌려준다.
 *       {@link #holdTests()}로 응답을 붙잡아 동시 실행 수 제한을 확인한다</li>
 *   <li>API-DSC-52 {@code GET /internal/ingress/sources/{id}/live}: {@link #liveEvents}의 SSE 줄을 보내고, {@link #keepLiveOpen}이면
 *       닫지 않고 기다린다</li>
 * </ul>
 */
public final class IngressStubServer {

    private final HttpServer server;
    final List<String> testBodies = new CopyOnWriteArrayList<>();
    final List<String> callers = new CopyOnWriteArrayList<>();
    final List<String> livePaths = new CopyOnWriteArrayList<>();
    final AtomicReference<String> testResponse = new AtomicReference<>();
    final AtomicInteger testStatus = new AtomicInteger(200);
    final AtomicReference<String> liveEvents = new AtomicReference<>("");
    final AtomicInteger liveStatus = new AtomicInteger(200);
    volatile boolean keepLiveOpen;
    private volatile CountDownLatch hold = new CountDownLatch(0);
    final AtomicInteger waiting = new AtomicInteger();

    public IngressStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/internal/ingress/sources/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        callers.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-CALLER-SERVICE")));
        if (path.equals("/internal/ingress/sources/test")) {
            testBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            waiting.incrementAndGet();
            try {
                hold.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                waiting.decrementAndGet();
            }
            byte[] body = String.valueOf(testResponse.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(testStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        if (path.endsWith("/live")) {
            livePaths.add(exchange.getRequestURI().toString());
            if (liveStatus.get() != 200) {
                exchange.sendResponseHeaders(liveStatus.get(), -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                out.write(liveEvents.get().getBytes(StandardCharsets.UTF_8));
                out.flush();
                while (keepLiveOpen) {
                    out.write(": keep\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    hold.await(200, TimeUnit.MILLISECONDS);
                }
            } catch (IOException | InterruptedException ex) {
                // 클라이언트가 끊음
            } finally {
                exchange.close();
            }
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 다음 테스트 요청들을 {@link #release()}까지 붙잡는다 */
    void holdTests() {
        hold = new CountDownLatch(1);
    }

    void release() {
        hold.countDown();
    }

    void reset() {
        release();
        testBodies.clear();
        callers.clear();
        livePaths.clear();
        testStatus.set(200);
        liveStatus.set(200);
        keepLiveOpen = false;
        liveEvents.set("");
        testResponse.set("""
                {"header":{"isSuccessful":true,"resultCode":"OK","resultMessage":"OK"},
                 "response":{"steps":[{"name":"DNS","status":"OK","ms":3},{"name":"TCP","status":"OK","ms":4},
                  {"name":"TLS","status":"OK","ms":9},{"name":"AUTH","status":"OK","ms":5},{"name":"SUBSCRIBE","status":"OK","ms":2}],
                  "preview":[{"at":"2026-10-03T00:00:01Z","topic":"application/1/device/24e1/event/up","size":120,
                   "rawExcerpt":"{\\"object\\":{\\"temperature\\":22.3}}"}],"lossPossible":false}}""");
    }
}
