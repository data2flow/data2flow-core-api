package net.java21.data2flow.core.outbox.service;

import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.outbox.repository.OutboxRepository.OutboxMessage;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * data2flow-auth 블랙리스트 등록(API-IAM-37b {@code POST /internal/auth/blacklists}). 내부망 HTTP, 토큰 없이
 * {@code X-CALLER-SERVICE}만 붙인다(ADR-021). auth는 Redis 등록과 EVT-IAM-03 발행을 하고, 같은 요청이 다시 와도 결과가 같다(멱등).
 */
@Component
public class AuthBlacklistDispatcher implements OutboxDispatcher {

    static final String CALLER = "data2flow-core-api";

    private final RestClient client;

    public AuthBlacklistDispatcher(CoreProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = RestClient.builder().baseUrl(properties.authBaseUrl()).requestFactory(factory).build();
    }

    @Override
    public boolean supports(OutboxMessage message) {
        return OutboxWriter.AUTH_TARGET.equals(message.exchange());
    }

    @Override
    public void dispatch(OutboxMessage message) {
        client.post().uri("/internal/auth/blacklists")
                .contentType(MediaType.APPLICATION_JSON)
                .header(DataflowHeaders.CALLER_SERVICE, CALLER)
                .body(message.payload())
                .retrieve()
                .toBodilessEntity();
    }
}
