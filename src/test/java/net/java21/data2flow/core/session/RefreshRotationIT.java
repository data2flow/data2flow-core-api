package net.java21.data2flow.core.session;

import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.outbox.service.OutboxRelay;
import net.java21.data2flow.core.support.Fixtures;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IAM-07.03 Refresh 계보(API-IAM-35·36·37·39a)·IAM-03.02/07.08 활성 로그인·IAM-03.03 강제 종료·IAM-07.05 폐기 원천.
 * design/testing/backend.md §4.1 RefreshRotationIT: ROTATED, GRACE(30초), 재사용 시 계보 폐기 + bl:sid 등록.
 */
class RefreshRotationIT extends IntegrationTestSupport {

    @Autowired
    OutboxRelay relay;

    private long org;
    private long user;

    private String register(String sid) throws Exception {
        return register(sid, UUID.randomUUID().toString(), "Firefox on Linux");
    }

    private String register(String sid, String jti, String userAgent) throws Exception {
        Instant now = clock.instant();
        mvc.perform(json(post("/internal/core/refresh-tokens"), """
                        {"jti":"%s","sid":"%s","userId":"%d","orgId":"%d","tokenHash":"%s","expiresAt":"%s","absoluteExpiresAt":"%s",
                         "ip":"10.1.1.1","userAgent":"%s"}""".formatted(jti, sid, user, org, Tokens.sha256Hex(jti),
                        now.plus(Duration.ofHours(6)), now.plus(Duration.ofHours(12)), userAgent)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/internal/core/refresh-tokens/" + jti))
                .andExpect(jsonPath("$.response.sid").value(sid));
        return jti;
    }

    private ResultActions rotate(String presented, String next) throws Exception {
        return mvc.perform(json(post("/internal/core/refresh-tokens/rotate"), """
                {"presentedJti":"%s","nextJti":"%s","nextTokenHash":"%s","nextExpiresAt":"%s"}"""
                .formatted(presented, next, Tokens.sha256Hex(next), clock.instant().plus(Duration.ofHours(6)))));
    }

    private void setUp() {
        org = fx.organization("rot");
        user = fx.user(org, "kim.op", "OPERATOR");
    }

    @Test
    @DisplayName("[IAM-07.03][AT-IAM-03.1] 정상 재발급은 ROTATED, 이전 토큰은 rotated, 새 토큰이 유효 — TC-IAM-189")
    void rotated() throws Exception {
        setUp();
        String sid = UUID.randomUUID().toString();
        String first = register(sid);
        String second = UUID.randomUUID().toString();

        clock.advance(Duration.ofMinutes(10));
        rotate(first, second).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.decision").value("ROTATED"))
                .andExpect(jsonPath("$.response.effectiveJti").value(second))
                .andExpect(jsonPath("$.response.sid").value(sid))
                .andExpect(jsonPath("$.response.userId").value(Long.toString(user)));
        assertThat(jdbc.sql("SELECT rotated_at IS NOT NULL FROM data2flow_core.refresh_tokens WHERE jti = CAST(:j AS uuid)")
                .param("j", first).query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("[IAM-07.03][AT-IAM-03.2] 회전 후 30초 안의 재사용(여러 탭)은 GRACE로 최신 토큰을 돌려준다")
    void graceWithin30Seconds() throws Exception {
        setUp();
        String sid = UUID.randomUUID().toString();
        String first = register(sid);
        String second = UUID.randomUUID().toString();
        rotate(first, second).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(29));
        rotate(first, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.decision").value("GRACE"))
                .andExpect(jsonPath("$.response.effectiveJti").value(second));
    }

    @Test
    @DisplayName("[IAM-07.03][AT-IAM-03.5] 31초 지난 이전 Refresh 재사용 → 401 AUTH_SESSION_REVOKED, 계보 전체 폐기, 감사 REFRESH_REUSED, bl:sid 등록·보안 경고")
    void reuseRevokesLineage() throws Exception {
        setUp();
        String sid = UUID.randomUUID().toString();
        String first = register(sid);
        String second = UUID.randomUUID().toString();
        rotate(first, second).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(31));
        rotate(first, UUID.randomUUID().toString()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_SESSION_REVOKED"));

        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.refresh_tokens WHERE session_id = CAST(:s AS uuid) AND revoked_at IS NULL")
                .param("s", sid).query(Long.class).single()).isZero();
        assertThat(auditCount(org, "REFRESH_REUSED")).isEqualTo(1);
        rotate(second, UUID.randomUUID().toString()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_SESSION_REVOKED"));

        relay.relayOnce();
        assertThat(AUTH.received()).anySatisfy(r -> {
            assertThat(r.body()).contains(sid).contains("REUSE_DETECTED");
            assertThat(r.callerService()).isEqualTo("data2flow-core-api");
        });
    }

    @Test
    @DisplayName("[IAM-03.01][AT-IAM-03.3·03.4] 유휴 30분·절대 12시간이 지나면 401 AUTH_SESSION_EXPIRED")
    void expiredSession() throws Exception {
        setUp();
        String first = register(UUID.randomUUID().toString());
        clock.advance(Duration.ofMinutes(31));
        rotate(first, UUID.randomUUID().toString()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_SESSION_EXPIRED"));

        clock.set(clock.instant().minus(Duration.ofMinutes(31)));
        String sid = UUID.randomUUID().toString();
        String jti = register(sid);
        for (int i = 0; i < 25; i++) {
            clock.advance(Duration.ofMinutes(29));
            String next = UUID.randomUUID().toString();
            ResultActions result = rotate(jti, next);
            if (clock.instant().isAfter(MutableClockT0().plus(Duration.ofHours(12)))) {
                result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.header.resultCode").value("AUTH_SESSION_EXPIRED"));
                return;
            }
            result.andExpect(status().isOk());
            jti = next;
        }
    }

    private Instant MutableClockT0() {
        return net.java21.data2flow.core.support.MutableClock.T0;
    }

    @Test
    @DisplayName("[IAM-07.03] 없는 jti 회전은 401 AUTH_TOKEN_INVALID, 같은 jti 등록 재시도는 멱등, 비활성 사용자 등록은 401")
    void registerAndUnknown() throws Exception {
        setUp();
        String sid = UUID.randomUUID().toString();
        String jti = register(sid);
        register(sid, jti, "Firefox on Linux");
        rotate(UUID.randomUUID().toString(), UUID.randomUUID().toString()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_TOKEN_INVALID"));
        jdbc.sql("UPDATE data2flow_core.app_users SET status = 'DISABLED' WHERE id = :id").param("id", user).update();
        String other = UUID.randomUUID().toString();
        mvc.perform(json(post("/internal/core/refresh-tokens"), """
                        {"jti":"%s","sid":"%s","userId":"%d","orgId":"%d","tokenHash":"%s","expiresAt":"%s","absoluteExpiresAt":"%s"}"""
                        .formatted(other, sid, user, org, Tokens.sha256Hex(other), clock.instant().plusSeconds(60), clock.instant().plusSeconds(120))))
                .andExpect(status().isUnauthorized());
        rotate(jti, other).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.header.resultCode").value("AUTH_SESSION_REVOKED"));
    }

    @Test
    @DisplayName("[IAM-07.05][API-IAM-37·39a] 로그아웃 폐기(jti·sid)는 멱등 204이고 최근 폐기 목록에 sid·jti가 나온다(Redis 재적재)")
    void logoutAndRevocations() throws Exception {
        setUp();
        String sid = UUID.randomUUID().toString();
        String jti = register(sid);
        String sid2 = UUID.randomUUID().toString();
        String jti2 = register(sid2);

        mvc.perform(json(delete("/internal/core/refresh-tokens/" + jti), "{\"reason\":\"LOGOUT\"}")).andExpect(status().isNoContent());
        mvc.perform(delete("/internal/core/refresh-tokens/" + jti)).andExpect(status().isNoContent());
        mvc.perform(json(delete("/internal/core/sessions/" + sid2), "{\"reason\":\"LOGOUT\"}")).andExpect(status().isNoContent());
        mvc.perform(json(delete("/internal/core/sessions/" + sid2), "{\"reason\":\"NOPE\"}")).andExpect(status().isBadRequest());

        mvc.perform(get("/internal/core/revocations").param("since", clock.instant().minus(Duration.ofMinutes(5)).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sids.length()").value(2))
                .andExpect(jsonPath("$.response.jtis[?(@ == '%s')]".formatted(jti)).exists())
                .andExpect(jsonPath("$.response.jtis[?(@ == '%s')]".formatted(jti2)).exists());
        clock.advance(Duration.ofHours(2));
        mvc.perform(get("/internal/core/revocations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sids.length()").value(0));
    }

    @Test
    @DisplayName("[IAM-03.02][AT-IAM-08.4] 활성 로그인은 계열당 한 줄, 기기 B만 종료하면 B의 sid만 폐기 — TC-IAM-048·118·201")
    void mySessions() throws Exception {
        setUp();
        String sidA = UUID.randomUUID().toString();
        String jtiA = register(sidA, UUID.randomUUID().toString(), "Chrome on Windows");
        String sidB = UUID.randomUUID().toString();
        register(sidB, UUID.randomUUID().toString(), "Safari on iOS");
        rotate(jtiA, UUID.randomUUID().toString()).andExpect(status().isOk());

        mvc.perform(as(org, user, get("/core/accounts/me/sessions")).header("X-SESSION-ID", sidA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(2))
                .andExpect(jsonPath("$.response[?(@.sid == '%s')].current".formatted(sidA)).value(true))
                .andExpect(jsonPath("$.response[?(@.sid == '%s')].userAgent".formatted(sidB)).value("Safari on iOS"));

        mvc.perform(as(org, user, delete("/core/accounts/me/sessions/" + sidB))).andExpect(status().isNoContent());
        mvc.perform(as(org, user, get("/core/accounts/me/sessions"))).andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].sid").value(sidA));

        long other = fx.user(org, "other.user", "VIEWER");
        mvc.perform(as(org, other, delete("/core/accounts/me/sessions/" + sidA))).andExpect(status().isNotFound());
        mvc.perform(as(org, user, delete("/core/accounts/me/sessions/not-a-uuid"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[IAM-01.09][AT-IAM-08.2] 기기 A에서 비밀번호 변경 → 기기 A는 유지, 기기 B는 폐기, auth에 bl:sid 등록 — TC-IAM-046")
    void passwordChangeKeepsCurrent() throws Exception {
        setUp();
        String sidA = UUID.randomUUID().toString();
        register(sidA);
        String sidB = UUID.randomUUID().toString();
        register(sidB);

        mvc.perform(as(org, user, json(put("/core/accounts/me/password"),
                        "{\"currentPassword\":\"" + Fixtures.PASSWORD + "\",\"newPassword\":\"Brand-New-Secure#2026\",\"keepCurrentSession\":true}"))
                        .header("X-SESSION-ID", sidA))
                .andExpect(status().isNoContent());

        assertThat(activeTokens(sidA)).isEqualTo(1);
        assertThat(activeTokens(sidB)).isZero();
        relay.relayOnce();
        assertThat(AUTH.received()).hasSize(1);
        assertThat(AUTH.received().get(0).body()).contains(sidB).doesNotContain(sidA).contains("PASSWORD_CHANGED");
    }

    @Test
    @DisplayName("[IAM-03.03][AT-IAM-04.2] ADMIN이 사용자의 모든 세션을 강제 종료 → 두 기기 모두 폐기, 다른 조직 사용자는 404 USER_NOT_FOUND — TC-IAM-124~126")
    void adminRevokeAll() throws Exception {
        setUp();
        long admin = fx.user(org, "boss.admin", "ADMIN");
        register(UUID.randomUUID().toString());
        register(UUID.randomUUID().toString());

        mvc.perform(as(org, admin, post("/core/users/" + user + "/sessions/revoke-all")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.revokedSessions").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.refresh_tokens WHERE revoked_at IS NULL").query(Long.class).single()).isZero();

        long otherOrg = fx.organization("other");
        long stranger = fx.user(otherOrg, "stranger", "VIEWER");
        mvc.perform(as(org, admin, post("/core/users/" + stranger + "/sessions/revoke-all")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("USER_NOT_FOUND"));
        mvc.perform(as(org, user, post("/core/users/" + user + "/sessions/revoke-all")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("[IAM-07.05] auth가 잠시 실패해도 아웃박스가 다음 주기에 다시 보낸다(폐기 유실 없음)")
    void outboxRetriesAuth() throws Exception {
        setUp();
        long admin = fx.user(org, "boss.admin", "ADMIN");
        register(UUID.randomUUID().toString());
        mvc.perform(as(org, admin, post("/core/users/" + user + "/sessions/revoke-all"))).andExpect(status().isOk());

        AUTH.failNext(1);
        relay.relayOnce();
        assertThat(AUTH.received()).isEmpty();
        assertThat(jdbc.sql("SELECT attempts FROM data2flow_core.outboxes WHERE exchange = 'data2flow-auth'").query(Integer.class).single())
                .isEqualTo(1);
        relay.relayOnce();
        assertThat(AUTH.received()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE sent_at IS NULL").query(Long.class).single()).isZero();
    }

    private long activeTokens(String sid) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.refresh_tokens WHERE session_id = CAST(:s AS uuid) AND revoked_at IS NULL")
                .param("s", sid).query(Long.class).single();
    }
}
