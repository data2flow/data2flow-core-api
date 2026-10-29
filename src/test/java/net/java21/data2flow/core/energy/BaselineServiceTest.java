package net.java21.data2flow.core.energy;

import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.event.EnergyBaselineTrained;
import net.java21.data2flow.core.analytics.service.AnalyticsClient;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.CreateVersionRequest;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.Params;
import net.java21.data2flow.core.energy.repository.EnergyBaselineRepository;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.SpaceRow;
import net.java21.data2flow.core.energy.repository.EnergySourceRepository;
import net.java21.data2flow.core.energy.service.EnergyAccess;
import net.java21.data2flow.core.energy.service.EnergyBaselineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** ENE-01.04 기준선 요청 검사(TC-ENE-025): 학습 기간 30~730일(끝 날 포함), 부족하면 409 neededDays, 이벤트 반영(r2 반올림·실패 사유) */
class BaselineServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private final EnergyBaselineRepository baselines = mock(EnergyBaselineRepository.class);
    private final EnergyConfigRepository config = mock(EnergyConfigRepository.class);
    private final EnergyAccess access = mock(EnergyAccess.class);
    private final RoleChecker roles = mock(RoleChecker.class);
    private final AnalyticsClient analytics = mock(AnalyticsClient.class);
    private final EnergyBaselineService service = new EnergyBaselineService(baselines, config, mock(EnergySourceRepository.class), access, roles,
            analytics, mock(Audits.class), JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        given(roles.currentUser()).willReturn(new CurrentUser(7, 1));
        given(access.space(any(), anyLong(), anyLong())).willReturn(new SpaceRow(10, "본관", "/1/10/", "BUILDING"));
        given(config.timezone(1)).willReturn("Asia/Seoul");
    }

    private static CreateVersionRequest req(String from, String to) {
        return new CreateVersionRequest("10", "period_average", from, to, null, null);
    }

    @ParameterizedTest(name = "{0}~{1} → neededDays {2}")
    @CsvSource({"2026-09-01,2026-09-25,5", "2026-09-01,2026-09-29,1", "2026-09-01,2026-09-01,29"})
    @DisplayName("[TC-ENE-025] 30일보다 짧으면 409 ENE_BASELINE_INSUFFICIENT_DATA(neededDays), analytics를 부르지 않는다")
    void insufficient(String from, String to, int needed) {
        assertThatThrownBy(() -> service.create(req(from, to))).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(EnergyErrorCode.ENE_BASELINE_INSUFFICIENT_DATA);
            assertThat(ex.getErrors().getFirst().message()).isEqualTo(Integer.toString(needed));
            assertThat(ex.getArgs()).containsExactly((long) needed);
        });
        verify(analytics, never()).call(any(), any(), any(), any(), any());
        verify(baselines, never()).insertVersion(anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("[TC-ENE-025] 30일이면 TRAINING 버전을 쓰고 학습을 요청한다(기본 냉방 24·난방 18·주말 분리)")
    void thirtyDays() {
        given(baselines.ensure(eq(1L), eq(10L), any())).willReturn(3L);
        given(baselines.insertVersion(eq(1L), eq(3L), eq("PERIOD_AVERAGE"), any(), any(), any(), eq(7L), any())).willReturn(12L);
        assertThat(service.create(req("2026-09-01", "2026-09-30")).versionId()).isEqualTo("12");
        verify(baselines).insertVersion(eq(1L), eq(3L), eq("PERIOD_AVERAGE"), any(), any(),
                eq("{\"coolingBaseC\":24.0,\"heatingBaseC\":18.0,\"splitWeekday\":true,\"temperatureSource\":null}"), eq(7L), any());
        verify(analytics).call(any(), eq("/internal/analytics/energy/baselines/train"), isNull(), any(), any());
    }

    @Test
    @DisplayName("[TC-ENE-025] 730일 초과·순서·미래·방법·기준 온도·회귀의 기온 원천은 400")
    void invalid() {
        assertCode(req("2024-01-01", "2026-01-01"), CommonErrorCode.INVALID_REQUEST);
        assertCode(req("2026-09-30", "2026-09-01"), CommonErrorCode.INVALID_REQUEST);
        assertCode(req("2026-09-01", "2026-10-29"), CommonErrorCode.INVALID_REQUEST);
        assertCode(new CreateVersionRequest("10", "MAGIC", "2026-08-01", "2026-09-30", null, null), CommonErrorCode.INVALID_REQUEST);
        assertCode(new CreateVersionRequest("10", "PERIOD_AVERAGE", "2026-08-01", "2026-09-30", new Params(BigDecimal.valueOf(20),
                BigDecimal.valueOf(22), null), null), CommonErrorCode.INVALID_REQUEST);
        assertCode(new CreateVersionRequest("10", "DEGREE_DAY_REGRESSION", "2026-08-01", "2026-09-30", null, null), CommonErrorCode.INVALID_REQUEST);
        assertCode(new CreateVersionRequest(null, "PERIOD_AVERAGE", "2026-08-01", "2026-09-30", null, null), CommonErrorCode.INVALID_REQUEST);
    }

    private void assertCode(CreateVersionRequest r, Object code) {
        assertThatThrownBy(() -> service.create(r)).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("EVT-ENE-02: READY는 r2를 소수 셋째 자리로(음수 하한 −9.999), FAILED는 사유·neededDays")
    void onTrained() {
        given(baselines.trained(anyLong(), anyLong(), any(), any(), any(), any(), any())).willReturn(1);
        service.onTrained(1, new EnergyBaselineTrained(12, EnergyBaselineTrained.Status.READY, 0.92349, Map.of("cdd", 8.0), "bl-12", "LOW_FIT", null,
                null, 60));
        verify(baselines).trained(eq(1L), eq(12L), eq(new BigDecimal("0.923")), eq("{\"cdd\":8.0}"), eq("bl-12"), eq("LOW_FIT"), any());
        service.onTrained(1, new EnergyBaselineTrained(13, EnergyBaselineTrained.Status.READY, -42.0, null, null, null, null, null, 60));
        verify(baselines).trained(eq(1L), eq(13L), eq(new BigDecimal("-9.999")), isNull(), isNull(), isNull(), any());
        service.onTrained(1, new EnergyBaselineTrained(14, EnergyBaselineTrained.Status.FAILED, null, null, null, null, "NO_WEATHER", null, null));
        verify(baselines).failed(eq(1L), eq(14L), eq("NO_WEATHER"), isNull(), any());
    }
}
