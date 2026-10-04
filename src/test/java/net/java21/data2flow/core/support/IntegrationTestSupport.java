package net.java21.data2flow.core.support;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 통합 테스트 기반(design/testing/backend.md §3 "서비스 전체 *IT"): Testcontainers PostgreSQL 18 + RabbitMQ 3.13, 테스트 SMTP(GreenMail),
 * auth 대역 HTTP 서버. 컨테이너는 JVM에 하나씩만 띄워 모든 IT가 함께 쓴다. 실제 s3·s4 인프라에는 붙지 않는다.
 * 테스트마다 업무 테이블(data2flow_core 전체 + 테스트용 data2flow_pipeline)을 비우고(감사 로그는 INSERT 전용이라 남는다 — 조직 ID가
 * 매번 달라 섞이지 않는다) 시계를 T0로 되돌린다. pipeline 소유 스키마는 {@link TestDatabase#createPipelineSchema}가 문서 DDL로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestBeans.class)
public abstract class IntegrationTestSupport {

    // 문맥(캐시)마다 커넥션 풀이 생기므로 기본 100개로는 모자랄 수 있다(실제 서버 포트 문맥 FlowLiveViewIT 포함)
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withCommand("postgres", "-c", "max_connections=300");
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    protected static final GreenMail MAIL = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
    protected static final AuthStubServer AUTH = new AuthStubServer();

    static {
        POSTGRES.start();
        RABBIT.start();
        MAIL.start();
        TestDatabase.createPipelineSchema(POSTGRES);
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
        registry.add("data2flow.core.auth-base-url", AUTH::baseUrl);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected Fixtures fx;
    /** M2 테스트 데이터(공간·소스·모델·기기·시계열) */
    protected M2Data data;

    @BeforeEach
    void resetState() throws Exception {
        clock.set(MutableClock.T0);
        TestDatabase.truncateAll(jdbc);
        data = new M2Data(jdbc);
        MAIL.purgeEmailFromAllMailboxes();
        AUTH.reset();
    }

    /** gateway가 넣는 신원 헤더를 붙인 요청 */
    protected static MockHttpServletRequestBuilder as(long orgId, long userId, MockHttpServletRequestBuilder builder) {
        return builder.header("X-USER-ID", Long.toString(userId)).header("X-ORG-ID", Long.toString(orgId))
                .header(HttpHeaders.ACCEPT_LANGUAGE, "ko");
    }

    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected static MockHttpServletRequestBuilder req(org.springframework.http.HttpMethod method, String path) {
        return request(method, path);
    }

    protected static List<MimeMessage> mails() {
        return List.of(MAIL.getReceivedMessages());
    }

    protected static int smtpPort() {
        return MAIL.getSmtp().getPort();
    }

    /** 감사 기록 수(조직·행위) */
    protected long auditCount(long orgId, String action) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.audit_logs WHERE organization_id = :org AND action = :action")
                .param("org", orgId).param("action", action).query(Long.class).single();
    }
}
