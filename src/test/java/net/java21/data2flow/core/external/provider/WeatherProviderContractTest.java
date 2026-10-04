package net.java21.data2flow.core.external.provider;

import net.java21.data2flow.contracts.external.KmaGrid;
import net.java21.data2flow.core.support.StubHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DSC-06.01 기상 파사드 계약(ADR-040): 기상청 어댑터는 문서 예시 응답을, 가짜 구현은 같은 모양의 값을 낸다 — TC-DSC-133 일부(core 쪽) */
class WeatherProviderContractTest {

    private final StubHttpServer kma = new StubHttpServer();
    private static final KmaGrid.Point GRID = new KmaGrid.Point(58, 74);

    @AfterEach
    void stop() {
        kma.reset();
    }

    @Test
    @DisplayName("[DSC-06.01][AT-DSC-09.1] 초단기실황: 40분 전이면 한 시간 앞 정시를 base_time, 서비스 키·격자를 보내고 예시 응답의 T1H 21.1·REH 84를 읽는다")
    void nowcast() {
        kma.on("GET", "/getUltraSrtNcst", r -> ProviderContractSupport.reply("kma/getUltraSrtNcst.json"));
        KmaWeatherProvider provider = new KmaWeatherProvider(ProviderContractSupport.client(kma, "my+key/="));
        // 2026-10-04 10:20 KST → base 09:00
        List<WeatherProvider.WeatherValue> values = provider.nowcast(GRID, Instant.parse("2026-10-04T01:20:00Z"));
        String query = kma.received().getFirst().query();
        assertThat(query).contains("serviceKey=my%2Bkey%2F%3D").contains("base_date=20261004").contains("base_time=0900")
                .contains("nx=58").contains("ny=74").contains("dataType=JSON");
        assertThat(values).hasSize(8);
        assertThat(values).filteredOn(v -> v.category().equals("T1H")).first().satisfies(v -> {
            assertThat(v.value()).isEqualTo(21.1);
            assertThat(v.baseAt()).isEqualTo(Instant.parse("2021-06-27T21:00:00Z"));
        });
        assertThat(values).filteredOn(v -> v.category().equals("REH")).first().extracting(WeatherProvider.WeatherValue::value).isEqualTo(84.0);
        assertThat(provider.descriptor()).isEqualTo(new ProviderDescriptor("KMA", true, false));
    }

    @Test
    @DisplayName("[DSC-06.01][AT-DSC-09.2][BR-DSC-16] 단기예보: 발표 10분 뒤의 가장 최근 발표, 대상 시각(fcstDate·fcstTime)과 '강수없음'=0")
    void forecast() {
        kma.on("GET", "/getVilageFcst", r -> ProviderContractSupport.reply("kma/getVilageFcst.json"));
        KmaWeatherProvider provider = new KmaWeatherProvider(ProviderContractSupport.client(kma, "k"));
        // 05:05 KST → 아직 05시 발표 전(10분) → 전날 23시가 아니라 같은 날 02시
        List<WeatherProvider.WeatherValue> values = provider.forecast(GRID, Instant.parse("2026-10-03T20:05:00Z"));
        assertThat(kma.received().getFirst().query()).contains("base_date=20261004").contains("base_time=0200");
        assertThat(values).hasSize(4);
        assertThat(values.getFirst().targetAt()).isEqualTo(Instant.parse("2021-06-27T21:00:00Z"));
        assertThat(values).filteredOn(v -> v.category().equals("PCP")).first().extracting(WeatherProvider.WeatherValue::value).isEqualTo(0.0);
        assertThat(KmaWeatherProvider.forecastBase(Instant.parse("2026-10-03T15:05:00Z")).toString()).isEqualTo("2026-10-03T23:00");
        assertThat(KmaWeatherProvider.forecastBase(Instant.parse("2026-10-03T17:15:00Z")).toString()).isEqualTo("2026-10-04T02:00");
    }

