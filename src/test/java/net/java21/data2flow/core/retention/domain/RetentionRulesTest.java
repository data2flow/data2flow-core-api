package net.java21.data2flow.core.retention.domain;

import net.java21.data2flow.core.retention.domain.RetentionRules.Effective;
import net.java21.data2flow.core.retention.domain.RetentionRules.Policy;
import net.java21.data2flow.core.retention.domain.RetentionRules.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 보관 정책 규칙(TSD-02.01·05.01·05.03, BR-TSD-02·03, NFR-04.03) — core 쪽 TC-TSD-121·132 서비스 규칙 */
class RetentionRulesTest {

    static Policy org(DataClass dc, int days) {
        return new Policy(Scope.ORG, null, dc, days, null, false, null);
    }

    static Policy metric(String key, int days, String storeMode) {
        return new Policy(Scope.METRIC, key, DataClass.TELEMETRY, days, null, false, storeMode);
    }

    @Test
    @DisplayName("[TSD-02.01][NFR-04.03] 저장된 정책이 없으면 13종 모두 시스템 기본값(원본 30일, 시계열 365일, 1h 1,095일, 1d 무기한) — TC-TSD-121")
    void defaults() {
        List<Effective> e = RetentionRules.effective(List.of());
        assertThat(e).hasSize(DataClass.values().length).allMatch(Effective::inherited);
        assertThat(e.stream().filter(x -> x.policy().dataClass() == DataClass.RAW_MESSAGE).findFirst().orElseThrow().policy().retainDays())
                .isEqualTo(30);
        assertThat(RetentionRules.orgDays(List.of(), DataClass.TELEMETRY)).isEqualTo(365);
        assertThat(RetentionRules.orgDays(List.of(), DataClass.AGG_1H)).isEqualTo(1095);
        assertThat(RetentionRules.orgDays(List.of(), DataClass.AGG_1D)).isZero();
        assertThat(RetentionRules.defaultPolicy(DataClass.TELEMETRY).compressAfterDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("[TSD-05.01][BR-TSD-03] 단축 판정: ORG 365→180, 새 재정의 LAeq 90일, 더 긴 재정의를 지움 → 단축. 늘리기·무기한은 단축 아님 — AT-TSD-06.1·06.3")
    void shortened() {
        List<Policy> before = List.of(org(DataClass.TELEMETRY, 365), metric("co2", 730, null));
        List<Policy> after = RetentionRules.merge(before, List.of(org(DataClass.TELEMETRY, 180), metric("laeq", 90, null)));
        // co2 재정의는 변경안에 없으니 지워지고(재정의 목록은 전체), ORG 180일로 돌아가므로 단축
        List<Policy> s = RetentionRules.shortened(before, after);
        assertThat(s).extracting(Policy::key).containsExactlyInAnyOrder("ORG||TELEMETRY", "METRIC|laeq|TELEMETRY",
                "METRIC|co2|TELEMETRY");
        assertThat(s.stream().filter(p -> p.scopeRef() != null && p.scopeRef().equals("co2")).findFirst().orElseThrow().retainDays())
                .isEqualTo(180);
        assertThat(RetentionRules.shortened(List.of(), List.of(org(DataClass.TELEMETRY, 400)))).isEmpty();
        assertThat(DataClass.shorter(0, 365)).isFalse();
        assertThat(DataClass.shorter(365, 0)).isTrue();
        assertThat(DataClass.shorter(30, 30)).isFalse();
    }

    @Test
    @DisplayName("[TSD-05.01][BR-TSD-03] 확인 토큰: 같은 조직·판·변경안·만료 전만 맞음 — AT-TSD-06.1")
    void confirmToken() {
        List<Policy> next = List.of(org(DataClass.TELEMETRY, 180));
        String token = RetentionRules.confirmToken(7, 3, next, 1_000);
        assertThat(RetentionRules.verify(token, 7, 3, next, 999)).isTrue();
        assertThat(RetentionRules.verify(token, 7, 3, next, 1_001)).isFalse();
        assertThat(RetentionRules.verify(token, 8, 3, next, 999)).isFalse();
        assertThat(RetentionRules.verify(token, 7, 4, next, 999)).isFalse();
        assertThat(RetentionRules.verify(token, 7, 3, List.of(org(DataClass.TELEMETRY, 90)), 999)).isFalse();
        assertThat(RetentionRules.verify(null, 7, 3, next, 999)).isFalse();
        assertThat(RetentionRules.verify("bad", 7, 3, next, 999)).isFalse();
        assertThat(RetentionRules.same(next, List.of(org(DataClass.TELEMETRY, 180)))).isTrue();
    }

    @Test
    @DisplayName("[TSD-05.03][NFR-04.03] 허용 범위: 최소값(원본 7, 시계열 30, 감사 365), AGG_1D는 0만, 분석 결과 최대 1,095, 재정의는 시계열·집계만")
    void ranges() {
        assertThat(DataClass.RAW_MESSAGE.allows(6)).isFalse();
        assertThat(DataClass.RAW_MESSAGE.allows(7)).isTrue();
        assertThat(DataClass.TELEMETRY.allows(29)).isFalse();
        assertThat(DataClass.AUDIT_LOG.allows(364)).isFalse();
        assertThat(DataClass.AGG_1D.allows(0)).isTrue();
        assertThat(DataClass.AGG_1D.allows(3650)).isFalse();
        assertThat(DataClass.ANALYSIS_RESULT.allows(1096)).isFalse();
        assertThat(DataClass.TELEMETRY.allows(3651)).isFalse();
        assertThat(DataClass.TELEMETRY.overridable()).isTrue();
        assertThat(DataClass.RAW_MESSAGE.overridable()).isFalse();
        assertThat(DataClass.parse(" telemetry ")).contains(DataClass.TELEMETRY);
        assertThat(DataClass.parse("X")).isEmpty();
        assertThat(DataClass.parse(null)).isEmpty();
        List<Effective> e = RetentionRules.effective(List.of(metric("door", 365, "ON_CHANGE"), org(DataClass.LINK, 30)));
        assertThat(e).hasSize(DataClass.values().length + 1);
        assertThat(e.getLast().policy().storeMode()).isEqualTo("ON_CHANGE");
        assertThat(e.stream().filter(x -> x.policy().dataClass() == DataClass.LINK && x.policy().scope() == Scope.ORG).findFirst()
                .orElseThrow().inherited()).isFalse();
    }
}
