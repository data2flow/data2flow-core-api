package net.java21.data2flow.core.external.provider;

import net.java21.data2flow.core.support.MutableClock;
import net.java21.data2flow.core.support.StubHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DSC-06.02 대기질 파사드 계약(ADR-040, design/external-integrations.md §2 "가짜 구현과 벤더 어댑터가 같은 계약 시험을 통과"):
 * 가까운 측정소가 거리순·최대 limit곳, 최근 값은 PM10·PM2.5·O3와 측정 시각 — TC-DSC-139(core 쪽)
 */
class AirQualityProviderContractTest {

    /** 광주 서구(상무지구 근처) */
    static final double LAT = 35.1520;
    static final double LNG = 126.8510;

    abstract static class Contract {

        abstract AirQualityProvider provider();

        @Test
        @DisplayName("[DSC-06.02][API-DSC-42] 가까운 측정소: 거리순(가장 가까운 치평동), limit 이하, 거리 km 소수 첫째 자리")
        void nearest() {
            List<AirQualityProvider.Station> stations = provider().nearestStations(LAT, LNG, 2);
            assertThat(stations).hasSize(2);
            assertThat(stations.getFirst().stationName()).isEqualTo("치평동");
            assertThat(stations.getFirst().distanceKm()).isLessThan(stations.get(1).distanceKm());
            assertThat(stations.getFirst().distanceKm()).isLessThan(1.0);
            assertThat(stations.getFirst().items()).contains("PM10", "PM25", "O3");
        }

        @Test
        @DisplayName("[DSC-06.02] 최근 값: PM10·PM2.5·O3와 측정 시각")
        void latest() {
            Optional<AirQualityProvider.AirQuality> q = provider().latest("치평동");
            assertThat(q).isPresent();
            assertThat(q.get().pm10()).isNotNull();
            assertThat(q.get().pm25()).isNotNull();
            assertThat(q.get().o3()).isNotNull();
            assertThat(q.get().measuredAt()).isNotNull();
        }
    }

    @Nested
    class Simulated extends Contract {
        final MutableClock clock = new MutableClock(MutableClock.T0);

        @Override
        AirQualityProvider provider() {
            return new SimulatedAirQualityProvider(clock);
        }

        @Test
        @DisplayName("[DSC-06.02][ADR-040] 가짜: 모르는 측정소는 빈 값, simulated=true, 같은 시각이면 같은 값")
        void simulatedOnly() {
            assertThat(provider().latest("없는곳")).isEmpty();
            assertThat(provider().descriptor().simulated()).isTrue();
            assertThat(provider().latest("중구")).isEqualTo(provider().latest("중구"));
        }
    }

    @Nested
    class AirKorea extends Contract {
        final StubHttpServer server = new StubHttpServer();
        final MutableClock clock = new MutableClock(MutableClock.T0);

        AirKorea() {
            server.on("GET", "/MsrstnInfoInqireSvc/getMsrstnList", r -> ProviderContractSupport.reply("airkorea/getMsrstnList.json"));
            server.on("GET", "/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty",
                    r -> ProviderContractSupport.reply("airkorea/getMsrstnAcctoRltmMesureDnsty.json"));
        }

        final AirKoreaAirQualityProvider provider = new AirKoreaAirQualityProvider(ProviderContractSupport.client(server, "k"), clock);

        @Override
        AirQualityProvider provider() {
            return provider;
        }

        @AfterEach
        void reset() {
            server.reset();
        }

        @Test
        @DisplayName("[DSC-06.02] 에어코리아: 예시 응답 값(PM10 35·PM2.5 21·O3 0.021, 2020-11-25 13:00 KST), 측정소 이름으로 묻고 목록은 하루 캐시")
        void adapterValues() {
            AirQualityProvider.AirQuality q = provider.latest("치평동").orElseThrow();
            assertThat(q.pm10()).isEqualTo(35.0);
            assertThat(q.pm25()).isEqualTo(21.0);
            assertThat(q.o3()).isEqualTo(0.021);
            assertThat(q.measuredAt()).isEqualTo(Instant.parse("2020-11-25T04:00:00Z"));
            assertThat(server.received("GET", ".*getMsrstnAcctoRltmMesureDnsty").getFirst().query())
                    .contains("stationName=%EC%B9%98%ED%8F%89%EB%8F%99").contains("returnType=json");
            provider.nearestStations(LAT, LNG, 5);
            provider.nearestStations(LAT, LNG, 5);
            assertThat(server.received("GET", ".*getMsrstnList")).hasSize(1);
            clock.advance(Duration.ofHours(25));
            provider.nearestStations(LAT, LNG, 5);
            assertThat(server.received("GET", ".*getMsrstnList")).hasSize(2);
            assertThat(provider.descriptor()).isEqualTo(new ProviderDescriptor("AIRKOREA", true, false));
        }

        @Test
        @DisplayName("[DSC-06.02] 에어코리아: 점검 중 '-'는 빈 값, 24:00은 다음 날 00:00, 결과 없음은 빈 값")
        void maintenanceValues() {
            server.on("GET", "/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty",
                    r -> ProviderContractSupport.reply("airkorea/rltm-maintenance.json"));
            AirQualityProvider.AirQuality q = provider.latest("치평동").orElseThrow();
            assertThat(q.pm10()).isNull();
            assertThat(q.o3()).isEqualTo(0.018);
            assertThat(q.measuredAt()).isEqualTo(Instant.parse("2020-11-25T15:00:00Z"));
            server.on("GET", "/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty",
                    r -> ProviderContractSupport.reply("holiday/getRestDeInfo-empty.json"));
            assertThat(provider.latest("치평동")).isEmpty();
        }
    }
}
