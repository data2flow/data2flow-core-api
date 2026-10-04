package net.java21.data2flow.core.external.provider;

import net.java21.data2flow.core.support.StubHttpServer;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** 외부 연동 계약 시험 공통: 공개 문서 예시 응답(src/test/resources/contracts)을 대역 HTTP 서버로 돌려준다(실제 네트워크·키 없음) */
final class ProviderContractSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private ProviderContractSupport() {
    }

    static String sample(String path) {
        try {
            return new ClassPathResource("contracts/" + path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static StubHttpServer.Reply reply(String path) {
        return new StubHttpServer.Reply(200, sample(path), Map.of());
    }

    static DataGoKrClient client(StubHttpServer server, String key) {
        return new DataGoKrClient(server.baseUrl(), key, Duration.ofSeconds(5), JSON);
    }
}
