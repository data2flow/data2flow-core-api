package net.java21.data2flow.core.support;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.event.EventPayload;
import net.java21.data2flow.core.messaging.service.CoreEventConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M3 가상 폐루프 통합 테스트 기반: 공통 기반(PostgreSQL 18 + RabbitMQ) + 내부 서비스 대역(action·flow-engine·simulator를 한 대역 서버가 맡는다).
 * 조직(org)·관리자(admin)·통합 담당(integrator)·운영자(operator)·분석가(analyst)·조회자(viewer)를 매번 새로 만든다.
 */
public abstract class LoopItSupport extends IntegrationTestSupport {

    protected static final StubHttpServer STUB = new StubHttpServer();

    @DynamicPropertySource
    static void loop(DynamicPropertyRegistry registry) {
        registry.add("data2flow.core.loop.action-base-url", STUB::baseUrl);
        registry.add("data2flow.core.loop.flow-engine-base-url", STUB::baseUrl);
        registry.add("data2flow.core.loop.simulator-base-url", STUB::baseUrl);
    }

    @Autowired
    protected CoreEventConsumer consumer;
    @Autowired
    protected MessageCodec codec;

    protected long org;
    protected long admin;
    protected long integrator;
    protected long operator;
    protected long analyst;
    protected long viewer;

    @BeforeEach
    void loopFixtures() {
        STUB.reset();
        org = fx.organization("loop");
        admin = fx.user(org, "loop.admin", "ADMIN");
        integrator = fx.user(org, "loop.integrator", "INTEGRATOR");
        operator = fx.user(org, "loop.operator", "OPERATOR");
        analyst = fx.user(org, "loop.analyst", "ANALYST");
        viewer = fx.user(org, "loop.viewer", "VIEWER");
    }

    /** 도메인 이벤트를 core.events 소비자에 직접 넣는다 */
    protected boolean deliver(EventType type, long orgId, EventPayload payload, Instant at) {
        DomainEvent<EventPayload> event = new DomainEvent<>(1, UUID.randomUUID(), type.routingKey(), orgId, at, null, payload);
        return consumer.onMessage(codec.write(event));
    }

    /** 아웃박스에 쌓인 설정 변경(data2flow.config) 페이로드 */
    protected List<String> configMessages(long orgId) {
        return jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = 'CONFIG' ORDER BY id")
                .param("org", orgId).query(String.class).list();
    }

    /** 아웃박스에 쌓인 도메인 이벤트(라우팅 키) */
    protected List<String> events(long orgId, String routingKey) {
        return jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = 'EVENT'"
                        + " AND routing_key = :key ORDER BY id")
                .param("org", orgId).param("key", routingKey).query(String.class).list();
    }

    protected static String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    protected static <T> T read(MvcResult r, String path) {
        return JsonPath.read(body(r), path);
    }
}
