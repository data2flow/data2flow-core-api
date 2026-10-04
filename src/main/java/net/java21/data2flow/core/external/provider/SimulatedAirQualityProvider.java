package net.java21.data2flow.core.external.provider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 가짜 대기질(ADR-040 SIMULATED, design/external-integrations.md §3.2): 키 없이 측정소 자동 선택·환기 자동화 조건을 시연·시험한다.
 * 측정소는 광주·서울의 실제 에어코리아 측정소 이름·좌표 일부를 내장하고, 값은 측정소·시각으로 정해진다(같은 입력이면 같은 값).
 */
public class SimulatedAirQualityProvider implements AirQualityProvider {

    static final List<Station> STATIONS = List.of(
            station("치평동", "광주 서구 상무대로 1101", 35.1527, 126.8497),
            station("농성동", "광주 서구 화운로 82", 35.1530, 126.8890),
            station("서석동", "광주 동구 필문대로 309", 35.1442, 126.9293),
            station("두암동", "광주 북구 서방로 141", 35.1710, 126.9265),
            station("송정1동", "광주 광산구 광산로 40", 35.1393, 126.7935),
            station("주월동", "광주 남구 대남대로 213", 35.1311, 126.9049),
            station("중구", "서울 중구 덕수궁길 15", 37.564639, 126.975961),
            station("종로구", "서울 종로구 종로35가길 19", 37.572025, 127.005028),
            station("강남구", "서울 강남구 학동로 426", 37.517528, 127.047470));

    private final Clock clock;

    public SimulatedAirQualityProvider(Clock clock) {
        this.clock = clock;
    }

    private static Station station(String name, String address, double lat, double lng) {
        return new Station(name, name, address, lat, lng, 0.0, List.of("PM10", "PM25", "O3"));
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor("SIMULATED", true, true);
    }

    @Override
    public List<Station> nearestStations(double lat, double lng, int limit) {
        return STATIONS.stream()
                .map(s -> new Station(s.stationId(), s.stationName(), s.address(), s.lat(), s.lng(),
                        AirQualityProvider.distanceKm(lat, lng, s.lat(), s.lng()), s.items()))
                .sorted(Comparator.comparingDouble(Station::distanceKm).thenComparing(Station::stationName))
                .limit(limit).toList();
    }

    @Override
    public Optional<AirQuality> latest(String stationId) {
        if (STATIONS.stream().noneMatch(s -> s.stationId().equals(stationId))) {
            return Optional.empty();
        }
        Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        int h = LocalDateTime.ofInstant(hour, KmaWeatherProvider.KST).getHour();
        int seed = Math.abs(stationId.hashCode() % 10);
        double pm10 = 25 + seed + 10 * Math.sin(h * Math.PI / 12);
        double pm25 = Math.round(pm10 * 0.55);
        return Optional.of(new AirQuality(stationId, hour, (double) Math.round(pm10), pm25, 0.020 + seed / 1000.0));
    }
}
