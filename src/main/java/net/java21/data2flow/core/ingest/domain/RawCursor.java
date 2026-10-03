package net.java21.data2flow.core.ingest.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 원본 메시지 목록 커서(API-ING-05 커서 목록): 마지막으로 준 행의 (수신 시각, ID). 목록은 수신 시각·ID 내림차순이다.
 *
 * @param receivedAt 마지막 행 수신 시각
 * @param id         마지막 행 ID
 */
public record RawCursor(Instant receivedAt, long id) {

    public String encode() {
        String raw = receivedAt.getEpochSecond() + "." + receivedAt.getNano() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** 잘못된 값이면 IllegalArgumentException */
    public static RawCursor decode(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        int colon = raw.indexOf(':');
        int dot = raw.indexOf('.');
        if (dot < 1 || colon < dot) {
            throw new IllegalArgumentException(cursor);
        }
        Instant at = Instant.ofEpochSecond(Long.parseLong(raw.substring(0, dot)), Long.parseLong(raw.substring(dot + 1, colon)));
        return new RawCursor(at, Long.parseLong(raw.substring(colon + 1)));
    }
}
