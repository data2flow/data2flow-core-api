package net.java21.data2flow.core.external.provider;

import net.java21.data2flow.contracts.external.KmaGrid;

import java.time.Instant;
import java.util.List;

/**
 * 기상 파사드(DSC-06.01). 기상청 동네예보(초단기실황·단기예보)를 격자(nx, ny)로 묻는다. 정기 수집은 ingress가 하고(DSC 개발 계획),
 * core는 [지금 갱신](API-DSC-41)의 연결 확인과 설정 화면에 이 창구를 쓴다.
 * 구현: {@link SimulatedWeatherProvider}(가짜), {@link KmaWeatherProvider}(기상청 어댑터, 서비스 키가 있을 때만).
 */
public interface WeatherProvider {

    ProviderDescriptor descriptor();

    /** 초단기실황: 가장 최근 정시 관측(T1H 기온, REH 습도, RN1 1시간 강수, WSD 풍속 …). targetAt은 관측 시각 */
    List<WeatherValue> nowcast(KmaGrid.Point grid, Instant now);

    /** 단기예보: 가장 최근 발표(하루 8회 중)의 시각별 예보. 예보 값은 품질 5로 저장한다(BR-DSC-16) */
    List<WeatherValue> forecast(KmaGrid.Point grid, Instant now);

    /**
     * 기상 값 하나.
     *
     * @param category 기상청 항목 코드(T1H·REH·RN1·WSD·TMP·POP …)
     * @param value    숫자 값(강수 "강수없음"은 0). 숫자가 아니면 null
     * @param baseAt   발표(관측) 기준 시각
     * @param targetAt 값의 대상 시각(실황은 baseAt과 같음)
     */
    record WeatherValue(String category, Double value, Instant baseAt, Instant targetAt) {
    }
}
