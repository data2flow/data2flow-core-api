package net.java21.data2flow.core.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * data2flow-auth 대역(API-IAM-37b {@code POST /internal/auth/blacklists}). 받은 본문과 호출자 헤더를 기록하고,
 * {@link #failNext(int)}로 다음 n번을 503으로 실패시킨다(아웃박스 재시도 확인).
 */
public final class AuthStubServer {

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();

    public AuthStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.createContext("/internal/auth/blacklists", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                received.add(new Received(body, exchange.getRequestHeaders().getFirst("X-CALLER-SERVICE")));
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public void reset() {
        received.clear();
        failures.set(0);
    }

    public void failNext(int count) {
        failures.set(count);
    }

    /** 받은 요청 */
    public record Received(String body, String callerService) {
    }
}
