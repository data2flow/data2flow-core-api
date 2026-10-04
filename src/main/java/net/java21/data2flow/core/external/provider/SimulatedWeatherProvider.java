package net.java21.data2flow.core.external.provider;

import net.java21.data2flow.contracts.external.KmaGrid;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 가짜 기상(ADR-040): 키 없이 개발·시연·시험할 수 있게 격자·시각으로 정해지는 값을 준다(하루 주기 기온 18~26℃, 습도 45~75%, 강수 0).
 * 같은 입력이면 늘 같은 값이다.
 */
public class SimulatedWeatherProvider implements WeatherProvider {

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor("SIMULATED_WEATHER", true, true);
    }

    @Override
    public List<WeatherValue> nowcast(KmaGrid.Point grid, Instant now) {
        Instant base = KmaWeatherProvider.nowcastBase(now).atZone(KmaWeatherProvider.KST).toInstant();
        return List.of(
                new WeatherValue("T1H", temperature(base), base, base),
                new WeatherValue("REH", humidity(base), base, base),
                new WeatherValue("RN1", 0.0, base, base),
                new WeatherValue("WSD", 1.0 + (grid.nx() % 3), base, base));
    }

    @Override
    public List<WeatherValue> forecast(KmaGrid.Point grid, Instant now) {
        LocalDateTime base = KmaWeatherProvider.forecastBase(now);
        Instant baseAt = base.atZone(KmaWeatherProvider.KST).toInstant();
        List<WeatherValue> out = new ArrayList<>();
        Instant first = baseAt.plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.HOURS);
        for (int h = 0; h < 24; h++) {
            Instant target = first.plus(Duration.ofHours(h));
            out.add(new WeatherValue("TMP", temperature(target), baseAt, target));
            out.add(new WeatherValue("REH", humidity(target), baseAt, target));
        }
        return out;
    }

    private static double temperature(Instant at) {
        int hour = LocalDateTime.ofInstant(at, KmaWeatherProvider.KST).getHour();
        return Math.round((22 + 4 * Math.sin((hour - 9) * Math.PI / 12)) * 10.0) / 10.0;
    }

    private static double humidity(Instant at) {
        int hour = LocalDateTime.ofInstant(at, KmaWeatherProvider.KST).getHour();
        return Math.round(60 - 15 * Math.sin((hour - 9) * Math.PI / 12));
    }
}
