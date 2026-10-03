package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import net.java21.data2flow.core.source.domain.SourceModels.SecretMeta;
import net.java21.data2flow.core.source.repository.DataSourceRepository;
import net.java21.data2flow.core.source.repository.SourceReferenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DSC 서비스 단위 규칙: ADR-030 배포 조직 좁히기, 비밀값 요청 해석, 연결 테스트 결과 정리, 원본 메시지 필드 이름 — TC-DSC-001·032·086·094 단위 */
class SourceServiceUnitTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("[ADR-030][API-DSC-50] staging 배포는 staging 조직 소스만 읽고, 버전도 그 조직 것으로 비교한다(같으면 빈 값 → 204)")
    void runtimeConfigUsesDeploymentRestriction() {
        DataSourceRepository sources = mock(DataSourceRepository.class);
        ConfigVersions versions = mock(ConfigVersions.class);
        DeploymentOrganization deployment = mock(DeploymentOrganization.class);
        when(deployment.restriction()).thenReturn(OptionalLong.of(42));
        when(versions.sum(ConfigVersions.SOURCES, OptionalLong.of(42))).thenReturn(7L);
        when(sources.listForRuntime(any(), eq(OptionalLong.of(42)))).thenReturn(List.of());
        var service = new SourceRuntimeConfigService(sources, mock(SourceReferenceRepository.class), mock(SourceSecrets.class), versions, deployment);
        assertThat(service.runtimeConfig(null, 7L)).isEmpty();
        assertThat(service.runtimeConfig("ACTIVE", 6L)).get().satisfies(r -> assertThat(r.version()).isEqualTo(7));
        verify(sources).listForRuntime(List.of("ACTIVE"), OptionalLong.of(42));
        when(sources.findInternal(5L, OptionalLong.of(42))).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.ingestContext(5L)).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(SourceErrorCode.SOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("[DSC-01.05][BR-DSC-02] 비밀값 요청: 웹 인증 방식 이름 별칭, 빈 값은 변경 없음, 모르는 종류 400, 화면 지문은 ••••+4자리")
    void secretParsing() {
        assertThat(SourceSecrets.parse(JSON.readTree("{\"kind\":\"USERPASS\",\"value\":\"p\"}"))).containsOnlyKeys("PASSWORD");
        assertThat(SourceSecrets.parse(JSON.readTree("{\"kind\":\"ca_cert\",\"value\":\"c\"}"))).containsOnlyKeys("CA_CERT");
        assertThat(SourceSecrets.parse(JSON.readTree("{\"kind\":\"HEADER\",\"value\":\"\"}"))).isEmpty();
        assertThat(SourceSecrets.parse(null)).isEmpty();
        assertThat(SourceSecrets.parse(JSON.readTree("{\"cert\":\"c\",\"key\":\"\"}"))).containsOnlyKeys("CLIENT_CERT");
        assertThatThrownBy(() -> SourceSecrets.parse(JSON.readTree("{\"kind\":\"X\",\"value\":\"v\"}"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SourceSecrets.parse(JSON.readTree("\"v\""))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SourceSecrets.parse(JSON.readTree("{\"cert\":1}"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SourceSecrets.parse(JSON.readTree("{\"kind\":\"PASSWORD\",\"value\":\"" + "x".repeat(70000) + "\"}")))
                .isInstanceOf(BusinessException.class);
        assertThat(SourceSecrets.fingerprint(Secret.of("abc"))).hasSize(8).isEqualTo("ba7816bf");
        assertThat(SourceSecrets.mask("ba7816bf")).isEqualTo("••••16bf");
        assertThat(SourceSecrets.mask(null)).isNull();
        assertThat(SourceSecrets.primary("MQTT_SUBSCRIBE", "USERPASS", List.of()).configured()).isFalse();
        assertThat(SourceSecrets.primary("MQTT_SUBSCRIBE", "NONE", List.of(new SecretMeta("CA_CERT", "k", "12345678", null, null, false))).kind())
                .isEqualTo("CA_CERT");
        assertThat(SourceSecrets.allowedKinds("SIMULATION", "NONE")).isEmpty();
    }

    @Test
    @DisplayName("[DSC-02.05][DSC-09.11] 연결 테스트 결과: FAILED→FAIL, ok·stage 추가, 빈 결과는 ok=false")
    void normalizeTestResult() {
        JsonNode r = ConnectionTestService.normalize(JSON.readTree(
                "{\"steps\":[{\"name\":\"DNS\",\"status\":\"FAILED\"},{\"name\":\"TCP\",\"status\":\"SKIPPED\"}],\"stage\":\"x\"}"));
        assertThat(r.get("ok").asBoolean()).isFalse();
        assertThat(r.get("stage").asString()).isEqualTo("DNS");
        assertThat(r.at("/steps/0/status").asString()).isEqualTo("FAIL");
        assertThat(r.get("preview").isArray()).isTrue();
        JsonNode empty = ConnectionTestService.normalize(null);
        assertThat(empty.get("ok").asBoolean()).isFalse();
        JsonNode ok = ConnectionTestService.normalize(JSON.readTree("{\"steps\":[{\"name\":\"DNS\",\"status\":\"OK\"}],\"stage\":\"old\"}"));
        assertThat(ok.get("ok").asBoolean()).isTrue();
        assertThat(ok.has("stage")).isFalse();
    }

    @Test
    @DisplayName("[DSC-02.06] 원본 메시지 필드: size→sizeBytes, rawExcerpt→payload, truncated 계산, 이미 API 이름이면 그대로")
    void liveMessageFields() {
        JsonNode m = SourceLiveRelay.toMessage(JSON.readTree("{\"at\":\"t\",\"topic\":\"a\",\"rawExcerpt\":\"abc\",\"decoded\":{\"v\":1}}"));
        assertThat(m.get("receivedAt").asString()).isEqualTo("t");
        assertThat(m.get("sizeBytes").asLong()).isEqualTo(3);
        assertThat(m.get("truncated").asBoolean()).isFalse();
        assertThat(m.at("/decoded/v").asInt()).isEqualTo(1);
        JsonNode p = SourceLiveRelay.toMessage(JSON.readTree("{\"receivedAt\":\"t\",\"topic\":\"a\",\"payload\":\"x\",\"sizeBytes\":5,\"truncated\":false}"));
        assertThat(p.get("truncated").asBoolean()).isFalse();
        assertThat(p.get("payload").asString()).isEqualTo("x");
    }

    @Test
    @DisplayName("[DSC-02.03] 지표 구간 기본값(2시간 이하 1m, 2일 이하 5m, 그 밖 1h)과 목록 요약(최근 5분 평균, 실패율 0~1)")
    void statDefaults() {
        assertThat(SourceQueryService.bucket(null, Duration.ofHours(2))).isEqualTo(Duration.ofMinutes(1));
        assertThat(SourceQueryService.bucket("", Duration.ofDays(2))).isEqualTo(Duration.ofMinutes(5));
        assertThat(SourceQueryService.bucket(null, Duration.ofDays(3))).isEqualTo(Duration.ofHours(1));
        Instant now = Instant.parse("2026-10-03T01:00:30Z");
        var summary = SourceQueryService.summary(null, Map.of(Instant.parse("2026-10-03T00:59:00Z"), new long[]{5, 5},
                Instant.parse("2026-10-03T02:00:00Z"), new long[]{9, 0}, Instant.parse("2026-10-02T00:00:00Z"), new long[]{9, 0}), now);
        assertThat(summary.ratePerMin()).isEqualTo(1.0);
        assertThat(summary.decodeErrorRate1h()).isEqualTo(1.0);
        assertThat(summary.lastReceivedAt()).isNull();
        assertThat(SourceQueryService.split(java.util.Arrays.asList("a,b", null, " ", "A"))).containsExactly("A", "B");
    }
}
