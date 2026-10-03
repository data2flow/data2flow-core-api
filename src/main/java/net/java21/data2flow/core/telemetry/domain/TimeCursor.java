package net.java21.data2flow.core.telemetry.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 원본 점 커서(TSD-06.01): 마지막으로 준 측정 시각. (기기, 측정 항목, 시각)이 기본키라 시각만으로 다음 위치가 정해지고,
 * 이어 받을 때 빠지거나 겹치는 점이 없다(TC-TSD-145). 값은 불투명 문자열(base64url)이다.
 */
public final class TimeCursor {

    private TimeCursor() {
    }

    public static String encode(Instant last) {
        String raw = last.getEpochSecond() + "." + last.getNano();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** 잘못된 값이면 IllegalArgumentException */
    public static Instant decode(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        int dot = raw.indexOf('.');
        if (dot < 1) {
            throw new IllegalArgumentException(cursor);
        }
        return Instant.ofEpochSecond(Long.parseLong(raw.substring(0, dot)), Long.parseLong(raw.substring(dot + 1)));
    }
}
