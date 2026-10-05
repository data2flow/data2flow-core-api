package net.java21.data2flow.core.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

/**
 * M6 분석·AI 통합 테스트 기반: M3 기반(조직·역할 5종·대역 서버)에 analytics·ai 내부 API도 같은 대역 서버로 돌린다.
 * 기본 템플릿 두 개(anomaly-detect 범용, comfort-index 의미 조건)와 빈 템플릿 설정을 매번 등록한다.
 */
public abstract class AnalyticsItSupport extends LoopItSupport {

    protected static final String ANOMALY = """
            {"key":"anomaly-detect","version":"1.2.0","name":"이상 탐지","kind":"GENERAL","category":"GENERAL",
             "summary":"평소 패턴과 다른 값이나 구간을 찾아 점수를 매깁니다",
             "questions":["어젯밤 CO2가 이상하게 높았던 시간이 있나?","이 센서 값이 갑자기 튄 적이 있나?"],
             "roles":[{"name":"target","type":"series","min":1,"max":50,"required":true}],
             "requirements":{"minPeriodDays":7},"fast":true,"realtime":true,"trainable":false}""";
    protected static final String COMFORT = """
            {"key":"comfort-index","version":"1.0.0","name":"쾌적도","kind":"DOMAIN","category":"ENV_QUALITY",
             "summary":"온도·습도·CO2로 쾌적도 점수를 매깁니다","questions":["이 강의실은 쾌적한가?"],
             "roles":[{"name":"temp","type":"series","min":1,"max":1,"semantic":"temperature","required":true},
                      {"name":"co2","type":"series","min":0,"max":1,"semantic":"co2","required":false}],
             "requirements":{"minPeriodDays":1},"fast":false,"realtime":false,"trainable":false}""";

    @DynamicPropertySource
    static void analytics(DynamicPropertyRegistry registry) {
        registry.add("data2flow.core.analytics.analytics-base-url", STUB::baseUrl);
        registry.add("data2flow.core.analytics.ai-base-url", STUB::baseUrl);
    }

    @BeforeEach
    void analyticsStubs() {
        STUB.on("GET", "/internal/analytics/templates", r -> list("[" + ANOMALY + "," + COMFORT + "]"));
        STUB.ok("GET", "/internal/analytics/templates/anomaly-detect", 200, ANOMALY);
        STUB.ok("GET", "/internal/analytics/templates/comfort-index", 200, COMFORT);
        STUB.on("GET", "/internal/analytics/template-settings", r -> list("[]"));
    }

    /** 목록 봉투 응답 */
    protected static StubHttpServer.Reply list(String responsesJson) {
        return new StubHttpServer.Reply(200, "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"SUCCESS\",\"resultMessage\":\"SUCCESS\"},"
                + "\"page\":1,\"size\":20,\"totalPages\":1,\"responses\":" + responsesJson + ",\"totalCount\":0}", Map.of());
    }
}