    @Test
    @DisplayName("[DSC-06.01][BR-DSC-17] 오류: 서비스 키 오류 XML → AUTH, 한도 초과 → QUOTA, resultCode 03 → PROTOCOL, HTTP 500 → OTHER")
    void errors() {
        KmaWeatherProvider provider = new KmaWeatherProvider(ProviderContractSupport.client(kma, "bad"));
        kma.on("GET", "/getUltraSrtNcst", r -> ProviderContractSupport.reply("kma/error-service-key.xml"));
        assertThatThrownBy(() -> provider.nowcast(GRID, Instant.parse("2026-10-04T01:50:00Z")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.AUTH));
        kma.on("GET", "/getUltraSrtNcst", r -> ProviderContractSupport.reply("kma/error-limit.xml"));
        assertThatThrownBy(() -> provider.nowcast(GRID, Instant.now()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.QUOTA));
        kma.on("GET", "/getUltraSrtNcst", r -> ProviderContractSupport.reply("kma/error-no-data.json"));
        assertThatThrownBy(() -> provider.nowcast(GRID, Instant.now()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.PROTOCOL));
        kma.on("GET", "/getUltraSrtNcst", r -> new StubHttpServer.Reply(500, "{}", Map.of()));
        assertThatThrownBy(() -> provider.nowcast(GRID, Instant.now()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.OTHER));
        kma.on("GET", "/getUltraSrtNcst", r -> new StubHttpServer.Reply(200, "not json", Map.of()));
        assertThatThrownBy(() -> provider.nowcast(GRID, Instant.now()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.PROTOCOL));
        kma.on("GET", "/getUltraSrtNcst", r -> new StubHttpServer.Reply(429, "{}", Map.of()));
        assertThatThrownBy(() -> provider.nowcast(GRID, Instant.now()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.QUOTA));
        kma.on("GET", "/getUltraSrtNcst", r -> new StubHttpServer.Reply(401, "{}", Map.of()));
        assertThatThrownBy(() -> provider.nowcast(GRID, Instant.now()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.AUTH));
        assertThat(new KmaWeatherProvider(ProviderContractSupport.client(kma, "")).descriptor().available()).isFalse();
        DataGoKrClient closed = new DataGoKrClient("http://127.0.0.1:1", "k", java.time.Duration.ofSeconds(2), ProviderContractSupport.JSON);
        assertThatThrownBy(() -> closed.get("/x", Map.of()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.OTHER));
    }

    @Test
    @DisplayName("[DSC-06.01][ADR-040] 가짜 기상: 같은 입력이면 같은 값, 실황 4항목(T1H·REH·RN1·WSD)·예보 24시간 TMP·REH, simulated=true")
    void simulated() {
        SimulatedWeatherProvider fake = new SimulatedWeatherProvider();
        Instant now = Instant.parse("2026-10-04T05:00:00Z");
        assertThat(fake.nowcast(GRID, now)).extracting(WeatherProvider.WeatherValue::category).containsExactly("T1H", "REH", "RN1", "WSD");
        assertThat(fake.nowcast(GRID, now)).isEqualTo(fake.nowcast(GRID, now));
        assertThat(fake.forecast(GRID, now)).hasSize(48).allSatisfy(v -> assertThat(v.targetAt()).isAfter(v.baseAt()));
        assertThat(fake.nowcast(GRID, now).getFirst().value()).isBetween(18.0, 26.0);
        assertThat(fake.descriptor().simulated()).isTrue();
        assertThat(DataGoKrClient.number(ProviderContractSupport.JSON.readTree("\"-\""))).isNull();
        assertThat(DataGoKrClient.number(ProviderContractSupport.JSON.readTree("3.5"))).isEqualTo(3.5);
        assertThat(DataGoKrClient.number(ProviderContractSupport.JSON.readTree("\"1.0mm\""))).isEqualTo(1.0);
        assertThat(DataGoKrClient.number(null)).isNull();
    }
}
