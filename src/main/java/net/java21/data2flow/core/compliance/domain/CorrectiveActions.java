package net.java21.data2flow.core.compliance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 개선 조치(CMP-02.05) 값과 리포트 "개선 조치" 장에 넣는 문구(AT-CMP-10.1) */
public final class CorrectiveActions {

    public static final List<String> CAUSES = List.of("VENTILATION", "EQUIPMENT", "OUTDOOR_INFLOW", "CROWDING", "SENSOR", "OTHER");
    public static final List<String> RESULT_MODES = List.of("AUTO", "MANUAL");
    static final Map<String, String> CAUSE_LABELS = Map.of("VENTILATION", "환기 부족", "EQUIPMENT", "장비 고장", "OUTDOOR_INFLOW", "외부 유입",
            "CROWDING", "재실 과밀", "SENSOR", "센서 이상", "OTHER", "기타");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private CorrectiveActions() {
    }

    /** 리포트에 넣을 조치 하나. {@code resultCheckedAt}은 AUTO 결과를 계산한 시각(없으면 대기) */
    public record Summary(String cause, String causeNote, String actionText, String actorName, Instant actedAt, String resultMode,
                          BigDecimal resultRatio24h, String resultNote, Instant resultCheckedAt) {
    }

    /** 결과 상태: MANUAL은 RECORDED, AUTO는 PENDING(24시간 전)·CONFIRMED·UNVERIFIABLE(결측 과다) */
    public static String resultStatus(String mode, BigDecimal ratio, Instant checkedAt) {
        if ("MANUAL".equals(mode)) {
            return "RECORDED";
        }
        if (checkedAt == null) {
            return "PENDING";
        }
        return ratio == null ? "UNVERIFIABLE" : "CONFIRMED";
    }

    /** 이탈 구간 하나의 조치들(조치 시각 순)을 한 칸 문구로. 없으면 null */
    public static String text(List<Summary> actions, ZoneId zone) {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        return actions.stream().map(a -> line(a, zone)).collect(Collectors.joining("\n"));
    }

    static String line(Summary a, ZoneId zone) {
        String cause = CAUSE_LABELS.getOrDefault(a.cause(), a.cause());
        if ("OTHER".equals(a.cause()) && a.causeNote() != null) {
            cause += ": " + a.causeNote();
        }
        StringBuilder s = new StringBuilder("[").append(cause).append("] ").append(a.actionText());
        s.append(" (").append(a.actorName() == null ? "-" : a.actorName()).append(", ").append(TIME.format(a.actedAt().atZone(zone))).append(")");
        s.append(" — ").append(switch (resultStatus(a.resultMode(), a.resultRatio24h(), a.resultCheckedAt())) {
            case "RECORDED" -> "결과: " + a.resultNote();
            case "PENDING" -> "결과 확인 대기";
            case "UNVERIFIABLE" -> "결과 확인 불가(데이터 결측)";
            default -> "조치 후 충족률 " + a.resultRatio24h().setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
        });
        return s.toString();
    }
}
