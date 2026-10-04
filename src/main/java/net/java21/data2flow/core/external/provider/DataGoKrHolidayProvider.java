package net.java21.data2flow.core.external.provider;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한국천문연구원 특일 정보(SpcdeInfoService) {@code getRestDeInfo} 어댑터(공휴일·대체공휴일·임시공휴일). 서비스 키가 있을 때만 {@code available}.
 * {@code locdate}는 yyyyMMdd 숫자, {@code isHoliday=Y}만 쉬는 날로 본다. 결과가 하나면 item이 객체로 온다.
 */
public class DataGoKrHolidayProvider implements HolidayProvider {

    static final DateTimeFormatter LOCDATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DataGoKrClient client;

    public DataGoKrHolidayProvider(DataGoKrClient client) {
        this.client = client;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor("DATA_GO_KR_HOLIDAY", client.hasKey(), false);
    }

    @Override
    public List<Holiday> holidays(int year) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("solYear", Integer.toString(year));
        p.put("numOfRows", "100");
        p.put("pageNo", "1");
        p.put("_type", "json");
        return DataGoKrClient.items(client.get("/getRestDeInfo", p)).stream()
                .filter(i -> "Y".equalsIgnoreCase(i.path("isHoliday").asString("Y")))
                .map(DataGoKrHolidayProvider::holiday)
                .sorted(Comparator.comparing(Holiday::date)).toList();
    }

    private static Holiday holiday(JsonNode i) {
        JsonNode d = i.path("locdate");
        String raw = d.isNumber() ? Long.toString(d.asLong()) : d.asString("");
        return new Holiday(LocalDate.parse(raw, LOCDATE), i.path("dateName").asString("공휴일"));
    }
}
