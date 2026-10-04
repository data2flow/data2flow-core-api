package net.java21.data2flow.core.external.service;

import net.java21.data2flow.core.external.provider.AirKoreaAirQualityProvider;
import net.java21.data2flow.core.external.provider.AirQualityProvider;
import net.java21.data2flow.core.external.provider.DataGoKrClient;
import net.java21.data2flow.core.external.provider.DataGoKrHolidayProvider;
import net.java21.data2flow.core.external.provider.FixedHolidayProvider;
import net.java21.data2flow.core.external.provider.HolidayProvider;
import net.java21.data2flow.core.external.provider.KmaWeatherProvider;
import net.java21.data2flow.core.external.provider.SimulatedAirQualityProvider;
import net.java21.data2flow.core.external.provider.SimulatedWeatherProvider;
import net.java21.data2flow.core.external.provider.WeatherProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * 외부 맥락 파사드 선택(ADR-040). 서비스 키가 있으면 벤더 어댑터, 없으면 가짜 구현을 준다. 쓰는 쪽(화면·플로우·[지금 갱신])은 어느 쪽인지 모른다.
 * 키는 소스 비밀값(API_KEY, 기상청·에어코리아 카드에서 입력) 또는 시스템 설정(공휴일·측정소 찾기)이다.
 */
@Component
@EnableConfigurationProperties(ExternalProperties.class)
public class ExternalProviders {

    private final ExternalProperties properties;
    private final JsonMapper json;
    private final Clock clock;
    private final SimulatedAirQualityProvider simulatedAir;
    private final AirQualityProvider systemAir;
    private final HolidayProvider holidays;

    public ExternalProviders(ExternalProperties properties, JsonMapper json, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.simulatedAir = new SimulatedAirQualityProvider(clock);
        this.systemAir = properties.airkoreaServiceKey().isBlank() ? simulatedAir : airKorea(properties.airkoreaServiceKey());
        this.holidays = properties.holidayServiceKey().isBlank() ? new FixedHolidayProvider()
                : new DataGoKrHolidayProvider(new DataGoKrClient(properties.holidayBaseUrl(), properties.holidayServiceKey(),
                properties.httpTimeout(), json));
    }

    /** 측정소 찾기 등 소스 키가 없는 호출 */
    public AirQualityProvider airQuality() {
        return systemAir;
    }

    /** 소스 키가 있으면 그 키의 어댑터, 없으면 시스템 기본 */
    public AirQualityProvider airQuality(String sourceKey) {
        return sourceKey == null || sourceKey.isBlank() ? systemAir : airKorea(sourceKey);
    }

    public WeatherProvider weather(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return new SimulatedWeatherProvider();
        }
        return new KmaWeatherProvider(new DataGoKrClient(properties.kmaBaseUrl(), sourceKey, properties.httpTimeout(), json));
    }

    public HolidayProvider holidays() {
        return holidays;
    }

    private AirQualityProvider airKorea(String key) {
        return new AirKoreaAirQualityProvider(new DataGoKrClient(properties.airkoreaBaseUrl(), key, properties.httpTimeout(), json), clock);
    }
}
