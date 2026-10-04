package net.java21.data2flow.core.notify.domain;

import net.java21.data2flow.contracts.alarm.AlarmSeverity;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 알림 정책이 알람에 맞는가(RUL-03.02, BR-RUL-12): 최소 심각도, 공간(하위 포함 여부), 규칙, 요일·시간대.
 * 수신자 합치기·중복 제거는 호출자가 한다.
 */
public final class PolicyMatcher {

    private PolicyMatcher() {
    }

    /**
     * @param minSeverity      정책 최소 심각도
     * @param policySpacePath  정책 공간 경로(예: /1/4/, 없으면 조직 전체)
     * @param includeChildren  하위 공간 포함
     * @param ruleIds          정책 규칙 목록(비면 모든 출처)
     * @param window           요일·시간대(null이면 항상)
     * @param severity         알람 심각도
     * @param alarmSpacePath   알람 공간 경로(없으면 null)
     * @param ruleId           알람 규칙(없으면 null)
     * @param local            조직 시간대의 지금
     */
    public static boolean matches(AlarmSeverity minSeverity, String policySpacePath, boolean includeChildren, Collection<Long> ruleIds,
                                  TimeWindow window, AlarmSeverity severity, String alarmSpacePath, Long ruleId, LocalDateTime local) {
        if (severity == null || !severity.atLeast(minSeverity)) {
            return false;
        }
        if (policySpacePath != null) {
            if (alarmSpacePath == null) {
                return false;
            }
            boolean ok = includeChildren ? alarmSpacePath.startsWith(policySpacePath) : alarmSpacePath.equals(policySpacePath);
            if (!ok) {
                return false;
            }
        }
        if (ruleIds != null && !ruleIds.isEmpty() && (ruleId == null || !ruleIds.contains(ruleId))) {
            return false;
        }
        return window == null || window.contains(local);
    }
}
