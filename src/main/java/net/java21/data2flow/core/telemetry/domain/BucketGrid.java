package net.java21.data2flow.core.telemetry.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 조회 기간에 걸치는 집계 구간 시작 시각 목록(채우기용). 1분·1시간은 UTC 기준, 1일은 기기가 속한 사이트 시간대 자정 기준이다
 * (BR-TSD-05, TSD-01.05: Asia/Seoul이면 15:00Z). 최대 {@link ResolutionPlanner#MAX_POINTS}개.
 */
public final class BucketGrid {

    private BucketGrid() {
    }

    public static List<Instant> of(Resolution level, Instant from, Instant to, ZoneId siteZone) {
        List<Instant> grid = new ArrayList<>();
        if (!level.aggregated()) {
            return grid;
        }
        if (level == Resolution.D1) {
            ZonedDateTime day = from.atZone(siteZone).truncatedTo(ChronoUnit.DAYS);
            while (day.toInstant().isBefore(to) && grid.size() < ResolutionPlanner.MAX_POINTS) {
                grid.add(day.toInstant());
                day = day.plusDays(1);
            }
            return grid;
        }
        long step = level.bucket().toSeconds();
        long start = Math.floorDiv(from.getEpochSecond(), step) * step;
        for (long s = start; Instant.ofEpochSecond(s).isBefore(to) && grid.size() < ResolutionPlanner.MAX_POINTS; s += step) {
            grid.add(Instant.ofEpochSecond(s));
        }
        return grid;
    }

    /** 집계 테이블에서 이 기간에 걸치는 구간을 읽을 때의 하한(구간 시작 &gt; 이 값) */
    public static Instant lowerBound(Resolution level, Instant from) {
        return level == Resolution.D1 ? from.minus(1, ChronoUnit.DAYS) : from.minus(level.bucket());
    }
}
