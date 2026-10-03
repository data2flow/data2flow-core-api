package net.java21.data2flow.core.ingest.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 수집 요약(API-ING-01)의 운영 경고 판정. DB에서 읽은 값만으로 판정하는 순수 함수다.
 * <ul>
 *   <li>INGEST_STOPPED(CRITICAL, BR-OPS-02·OPS-01.05): ACTIVE 소스가 있는데 {@code INGEST_ZERO_MINUTES}(기본 5분) 동안 원본 수신 0건.
 *       ACTIVE 소스가 없거나 모두 멈춤(PAUSED, 유지보수)이면 내지 않는다(AT-OPS-02.2). 원인 후보 SOURCE_DISCONNECTED</li>
 *   <li>INGEST_LAG_HIGH(BR-ING-16, ING-07.04): 처리 대기 지연이 lagWarnSec(기본 60초) 이상이면 WARNING, lagCriticalSec(기본 300초) 이상이면
 *       CRITICAL. 원인 후보: 스크립트 오류율 10% 초과(SCRIPT_ERROR_RATE), 최근 5분 입력률이 직전 평균의 2배 이상(INPUT_SURGE)</li>
 *   <li>DLQ_GROWING(WARNING, ops_thresholds DLQ_GROWTH_PER_10MIN 기본 100): 최근 10분 실패 메시지 증가</li>
 * </ul>
 * 경고 코드 이름은 웹 수집 모니터(app/features/ingest/model/ingest.ts CARD_ALERT_CODES)와 맞췄다. 운영 알람 생성·알림 발송은
 * Alertmanager 규칙(data2flow-manifests)과 RUL(출처 SYSTEM, BR-OPS-04)이 맡는다.
 */
public final class IngestAlertRules {

    private IngestAlertRules() {
    }

    /** 판정 입력 */
    public record Inputs(int activeSources, long receivedInZeroWindow, boolean zeroRuleEnabled, int zeroMinutes,
                         Long streamLagSec, int lagWarnSec, int lagCriticalSec, double scriptErrorRate, double inputSurgeRatio,
                         long dlqLast10Min, boolean dlqRuleEnabled, double dlqThreshold, boolean anySourceDisconnected) {
    }

    /** 경고 하나(문구는 서비스가 언어에 맞춰 채운다) */
    public record Alert(String level, String code, List<String> causeHints) {
    }

    public static List<Alert> evaluate(Inputs in) {
        List<Alert> alerts = new ArrayList<>();
        if (in.zeroRuleEnabled() && in.activeSources() > 0 && in.receivedInZeroWindow() == 0) {
            alerts.add(new Alert("CRITICAL", "INGEST_STOPPED", in.anySourceDisconnected() ? List.of("SOURCE_DISCONNECTED") : List.of()));
        }
        if (in.streamLagSec() != null && in.streamLagSec() >= in.lagWarnSec()) {
            List<String> hints = new ArrayList<>();
            if (in.scriptErrorRate() > 0.10) {
                hints.add("SCRIPT_ERROR_RATE");
            }
            if (in.inputSurgeRatio() >= 2.0) {
                hints.add("INPUT_SURGE");
            }
            alerts.add(new Alert(in.streamLagSec() >= in.lagCriticalSec() ? "CRITICAL" : "WARNING", "INGEST_LAG_HIGH", List.copyOf(hints)));
        }
        if (in.dlqRuleEnabled() && in.dlqLast10Min() >= in.dlqThreshold()) {
            alerts.add(new Alert("WARNING", "DLQ_GROWING", List.of()));
        }
        return alerts;
    }
}
