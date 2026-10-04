package net.java21.data2flow.core.external.provider;

import net.java21.data2flow.contracts.external.KmaGrid;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기상청 단기예보 조회서비스(VilageFcstInfoService_2.0) 어댑터. 서비스 키가 있을 때만 {@code available}.
 * <ul>
 *   <li>초단기실황 {@code getUltraSrtNcst}: 매시 정시 관측, 매시 40분 이후 제공 → 40분 전이면 한 시간 앞 정시를 base_time으로</li>
 *   <li>단기예보 {@code getVilageFcst}: 02·05·08·11·14·17·20·23시 발표, 발표 10분 뒤 제공</li>
 * </ul>
 * 날짜·시각은 KST(Asia/Seoul)다.
 */
public class KmaWeatherProvider implements WeatherProvider {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
    static final int[] FORECAST_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};

    private final DataGoKrClient client;

    public KmaWeatherProvider(DataGoKrClient client) {
        this.client = client;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor("KMA", client.hasKey(), false);
    }

    @Override
    public List<WeatherValue> nowcast(KmaGrid.Point grid, Instant now) {
        LocalDateTime base = nowcastBase(now);
        JsonNode body = client.get("/getUltraSrtNcst", params(base, grid));
        List<WeatherValue> out = new ArrayList<>();
        for (JsonNode item : DataGoKrClient.items(body)) {
            Instant at = at(item.path("baseDate").asString(), item.path("baseTime").asString());
            out.add(new WeatherValue(item.path("category").asString(), DataGoKrClient.number(item.path("obsrValue")), at, at));
        }
        return out;
    }

    @Override
    public List<WeatherValue> forecast(KmaGrid.Point grid, Instant now) {
        LocalDateTime base = forecastBase(now);
        JsonNode body = client.get("/getVilageFcst", params(base, grid));
        List<WeatherValue> out = new ArrayList<>();
        for (JsonNode item : DataGoKrClient.items(body)) {
            out.add(new WeatherValue(item.path("category").asString(), DataGoKrClient.number(item.path("fcstValue")),
                    at(item.path("baseDate").asString(), item.path("baseTime").asString()),
                    at(item.path("fcstDate").asString(), item.path("fcstTime").asString())));
        }
        return out;
    }

    private static Map<String, String> params(LocalDateTime base, KmaGrid.Point grid) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("pageNo", "1");
        p.put("numOfRows", "1000");
        p.put("dataType", "JSON");
        p.put("base_date", base.format(DATE));
        p.put("base_time", base.format(TIME));
        p.put("nx", Integer.toString(grid.nx()));
        p.put("ny", Integer.toString(grid.ny()));
        return p;
    }

    /** 초단기실황 기준 시각: 매시 40분부터 그 정시, 그 전이면 한 시간 앞 정시 */
    static LocalDateTime nowcastBase(Instant now) {
        LocalDateTime local = LocalDateTime.ofInstant(now, KST);
        LocalDateTime hour = local.withMinute(0).withSecond(0).withNano(0);
        return local.getMinute() < 40 ? hour.minusHours(1) : hour;
    }

    /** 단기예보 기준 시각: 발표 시각 + 10분이 지난 가장 최근 발표 */
    static LocalDateTime forecastBase(Instant now) {
        LocalDateTime local = LocalDateTime.ofInstant(now, KST).minusMinutes(10);
        LocalDate day = local.toLocalDate();
        for (int i = FORECAST_HOURS.length - 1; i >= 0; i--) {
            if (local.getHour() >= FORECAST_HOURS[i]) {
                return day.atTime(FORECAST_HOURS[i], 0);
            }
        }
        return day.minusDays(1).atTime(23, 0);
    }

    static Instant at(String date, String time) {
        if (date == null || date.isBlank()) {
            return null;
        }
        LocalTime t = time == null || time.isBlank() ? LocalTime.MIDNIGHT : LocalTime.parse(time, TIME);
        return LocalDate.parse(date, DATE).atTime(t).atZone(KST).toInstant();
    }
}
