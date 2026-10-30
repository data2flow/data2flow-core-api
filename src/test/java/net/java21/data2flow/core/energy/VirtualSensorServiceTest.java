package net.java21.data2flow.core.energy;

import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.analytics.service.AnalyticsClient;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.UpdateRequest;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.SpaceRow;
import net.java21.data2flow.core.energy.repository.VirtualSensorRepository;
import net.java21.data2flow.core.energy.repository.VirtualSensorRepository.ConfigRow;
import net.java21.data2flow.core.energy.repository.VirtualSensorRepository.SpaceGeometry;
import net.java21.data2flow.core.energy.service.EnergyAccess;
import net.java21.data2flow.core.energy.service.EnergyEvents;
import net.java21.data2flow.core.energy.service.VirtualSensorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Answers;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** ENE-02.01 가상 센서 설정 검사(TC-ENE-036): ACH는 공간 부피(면적 × 층고)와 CO2 입력이 있어야 켜진다. 설정값 범위, 상태 판정 */
class VirtualSensorServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final VirtualSensorRepository repo = mock(VirtualSensorRepository.class);
    private final EnergyAccess access = mock(EnergyAccess.class);
    private final RoleChecker roles = mock(RoleChecker.class);
    private final EnergyEvents events = mock(EnergyEvents.class);
    private final Audits audits = mock(Audits.class, Answers.RETURNS_DEEP_STUBS);
    private final VirtualSensorService service = new VirtualSensorService(repo, access, roles, events, mock(AnalyticsClient.class), audits, JSON,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        given(roles.currentUser()).willReturn(new CurrentUser(7, 1));
        given(access.space(any(), anyLong(), anyLong())).willReturn(new SpaceRow(31, "101호", "/1/10/31/", "ROOM"));
        given(repo.geometry(1, 31)).willReturn(Optional.of(new SpaceGeometry(31, new BigDecimal("50.00"), new BigDecimal("3.00"), "Asia/Seoul")));
        given(repo.inputDevices(1, 31, "co2")).willReturn(List.of(3001L));
    }

    private static UpdateRequest on(String params) {
        return new UpdateRequest(true, params == null ? null : JSON.readTree(params));
    }

    @ParameterizedTest(name = "면적 {0} · 층고 {1}")
    @CsvSource({"50.00,", ",3.00", ","})
    @DisplayName("[TC-ENE-036] 면적이나 층고가 없으면 ACH를 켤 수 없다(409 ENE_SPACE_VOLUME_REQUIRED), 저장·이벤트 없음")
    void volumeRequired(BigDecimal area, BigDecimal height) {
        given(repo.geometry(1, 31)).willReturn(Optional.of(new SpaceGeometry(31, area, height, "Asia/Seoul")));
        assertThatThrownBy(() -> service.update("31", "ach", on(null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(EnergyErrorCode.ENE_SPACE_VOLUME_REQUIRED));
        verify(repo, never()).upsert(anyLong(), anyLong(), anyString(), anyBoolean(), anyString(), any(), anyLong(), any());
        verify(events, never()).configChanged(anyLong(), any(), any());
    }

    @Test
    @DisplayName("[TC-ENE-036] 공간에 CO2 기기가 없으면 409 ENE_VIRTUAL_SENSOR_INPUT_MISSING(missing co2). 끄기는 검사하지 않는다")
    void inputMissing() {
        given(repo.inputDevices(1, 31, "co2")).willReturn(List.of());
        assertThatThrownBy(() -> service.update("31", "ACH", on(null))).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(EnergyErrorCode.ENE_VIRTUAL_SENSOR_INPUT_MISSING);
            assertThat(ex.getErrors()).extracting(e -> e.field() + ":" + e.code()).containsExactly("inputs:co2");
        });
        given(repo.geometry(1, 31)).willReturn(Optional.of(new SpaceGeometry(31, null, null, "Asia/Seoul")));
        given(repo.find(1, 31, "ACH")).willReturn(Optional.of(row(false, null)));
        assertThat(service.update("31", "ach", new UpdateRequest(false, null)).status()).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("[TC-ENE-036] 켜면 기본값을 채워 저장하고 EVT-ENE-07(VIRTUAL_SENSOR)을 낸다")
    void enable() {
        given(repo.find(1, 31, "ACH")).willReturn(Optional.of(row(true, null)));
        var view = service.update("31", "ach", on("{\"outdoorCo2Ppm\":450}"));
        verify(repo).upsert(eq(1L), eq(31L), eq("ACH"), eq(true), eq("{\"outdoorCo2Mode\":\"FIXED\",\"outdoorCo2Ppm\":450.0,\"minDecayMinutes\":30}"),
                eq(List.of("ach", "ventilation_m3h")), eq(7L), eq(NOW));
        verify(events).configChanged(1, net.java21.data2flow.contracts.message.event.EnergyConfigChanged.Scope.VIRTUAL_SENSOR, 31L);
        assertThat(view.status()).isEqualTo("OK");
        assertThat(view.volumeM3()).isEqualTo(150.0);
        assertThat(view.inputs().getFirst().deviceIds()).containsExactly("3001");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "{\"outdoorCo2Ppm\":200}|params.outdoorCo2Ppm",
            "{\"minDecayMinutes\":10}|params.minDecayMinutes",
            "{\"minDecayMinutes\":30.5}|params.minDecayMinutes",
            "{\"outdoorCo2Mode\":\"SOURCE\"}|params.outdoorCo2Mode",
            "{\"foo\":1}|params.foo"})
    @DisplayName("[ENE-02.01] ACH 설정값 범위 밖이면 400 INVALID_REQUEST")
    void invalidParams(String params, String field) {
        assertThatThrownBy(() -> service.update("31", "ach", on(params))).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST);
            assertThat(ex.getErrors()).extracting(e -> e.field()).containsExactly(field);
        });
    }

    @Test
    @DisplayName("[ENE-02.01] 아직 받지 않는 종류·없는 종류는 400, enabled가 없으면 400")
    void kindAndBody() {
        assertThatThrownBy(() -> service.update("31", "occupancy", on(null))).isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrors().getFirst().code()).isEqualTo("NOT_SUPPORTED");
        assertThatThrownBy(() -> service.update("31", "foo", on(null))).isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrors().getFirst().code()).isEqualTo("INVALID");
        assertThatThrownBy(() -> service.update("31", "ach", new UpdateRequest(null, null))).isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrors().getFirst().field()).isEqualTo("enabled");
    }

    @Test
    @DisplayName("[ENE-02.01] 상태: 설정 없음·꺼짐 DISABLED, 켜졌는데 지금 CO2 기기가 없거나 마지막 계산이 INPUT_MISSING이면 INPUT_MISSING")
    void status() {
        given(repo.list(1, 31)).willReturn(List.of());
        assertThat(service.list("31").getFirst().status()).isEqualTo("DISABLED");
        given(repo.list(1, 31)).willReturn(List.of(row(true, "INPUT_MISSING")));
        var missing = service.list("31").getFirst();
        assertThat(missing.status()).isEqualTo("INPUT_MISSING");
        assertThat(missing.missing()).containsExactly("co2");
        given(repo.list(1, 31)).willReturn(List.of(row(true, "OK")));
        assertThat(service.list("31").getFirst().status()).isEqualTo("OK");
        given(repo.inputDevices(1, 31, "co2")).willReturn(List.of());
        assertThat(service.list("31").getFirst().status()).isEqualTo("INPUT_MISSING");
    }

    private static ConfigRow row(boolean enabled, String lastStatus) {
        return new ConfigRow(31, "ACH", enabled, "{\"outdoorCo2Mode\":\"FIXED\",\"outdoorCo2Ppm\":420,\"minDecayMinutes\":30}", List.of("ach", "ventilation_m3h"),
                lastStatus, "INPUT_MISSING".equals(lastStatus) ? List.of("co2") : null, lastStatus == null ? null : NOW, 0, NOW);
    }
}
