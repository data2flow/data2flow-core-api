package net.java21.data2flow.core.external.provider;

import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 에어코리아(한국환경공단) 어댑터(ADR-040 "준비 중": 서비스 키가 있을 때만 {@code available}).
 * <ul>
 *   <li>측정소 목록 {@code MsrstnInfoInqireSvc/getMsrstnList}: dmX=위도, dmY=경도(WGS84). 근접 측정소 API({@code getNearbyMsrstnList})는
 *       TM 좌표를 받아야 해서 쓰지 않고, 목록을 하루 캐시해 거리(하버사인)로 고른다</li>
 *   <li>실시간 측정값 {@code ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty}: 측정소 이름으로 최근 1건. 값 "-"는 점검 중(null)</li>
 * </ul>
 */
public class AirKoreaAirQualityProvider implements AirQualityProvider {

    static final DateTimeFormatter DATA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final Duration STATION_CACHE = Duration.ofHours(24);

    private final DataGoKrClient client;
    private final Clock clock;
    private volatile List<Station> stations;
    private volatile Instant stationsAt;

    public AirKoreaAirQualityProvider(DataGoKrClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor("AIRKOREA", client.hasKey(), false);
    }

    @Override
    public List<Station> nearestStations(double lat, double lng, int limit) {
        return allStations().stream()
                .map(s -> new Station(s.stationId(), s.stationName(), s.address(), s.lat(), s.lng(),
                        AirQualityProvider.distanceKm(lat, lng, s.lat(), s.lng()), s.items()))
                .sorted(Comparator.comparingDouble(Station::distanceKm).thenComparing(Station::stationName))
                .limit(limit).toList();
    }

    @Override
    public Optional<AirQuality> latest(String stationId) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("returnType", "json");
        p.put("numOfRows", "1");
        p.put("pageNo", "1");
        p.put("stationName", stationId);
        p.put("dataTerm", "DAILY");
        p.put("ver", "1.0");
        List<JsonNode> items = DataGoKrClient.items(client.get("/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty", p));
        if (items.isEmpty()) {
            return Optional.empty();
        }
        JsonNode i = items.getFirst();
        return Optional.of(new AirQuality(stationId, dataTime(i.path("dataTime").asString("")), DataGoKrClient.number(i.path("pm10Value")),
                DataGoKrClient.number(i.path("pm25Value")), DataGoKrClient.number(i.path("o3Value"))));
    }

    private List<Station> allStations() {
        Instant now = clock.instant();
        List<Station> cached = stations;
        if (cached != null && stationsAt != null && now.isBefore(stationsAt.plus(STATION_CACHE))) {
            return cached;
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("returnType", "json");
        p.put("numOfRows", "1000");
        p.put("pageNo", "1");
        List<Station> loaded = DataGoKrClient.items(client.get("/MsrstnInfoInqireSvc/getMsrstnList", p)).stream()
                .filter(i -> DataGoKrClient.number(i.path("dmX")) != null && DataGoKrClient.number(i.path("dmY")) != null)
                .map(i -> new Station(i.path("stationName").asString(), i.path("stationName").asString(), i.path("addr").asString(""),
                        DataGoKrClient.number(i.path("dmX")), DataGoKrClient.number(i.path("dmY")), 0.0, items(i.path("item").asString(""))))
                .toList();
        stations = loaded;
        stationsAt = now;
        return loaded;
    }

    static List<String> items(String raw) {
        return Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(s -> s.replace(".", "")).toList();
    }

    /** "2020-11-25 13:00"(KST) → 시각. 24:00은 다음 날 00:00 */
    static Instant dataTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        boolean midnight = s.endsWith("24:00");
        LocalDateTime t = LocalDateTime.parse(midnight ? s.substring(0, s.length() - 5) + "00:00" : s, DATA_TIME);
        return (midnight ? t.plusDays(1) : t).atZone(KmaWeatherProvider.KST).toInstant();
    }
}
