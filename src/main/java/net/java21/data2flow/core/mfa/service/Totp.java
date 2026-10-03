package net.java21.data2flow.core.mfa.service;

import org.apache.commons.codec.binary.Base32;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * TOTP(RFC 6238): HMAC-SHA1, 30초, 6자리, 앞뒤 1단계 허용(BR-IAM-25). 비밀값은 20바이트 난수의 Base32 표기다.
 */
public final class Totp {

    static final int PERIOD_SECONDS = 30;
    static final int DIGITS = 6;
    static final int WINDOW = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return new Base32().encodeToString(bytes).replace("=", "");
    }

    /** 인증 앱 등록 URI(QR) */
    public static String otpauthUri(String issuer, String account, String secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /** 현재 시각 기준 앞뒤 1단계 안의 코드인가 */
    public static boolean verify(String secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long step = now.getEpochSecond() / PERIOD_SECONDS;
        boolean ok = false;
        for (int i = -WINDOW; i <= WINDOW; i++) {
            // 시간 차이로 맞은 단계가 드러나지 않게 모두 비교한다
            ok |= MessageDigest.isEqual(code(secret, step + i).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII));
        }
        return ok;
    }

    /** 해당 시간 단계의 코드 */
    public static String code(String secret, long step) {
        try {
            byte[] key = new Base32().decode(secret);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("TOTP 계산 실패", ex);
        }
    }

    public static String codeAt(String secret, Instant instant) {
        return code(secret, instant.getEpochSecond() / PERIOD_SECONDS);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
