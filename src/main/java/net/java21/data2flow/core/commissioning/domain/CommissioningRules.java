package net.java21.data2flow.core.commissioning.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QR 현장 설치 규칙(DEV-13.05·13.06, BR-DEV-37·38). 상태: PLANNED → INSTALLED → VERIFIED(첫 수신) / PROBLEM(10분 무수신).
 * 점검 체크리스트(BR-DEV-38, API-DEV-138 checklist): firstData·position·photo·signal·battery·gateway·source.
 */
public final class CommissioningRules {

    public static final String PLANNED = "PLANNED";
    public static final String INSTALLED = "INSTALLED";
    public static final String VERIFIED = "VERIFIED";
    public static final String PROBLEM = "PROBLEM";
    public static final List<String> STATUSES = List.of(PLANNED, INSTALLED, VERIFIED, PROBLEM);
    /** 첫 수신을 기다리는 시간(BR-DEV-38) */
    public static final Duration FIRST_DATA_WAIT = Duration.ofMinutes(10);
    /** 신호가 약하다고 보는 기준(LoRa SF12 수신 한계 근처) */
    public static final BigDecimal MIN_RSSI = BigDecimal.valueOf(-115);
    public static final BigDecimal MIN_SNR = BigDecimal.valueOf(-15);
    public static final BigDecimal MIN_BATTERY = BigDecimal.valueOf(20);

    private CommissioningRules() {
    }

    /** 기기 상태 사본(없으면 null 필드) */
    public record Observed(Instant lastSeenAt, BigDecimal battery, BigDecimal rssi, BigDecimal snr, String bestGatewayEui,
                           Boolean gatewayOnline, String sourceLifecycle, String sourceConnection) {
    }

    /** 설치 기록 + 관측으로 체크리스트를 만든다. 알 수 없는 항목(값 없음)은 false가 아니라 빼지 않고 null 대신 false로 둔다 */
    public static Map<String, Boolean> checklist(Instant installedAt, boolean positioned, boolean photographed, Observed o) {
        Map<String, Boolean> c = new LinkedHashMap<>();
        boolean firstData = o != null && o.lastSeenAt() != null && installedAt != null && !o.lastSeenAt().isBefore(installedAt);
        c.put("firstData", firstData);
        c.put("position", positioned);
        c.put("photo", photographed);
        c.put("signal", o != null && o.rssi() != null && o.rssi().compareTo(MIN_RSSI) >= 0
                && (o.snr() == null || o.snr().compareTo(MIN_SNR) >= 0));
        c.put("battery", o != null && o.battery() != null && o.battery().compareTo(MIN_BATTERY) >= 0);
        c.put("gateway", o != null && Boolean.TRUE.equals(o.gatewayOnline()));
        c.put("source", o != null && "ACTIVE".equals(o.sourceLifecycle()) && !"DISCONNECTED".equals(o.sourceConnection())
                && !"ERROR".equals(o.sourceConnection()));
        return c;
    }

    /** 설치 뒤 다음 상태. 바뀌지 않으면 같은 값 */
    public static String next(String status, Instant installedAt, Instant lastSeenAt, Instant now) {
        if (!INSTALLED.equals(status) && !PROBLEM.equals(status)) {
            return status;
        }
        if (lastSeenAt != null && installedAt != null && !lastSeenAt.isBefore(installedAt)) {
            return VERIFIED;
        }
        if (INSTALLED.equals(status) && installedAt != null && !now.isBefore(installedAt.plus(FIRST_DATA_WAIT))) {
            return PROBLEM;
        }
        return status;
    }
}
