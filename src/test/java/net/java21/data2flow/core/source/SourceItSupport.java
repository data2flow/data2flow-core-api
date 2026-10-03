package net.java21.data2flow.core.source;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.event.EventPayload;
import net.java21.data2flow.core.messaging.service.CoreEventConsumer;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSC 통합 테스트 기반: 공통 기반(PostgreSQL 18 + RabbitMQ) + ingress 대역 서버. 모든 DSC IT가 이 클래스를 상속해 Spring 컨텍스트를 하나만 더 만든다.
 * 조직(org)·관리자(admin, ADMIN)·통합 담당(integrator, INTEGRATOR)·운영자(operator, OPERATOR)를 매번 새로 만든다.
 */
public abstract class SourceItSupport extends IntegrationTestSupport {

    protected static final IngressStubServer INGRESS = new IngressStubServer();

    @DynamicPropertySource
    static void ingress(DynamicPropertyRegistry registry) {
        registry.add("data2flow.core.ingress-base-url", INGRESS::baseUrl);
    }

    @Autowired
    protected CoreEventConsumer consumer;
    @Autowired
    protected MessageCodec codec;

    protected long org;
    protected long admin;
    protected long integrator;
    protected long operator;

    @BeforeEach
    void sourceFixtures() {
        INGRESS.reset();
        org = fx.organization("dsc");
        admin = fx.user(org, "dsc.admin", "ADMIN");
        integrator = fx.user(org, "dsc.integrator", "INTEGRATOR");
        operator = fx.user(org, "dsc.operator", "OPERATOR");
    }

    /** MQTT 구독 소스 생성 본문(아카데미 iot-data 형태: wss + HTTP Basic 헤더) */
    protected static String mqttBody(String code, String extra) {
        return """
                {"code":"%s","name":"소스 %s","type":"MQTT_SUBSCRIBE","connectorKey":"mqtt",
                 "connection":{"url":"wss://broker.test:443/mqtt","protocolVersion":"5.0","qos":1,"keepaliveSec":60,"cleanStart":false,
                               "auth":"HEADER","headerName":"Authorization"},
                 "topics":[{"topic":"application/+/device/+/event/up","qos":1}],
                 "secret":{"kind":"HEADER","value":"student:s3cr3t-Pa55"},
                 "decoderKey":"chirpstack-v4"%s}""".formatted(code, code, extra == null ? "" : "," + extra);
    }

    /** 소스를 만들고 ID */
    protected long createSource(String body) throws Exception {
        MvcResult r = mvc.perform(as(org, integrator, json(post("/core/sources"), body))).andExpect(status().isCreated()).andReturn();
        return Long.parseLong(JsonPath.read(r.getResponse().getContentAsString(), "$.response.id"));
    }

    /** 도메인 이벤트를 core.events 소비자에 직접 넣는다 */
    protected boolean deliver(EventType type, long orgId, EventPayload payload, Instant at) {
        DomainEvent<EventPayload> event = new DomainEvent<>(1, java.util.UUID.randomUUID(), type.routingKey(), orgId, at, null, payload);
        return consumer.onMessage(codec.write(event));
    }

    /** 아웃박스에 쌓인 메시지(라우팅 키 또는 CONFIG) 페이로드 */
    protected List<String> outbox(long orgId, String routingKey) {
        return jdbc.sql("""
                        SELECT payload::text FROM data2flow_core.outboxes
                         WHERE organization_id = :org AND (routing_key = :rk OR (:rk = 'CONFIG' AND kind = 'CONFIG')) ORDER BY id""")
                .param("org", orgId).param("rk", routingKey).query(String.class).list().stream()
                .map(p -> p.replace("\": ", "\":").replace(", \"", ",\"")).toList();
    }

    protected String read(MvcResult r, String path) throws Exception {
        Object v = JsonPath.read(r.getResponse().getContentAsString(), path);
        return v == null ? null : v.toString();
    }
}
