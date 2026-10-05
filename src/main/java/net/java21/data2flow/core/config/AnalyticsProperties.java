package net.java21.data2flow.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * M6 분석·AI 연동 설정({@code data2flow.core.analytics.*}). core-api가 외부 경로 {@code /api/v1/core/analytics/**}를 열고 권한 확인 뒤
 * analytics 내부 API(ANA-api §2)로, 스크립트 AI 초안(API-SCR-16)은 ai 내부 API로 넘긴다.
 *
 * @param analyticsBaseUrl  data2flow-analytics 내부 주소. 기본 {@code http://data2flow-analytics}
 * @param aiBaseUrl         data2flow-ai 내부 주소. 기본 {@code http://data2flow-ai}
 * @param relayTimeout      내부 API 응답 대기(기본 8초, gateway 응답 타임아웃 10초 안). 동기 실행(5만 포인트 이하)도 이 안에 끝나야 한다
 * @param runStreamPoll     실행 상태 스트림(API-ANA-16)이 analytics 실행 상태를 다시 읽는 간격(기본 2초)
 * @param schedulerEnabled  1분 일정 실행기(ANA-04.01). 테스트는 끄고 직접 부른다
 */
@ConfigurationProperties(prefix = "data2flow.core.analytics")
public record AnalyticsProperties(String analyticsBaseUrl, String aiBaseUrl, Duration relayTimeout, Duration runStreamPoll,
                                  Boolean schedulerEnabled) {

    public AnalyticsProperties {
        analyticsBaseUrl = url(analyticsBaseUrl, "http://data2flow-analytics");
        aiBaseUrl = url(aiBaseUrl, "http://data2flow-ai");
        relayTimeout = relayTimeout == null ? Duration.ofSeconds(8) : relayTimeout;
        runStreamPoll = runStreamPoll == null ? Duration.ofSeconds(2) : runStreamPoll;
        schedulerEnabled = schedulerEnabled == null || schedulerEnabled;
    }

    private static String url(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
