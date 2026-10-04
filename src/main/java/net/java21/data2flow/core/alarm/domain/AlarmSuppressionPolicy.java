package net.java21.data2flow.core.alarm.domain;

import net.java21.data2flow.contracts.alarm.SuppressedReason;

/**
 * 억제 판정(RUL-02.05, BR-RUL-08): 유지보수 중인 기기·공간, 상위 원인(게이트웨이·소스 장애)이 열린 기기의 알람은 SUPPRESSED로 만들고
 * 알림하지 않는다. 오프라인 기기에서 파생되는 측정값 알람(무수신 제외)도 억제한다.
 */
public final class AlarmSuppressionPolicy {

    private AlarmSuppressionPolicy() {
    }

    /**
     * @param maintenance   대상이 유지보수 중인가
     * @param parentOpen    상위 원인 알람이 열려 있는가(토폴로지 묶기 대상 알람일 때만)
     * @param deviceOffline 대상 기기가 오프라인인가
     * @param connectivity  이 알람이 연결 계열(무수신·오프라인)인가. 오프라인 사유로는 억제하지 않는다
     * @return 억제 사유, 억제하지 않으면 null. 우선순위는 유지보수 > 상위 원인 > 기기 오프라인
     */
    public static SuppressedReason decide(boolean maintenance, boolean parentOpen, boolean deviceOffline, boolean connectivity) {
        if (maintenance) {
            return SuppressedReason.MAINTENANCE;
        }
        if (parentOpen && connectivity) {
            return SuppressedReason.PARENT;
        }
        if (deviceOffline && !connectivity) {
            return SuppressedReason.DEVICE_OFFLINE;
        }
        return null;
    }
}
