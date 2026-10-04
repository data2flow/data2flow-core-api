package net.java21.data2flow.core.external.provider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 실외 대기질 파사드(DSC-06.02, ADR-040, design/external-integrations.md §3.2). 화면·플로우·분석은 벤더 이름을 모르고 이 창구만 부른다.
 * 구현: {@link SimulatedAirQualityProvider}(가짜, 키 없이 시연·시험), {@link AirKoreaAirQualityProvider}(에어코리아 어댑터, 서비스 키가 있을 때만).
 */
public interface AirQualityProvider {

    ProviderDescriptor descriptor();

    /** 좌표에서 가까운 측정소(가까운 순, 최대 limit곳). 측정소 자동 선택(API-DSC-42) */
    List<Station> nearestStations(double lat, double lng, int limit);

    /** 측정소의 최근 측정값. 측정소가 없거나 값이 아직 없으면 빈 값 */
    Optional<AirQuality> latest(String stationId);

    /**
     * 측정소.
     *
     * @param stationId   측정소 식별자(에어코리아는 측정소 이름이 키)
     * @param distanceKm  요청 좌표에서의 거리(km, 소수 첫째 자리)
     * @param items       측정 항목(PM10·PM25·O3 …)
     */
    record Station(String stationId, String stationName, String address, double lat, double lng, double distanceKm,
                   List<String> items) {
    }

    /** 측정값. 없는 항목(점검 중 "-")은 null */
    record AirQuality(String stationId, Instant measuredAt, Double pm10, Double pm25, Double o3) {
    }

    /** 위·경도 사이 거리(km, 하버사인) */
    static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return Math.round(r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)) * 10.0) / 10.0;
    }
}
