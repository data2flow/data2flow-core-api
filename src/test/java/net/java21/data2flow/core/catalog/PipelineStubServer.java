package net.java21.data2flow.core.catalog;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * data2flow-pipeline 대역(API-TSD-51 {@code POST /internal/pipeline/telemetry/remap-metric} → 202 {@code {"jobId":"pjob-n"}}).
 * 받은 본문과 호출자 헤더를 기록하고 {@link #failNext(int)}로 다음 n번을 503으로 실패시킨다.
 */
final class PipelineStubServer {

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger jobs = new AtomicInteger();

    PipelineStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.createContext("/internal/pipeline/telemetry/remap-metric", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                received.add(new Received(body, exchange.getRequestHeaders().getFirst("X-CALLER-SERVICE")));
                byte[] response = ("{\"jobId\":\"pjob-" + jobs.incrementAndGet() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    void reset() {
        received.clear();
        failures.set(0);
    }

    void failNext(int count) {
        failures.set(count);
    }

    record Received(String body, String callerService) {
    }
}
