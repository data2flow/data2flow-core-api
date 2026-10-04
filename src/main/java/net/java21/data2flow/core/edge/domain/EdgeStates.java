package net.java21.data2flow.core.edge.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 엣지 상태 규칙(DSC domain-model §6.10): REGISTERING → ONLINE(등록) · ONLINE ↔ OFFLINE(하트비트 30초 3회 누락/복구) · ONLINE → UPDATING →
 * ONLINE|ERROR · * → REVOKED(되돌릴 수 없음). OFFLINE은 저장하지 않고 조회할 때 마지막 하트비트로 계산한다.
 */
public final class EdgeStates {

    public static final String REGISTERING = "REGISTERING";
    public static final String ONLINE = "ONLINE";
    public static final String OFFLINE = "OFFLINE";
    public static final String UPDATING = "UPDATING";
    public static final String ERROR = "ERROR";
    public static final String REVOKED = "REVOKED";
    /** 하트비트 30초 × 3회 */
    public static final Duration OFFLINE_AFTER = Duration.ofSeconds(90);
    /** 등록 토큰 유효 기간(BR-DSC-31) */
    public static final Duration TOKEN_TTL = Duration.ofHours(24);
    /** 업데이트 상태 점검 실패 시 이전 버전으로 돌아가는 시간(BR-DSC-33) */
    public static final Duration UPDATE_ROLLBACK_AFTER = Duration.ofMinutes(5);
    public static final Set<String> CONFIG_RESULTS = Set.of("APPLIED", "FAILED_ROLLED_BACK");
    public static final Set<String> UPDATE_RESULTS = Set.of("SUCCEEDED", "FAILED_ROLLED_BACK");

    private EdgeStates() {
    }

    /** 보이는 상태: 하트비트가 끊긴 ONLINE·UPDATING은 OFFLINE */
    public static String effective(String stored, Instant lastSeenAt, Instant now) {
        if ((ONLINE.equals(stored) || UPDATING.equals(stored)) && (lastSeenAt == null || lastSeenAt.isBefore(now.minus(OFFLINE_AFTER)))) {
            return OFFLINE;
        }
        return stored;
    }

    /**
     * 진행 중인 업데이트의 결과: 보고된 에이전트 버전이 목표면 SUCCEEDED, 시작 뒤 5분이 지났는데 이전 버전이면 FAILED_ROLLED_BACK, 아니면 null
     */
    public static String updateOutcome(String reportedVersion, String fromVersion, String toVersion, Instant startedAt, Instant now) {
        if (toVersion.equals(reportedVersion)) {
            return "SUCCEEDED";
        }
        if (startedAt != null && !now.isBefore(startedAt.plus(UPDATE_ROLLBACK_AFTER)) && fromVersion.equals(reportedVersion)) {
            return "FAILED_ROLLED_BACK";
        }
        return null;
    }
}
