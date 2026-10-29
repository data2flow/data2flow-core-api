package net.java21.data2flow.core.energy.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 시간대별 단가(API-ENE-01 §1.1). 12개월이 모두 한 계절에만 속하고, 계절마다 요일 유형(평일, 주말·공휴일) 둘 다 00:00~24:00을 빈틈·겹침 없이
 * 덮어야 하며, 단가는 0보다 커야 한다. 공휴일은 조직 달력(DEV-12)을 쓴다.
 */
public record TouSchedule(List<Season> seasons) {

    public static final List<String> DAY_TYPES = List.of("WEEKDAY", "WEEKEND_HOLIDAY");
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$|^24:00$");

    public record Season(String name, List<Integer> months, List<Band> bands) {
    }

    public record Band(String dayType, String from, String to, BigDecimal rate, String label) {
    }

    /** 검사 실패면 필드 경로(예: {@code touSchedule.seasons[0].bands}) */
    public Optional<String> problem() {
        if (seasons == null || seasons.isEmpty()) {
            return Optional.of("touSchedule.seasons");
        }
        Set<Integer> months = new HashSet<>();
        for (int i = 0; i < seasons.size(); i++) {
            Season s = seasons.get(i);
            String at = "touSchedule.seasons[" + i + "]";
            if (s == null || s.name() == null || s.name().isBlank() || s.name().length() > 32) {
                return Optional.of(at + ".name");
            }
            if (s.months() == null || s.months().isEmpty()) {
                return Optional.of(at + ".months");
            }
            for (Integer m : s.months()) {
                if (m == null || m < 1 || m > 12 || !months.add(m)) {
                    return Optional.of(at + ".months");
                }
            }
            if (s.bands() == null || s.bands().isEmpty()) {
                return Optional.of(at + ".bands");
            }
            for (String dayType : DAY_TYPES) {
                if (!covers(s.bands().stream().filter(b -> b != null && dayType.equals(b.dayType())).toList())) {
                    return Optional.of(at + ".bands");
                }
            }
            for (Band b : s.bands()) {
                if (b == null || !DAY_TYPES.contains(b.dayType())) {
                    return Optional.of(at + ".bands");
                }
                if (b.rate() == null || b.rate().signum() <= 0 || b.rate().stripTrailingZeros().scale() > 2) {
                    return Optional.of(at + ".bands.rate");
                }
                if (b.label() != null && b.label().length() > 32) {
                    return Optional.of(at + ".bands.label");
                }
            }
        }
        return months.size() == 12 ? Optional.empty() : Optional.of("touSchedule.seasons.months");
    }

    /** 한 요일 유형의 구간이 00:00부터 24:00까지 이어지는가 */
    static boolean covers(List<Band> bands) {
        if (bands.isEmpty()) {
            return false;
        }
        List<int[]> ranges = new ArrayList<>();
        for (Band b : bands) {
            if (b.from() == null || b.to() == null || !TIME.matcher(b.from()).matches() || !TIME.matcher(b.to()).matches()) {
                return false;
            }
            int from = minutes(b.from());
            int to = minutes(b.to());
            if (from >= to) {
                return false;
            }
            ranges.add(new int[] {from, to});
        }
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        int at = 0;
        for (int[] r : ranges) {
            if (r[0] != at) {
                return false;
            }
            at = r[1];
        }
        return at == 24 * 60;
    }

    private static int minutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3));
    }
}
