package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.core.common.Tokens;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 내보내기 다운로드 서명 URL(BR-TSD-14: 1시간). {@code /api/v1/core/exports/{id}/file?expires=<유닉스 초>&signature=<hex>}.
 * 서명 키는 비밀값 마스터 키({@code data2flow.secrets.master-keys})에서 용도별로 이끌어 낸다(모든 파드가 같은 키). 링크가 있어도
 * 다운로드는 로그인한 같은 조직·TS_EXPORT 사용자만 된다(링크는 공유용이 아니라 만료용).
 */
@Component
public class ExportLinkSigner {

    private final byte[] key;

    public ExportLinkSigner(Environment env) {
        String master = env.getProperty("data2flow.secrets.master-keys", "");
        if (master.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
        } else {
            this.key = sha256(("data2flow-export-link|" + master).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 다운로드 경로(외부 경로 기준) */
    public String url(long organizationId, long jobId, Instant expires) {
        long epoch = expires.getEpochSecond();
        return "/api/v1/core/exports/" + jobId + "/file?expires=" + epoch + "&signature=" + sign(organizationId, jobId, epoch);
    }

    /** 서명이 맞는가(만료는 따로 본다) */
    public boolean verify(long organizationId, long jobId, long expires, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(organizationId, jobId, expires).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    String sign(long organizationId, long jobId, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal((organizationId + "|" + jobId + "|" + expires).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] sha256(byte[] data) {
        return HexFormat.of().parseHex(Tokens.sha256Hex(new String(data, StandardCharsets.UTF_8)));
    }
}
