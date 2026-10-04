package net.java21.data2flow.core.external.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

/**
 * 외부 맥락 설정({@code data2flow.core.external.*}, DSC-06). 서비스 키는 k8s Secret·로컬 .env로만 넣는다(없으면 가짜 구현, ADR-040).
 *
 * @param kmaBaseUrl              기상청 단기예보 조회서비스 주소
 * @param airkoreaBaseUrl         에어코리아(한국환경공단) 주소
 * @param airkoreaServiceKey      측정소 찾기(API-DSC-42)에 쓰는 시스템 서비스 키(소스에 키가 없을 때). 비면 가짜 측정소 목록
 * @param holidayBaseUrl          특일 정보(공휴일) 주소
 * @param holidayServiceKey       공휴일 서비스 키. 비면 내장 목록(가짜)
 * @param httpTimeout             외부 호출 제한 시간(기본 10초)
 * @param icalAllowHttp           iCal URL에 http:// 허용(시험 전용, 기본 false — 화면 규칙은 https·webcal만)
 * @param quotaZone               일일 한도의 날짜 경계(기본 Asia/Seoul, "다음 날 00:00 KST")
 * @param kmaDailyQuota           기상청 기본 일일 한도(개발 계정 10,000)
 * @param airkoreaDailyQuota      에어코리아 기본 일일 한도(개발 계정 500)
 * @param holidayDailyQuota       특일 정보 기본 일일 한도(10,000)
 */
@ConfigurationProperties(prefix = "data2flow.core.external")
public record ExternalProperties(String kmaBaseUrl, String airkoreaBaseUrl, String airkoreaServiceKey, String holidayBaseUrl,
                                 String holidayServiceKey, Duration httpTimeout, Boolean icalAllowHttp, String quotaZone,
                                 Integer kmaDailyQuota, Integer airkoreaDailyQuota, Integer holidayDailyQuota) {

    public ExternalProperties {
        kmaBaseUrl = or(kmaBaseUrl, "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0");
        airkoreaBaseUrl = or(airkoreaBaseUrl, "https://apis.data.go.kr/B552584");
        airkoreaServiceKey = airkoreaServiceKey == null ? "" : airkoreaServiceKey.strip();
        holidayBaseUrl = or(holidayBaseUrl, "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService");
        holidayServiceKey = holidayServiceKey == null ? "" : holidayServiceKey.strip();
        httpTimeout = httpTimeout == null ? Duration.ofSeconds(10) : httpTimeout;
        icalAllowHttp = icalAllowHttp != null && icalAllowHttp;
        quotaZone = or(quotaZone, "Asia/Seoul");
        kmaDailyQuota = kmaDailyQuota == null ? 10000 : kmaDailyQuota;
        airkoreaDailyQuota = airkoreaDailyQuota == null ? 500 : airkoreaDailyQuota;
        holidayDailyQuota = holidayDailyQuota == null ? 10000 : holidayDailyQuota;
    }

    public ZoneId zone() {
        return ZoneId.of(quotaZone);
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
