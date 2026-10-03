package net.java21.data2flow.core.organization;

import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.outbox.service.OutboxRelay;
import net.java21.data2flow.core.outbox.service.OutboxWriter;
import net.java21.data2flow.core.support.Fixtures;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-030 staging 전용 조직: staging·prod가 DB 하나를 함께 쓰면 ACTIVE 조직이 둘이다. 배포 설정
 * {@code data2flow.core.organization-code}(DATA2FLOW_ORGANIZATION_CODE)가 공개 경로·로그인·아웃박스 릴레이의 조직을 정한다.
 */
@TestPropertySource(properties = "data2flow.core.organization-code=org-stg")
class DeploymentOrganizationIT extends IntegrationTestSupport {

    @Autowired
    DeploymentOrganization deployment;
    @Autowired
    OutboxWriter outbox;
    @Autowired
    OutboxRelay relay;
    @Autowired
    TransactionTemplate tx;

    @Test
    @DisplayName("[ADR-030][IAM-01.08] 조직이 둘이어도 배포 조직(org-stg)의 가입 설정을 답한다 — API-IAM-74")
    void publicSignupSettingsUseDeploymentOrganization() throws Exception {
        long prod = fx.organization("prod");
        long stg = fx.organization("stg");
        fx.policy(prod, "signup_request_enabled", false);
        fx.policy(stg, "signup_request_enabled", true);

        assertThat(deployment.current()).get().extracting(o -> o.id()).isEqualTo(stg);
        assertThat(deployment.restriction()).hasValue(stg);
        mvc.perform(get("/core/public/signup-settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.signupRequestEnabled").value(true));
    }

    @Test
    @DisplayName("[ADR-030][IAM-02.01] 두 조직에 같은 로그인 아이디가 있어도 배포 조직의 계정으로 로그인한다 — API-IAM-30")
    void loginUsesDeploymentOrganization() throws Exception {
        long prod = fx.organization("prod");
        long stg = fx.organization("stg");
        fx.user(prod, "admin", "ADMIN");
        long stgAdmin = fx.user(stg, "admin", "ADMIN");

        mvc.perform(json(post("/internal/core/users/verify-credentials").header("X-CALLER-SERVICE", "data2flow-auth"),
                        "{\"loginId\":\"admin\",\"password\":\"" + Fixtures.PASSWORD + "\",\"ip\":\"10.0.0.7\",\"userAgent\":\"JUnit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.userId").value(Long.toString(stgAdmin)))
                .andExpect(jsonPath("$.response.orgId").value(Long.toString(stg)));
    }

    @Test
    @DisplayName("[ADR-030][reliability ⑦] 아웃박스 릴레이는 배포 조직의 행만 보낸다(prod 이벤트를 staging vhost로 보내지 않음)")
    void relaySendsOnlyDeploymentOrganizationRows() {
        long prod = fx.organization("prod");
        long stg = fx.organization("stg");
        tx.executeWithoutResult(s -> {
            outbox.event(prod, "it.deployment.prod", Map.of("k", "v"));
            outbox.event(stg, "it.deployment.stg", Map.of("k", "v"));
        });

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT organization_id FROM data2flow_core.outboxes WHERE sent_at IS NULL").query(Long.class).list())
                .containsExactly(prod);
    }

    @Test
    @DisplayName("[ADR-030] 설정한 코드의 조직이 아직 없으면 어떤 조직도 고르지 않는다(릴레이도 보내지 않음)")
    void unknownCodeSelectsNothing() {
        long prod = fx.organization("prod");
        tx.executeWithoutResult(s -> outbox.event(prod, "it.deployment.prod", Map.of("k", "v")));

        assertThat(deployment.current()).isEmpty();
        assertThat(deployment.restriction()).hasValue(DeploymentOrganization.NO_ORGANIZATION);
        assertThat(deployment.includes(prod)).isFalse();
        assertThat(relay.relayOnce()).isZero();
    }
}
