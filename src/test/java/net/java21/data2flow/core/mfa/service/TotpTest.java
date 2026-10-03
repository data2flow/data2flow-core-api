package net.java21.data2flow.core.mfa.service;

import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** IAM-02.05 TOTP(RFC 6238, BR-IAM-25) — TC-IAM-102 */
class TotpTest {

    /** RFC 6238 부록 B의 SHA-1 비밀값 "12345678901234567890" */
    private static final String RFC_SECRET = new Base32().encodeToString("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    @DisplayName("[IAM-02.05] RFC 6238 시험 벡터(6자리)와 같다")
    void rfcVectors() {
        assertThat(Totp.codeAt(RFC_SECRET, Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThat(Totp.codeAt(RFC_SECRET, Instant.ofEpochSecond(1111111109))).isEqualTo("081804");
        assertThat(Totp.codeAt(RFC_SECRET, Instant.ofEpochSecond(1234567890))).isEqualTo("005924");
        assertThat(Totp.codeAt(RFC_SECRET, Instant.ofEpochSecond(2000000000))).isEqualTo("279037");
    }

    @Test
    @DisplayName("[IAM-02.05][AT-IAM-14.3] 앞뒤 1단계(30초)까지 허용하고 2단계 차이·형식 오류는 거부")
    void window() {
        String secret = Totp.newSecret();
        Instant now = Instant.parse("2026-10-03T00:00:15Z");
        assertThat(Totp.verify(secret, Totp.codeAt(secret, now), now)).isTrue();
        assertThat(Totp.verify(secret, Totp.codeAt(secret, now.minusSeconds(30)), now)).isTrue();
        assertThat(Totp.verify(secret, Totp.codeAt(secret, now.plusSeconds(30)), now)).isTrue();
        assertThat(Totp.verify(secret, Totp.codeAt(secret, now.minusSeconds(61)), now)).isFalse();
        assertThat(Totp.verify(secret, "12345", now)).isFalse();
        assertThat(Totp.verify(secret, null, now)).isFalse();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(Totp.otpauthUri("data2flow", "kim op", secret)).contains("data2flow:kim%20op").contains("period=30");
    }
}
