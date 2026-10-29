package net.java21.data2flow.core.compliance;

import net.java21.data2flow.contracts.audit.AuditEvent;
import net.java21.data2flow.contracts.audit.AuditRecorder;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.compliance.domain.ComplianceErrorCode;
import net.java21.data2flow.core.compliance.domain.ProfileModels.Band;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.domain.ProfileModels.VersionSpec;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.CloneRequest;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository.ProfileRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository.VersionRow;
import net.java21.data2flow.core.compliance.service.ComplianceEvents;
import net.java21.data2flow.core.compliance.service.ComplianceProfileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/** CMP-01.01·01.02 프로필 서비스 — TC-CMP-004(복제), TC-CMP-014(시스템 프로필 읽기 전용), TC-CMP-001(잠긴 버전) */
class ComplianceProfileServiceTest {

    static final long ORG = 7;
    static final long RESET_ID = 2;
    static final Instant NOW = Instant.parse("2026-10-29T01:00:00Z");

    final ComplianceProfileRepository profiles = mock(ComplianceProfileRepository.class);
    final ComplianceApplicationRepository applications = mock(ComplianceApplicationRepository.class);
    final ComplianceEvents events = mock(ComplianceEvents.class);
    final RoleChecker roleChecker = mock(RoleChecker.class);
    final AuditRecorder recorder = mock(AuditRecorder.class);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final ComplianceProfileService service = new ComplianceProfileService(profiles, applications, events, roleChecker,
            new Audits(recorder, clock), clock);
    final JsonMapper json = JsonMapper.builder().build();

    ComplianceProfileServiceTest() {
        given(roleChecker.currentUser()).willReturn(new CurrentUser(3, ORG, null, Set.of()));
    }

    static ProfileRow system() {
        return new ProfileRow(RESET_ID, 0, "SYSTEM", "RESET_AIR_CI", "RESET Air(Commercial Interiors)", 1, null, 0, NOW,
                "RESET Air Standard", LocalDate.of(2023, 1, 1), 0);
    }

    static ProfileRow org(long id, String name, int current, int version) {
        return new ProfileRow(id, ORG, "ORGANIZATION", null, name, current, null, version, NOW, "내부 지침", LocalDate.of(2026, 9, 1), 0);
    }

    static VersionRow version(long profileId, int no, boolean locked) {
        return new VersionRow(100 + no, profileId, no, "RESET Air Standard", "https://www.reset.build/standard/air", LocalDate.of(2023, 1, 1),
                null, 90, new BigDecimal("95.00"), false, null, locked, 0, NOW, null, 0);
    }

    static VersionSpec resetSpec() {
        MetricRule co2 = new MetricRule("co2", null, "HOUR_1", null, new BigDecimal("1000"), false, "ppm", "LIMIT", null,
                List.of(new Band("허용", null, new BigDecimal("1000"), true), new Band("고성능", null, new BigDecimal("600"), false)));
        return new VersionSpec("RESET Air Standard", "https://www.reset.build/standard/air", LocalDate.of(2023, 1, 1), null, 90,
                new BigDecimal("95.00"), false, "원본 메모", List.of(co2), List.of());
    }

    @Test
    @DisplayName("[TC-CMP-004] 시스템 RESET을 복제하면 조직 프로필 v1이 생기고 원본은 바뀌지 않으며 CMP_PROFILE_CLONED가 남는다")
    void cloneSystem() {
        given(profiles.findVisible(ORG, RESET_ID)).willReturn(Optional.of(system()));
        given(profiles.version(ORG, RESET_ID, 1)).willReturn(Optional.of(version(RESET_ID, 1, true)));
        given(profiles.spec(any())).willReturn(resetSpec());
        given(profiles.nameExists(ORG, "RESET 복제본")).willReturn(false);
        given(profiles.insertProfile(ORG, "RESET 복제본", 3, NOW)).willReturn(50L);
        given(profiles.findVisible(ORG, 50)).willReturn(Optional.of(org(50, "RESET 복제본", 1, 0)));
        given(profiles.version(ORG, 50, 1)).willReturn(Optional.of(version(50, 1, false)));

        var created = service.cloneProfile(RESET_ID, new CloneRequest("RESET 복제본", null));

        assertThat(created.kind()).isEqualTo("ORGANIZATION");
        assertThat(created.currentVersionNo()).isEqualTo(1);
        ArgumentCaptor<VersionSpec> spec = ArgumentCaptor.forClass(VersionSpec.class);
        then(profiles).should().insertVersion(eq(ORG), eq(50L), eq(1), spec.capture(), eq(3L), eq(NOW));
        assertThat(spec.getValue().metrics()).isEqualTo(resetSpec().metrics());
        assertThat(spec.getValue().changeNote()).isNull();
        // 원본은 새 버전·잠금 해제·수정이 없다
        then(profiles).should(never()).advance(eq(RESET_ID), anyInt(), anyInt(), anyLong(), any());
        then(profiles).should(never()).replaceVersion(anyLong(), anyLong(), any(), any());
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        then(recorder).should().record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("CMP_PROFILE_CLONED");
        assertThat(audit.getValue().detail()).containsEntry("sourceProfileId", "2");
    }

    @Test
    @DisplayName("[TC-CMP-004] 같은 이름으로 다시 만들면 CMP_PROFILE_NAME_DUPLICATE")
    void duplicateName() {
        given(profiles.findVisible(ORG, RESET_ID)).willReturn(Optional.of(system()));
        given(profiles.version(ORG, RESET_ID, 1)).willReturn(Optional.of(version(RESET_ID, 1, true)));
        given(profiles.nameExists(ORG, "RESET 복제본")).willReturn(true);
        assertThatThrownBy(() -> service.cloneProfile(RESET_ID, new CloneRequest("RESET 복제본", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ComplianceErrorCode.CMP_PROFILE_NAME_DUPLICATE));
        then(profiles).should(never()).insertProfile(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("[TC-CMP-014] 시스템 프로필은 버전 추가·수정·보관·해제 모두 CMP_PROFILE_READ_ONLY")
    void systemReadOnly() {
        given(profiles.findVisible(ORG, RESET_ID)).willReturn(Optional.of(system()));
        var body = json.readTree("{\"baseVersion\":0}");
        List<Runnable> actions = List.of(() -> service.addVersion(RESET_ID, body), () -> service.updateVersion(RESET_ID, 1, body),
                () -> service.archive(RESET_ID, true), () -> service.archive(RESET_ID, false));
        for (Runnable action : actions) {
            assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ComplianceErrorCode.CMP_PROFILE_READ_ONLY));
        }
        then(profiles).should(never()).lock(anyLong(), anyLong());
    }

    @Test
    @DisplayName("[TC-CMP-001] 잠긴 버전을 고치면 CMP_PROFILE_READ_ONLY, 오래된 baseVersion은 CMP_PROFILE_VERSION_CONFLICT")
    void lockedAndConflict() {
        given(profiles.findVisible(ORG, 50)).willReturn(Optional.of(org(50, "우리 기준", 1, 2)));
        given(profiles.version(ORG, 50, 1)).willReturn(Optional.of(version(50, 1, true)));
        assertThatThrownBy(() -> service.updateVersion(50, 1, json.readTree("{\"baseVersion\":2}")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ComplianceErrorCode.CMP_PROFILE_READ_ONLY));
        assertThatThrownBy(() -> service.updateVersion(50, 1, json.readTree("{\"baseVersion\":1}")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ComplianceErrorCode.CMP_PROFILE_VERSION_CONFLICT));
    }
}
