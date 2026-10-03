package net.java21.data2flow.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * M3 가상 폐루프 연동 설정({@code data2flow.core.loop.*}). core-api가 외부 경로를 대신 열고 내부 API로 넘기는 서비스 주소와
 * 가상 환경 실시간 스트림(API-SIM-31) 주기.
 *
 * @param actionBaseUrl     data2flow-action 내부 주소(제어 창구 API-ACT-01~06). 기본 {@code http://data2flow-action}
 * @param flowEngineBaseUrl data2flow-flow-engine 내부 주소(플로우 지표 API-FLW-14). 기본 {@code http://data2flow-flow-engine}
 * @param simulatorBaseUrl  data2flow-simulator 내부 주소(API-SIM-30~34). 기본 {@code http://data2flow-simulator}
 * @param simTick           실행 스트림 {@code sim.tick} 간격(1초). simulator가 틱 이벤트를 내지 않아 core가 실행 상태를 읽어 만든다
 * @param simTickEnabled    {@code sim.tick} 주기 작업 실행(테스트는 끄고 직접 부른다)
 * @param relayTimeout      내부 API 응답 대기(기본 8초, gateway 응답 타임아웃 10초 안)
 * @param seedOnStartup     시작할 때 ACTIVE 조직마다 가상 환경 기본값(SIM 소스·하트비트 기기·가상 드라이버) 보장(멱등)
 */
@ConfigurationProperties(prefix = "data2flow.core.loop")
public record LoopProperties(String actionBaseUrl, String flowEngineBaseUrl, String simulatorBaseUrl, Duration simTick,
                             Boolean simTickEnabled, Duration relayTimeout, Boolean seedOnStartup) {

    public LoopProperties {
        actionBaseUrl = url(actionBaseUrl, "http://data2flow-action");
        flowEngineBaseUrl = url(flowEngineBaseUrl, "http://data2flow-flow-engine");
        simulatorBaseUrl = url(simulatorBaseUrl, "http://data2flow-simulator");
        simTick = simTick == null ? Duration.ofSeconds(1) : simTick;
        simTickEnabled = simTickEnabled == null || simTickEnabled;
        relayTimeout = relayTimeout == null ? Duration.ofSeconds(8) : relayTimeout;
        seedOnStartup = seedOnStartup == null || seedOnStartup;
    }

    private static String url(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
