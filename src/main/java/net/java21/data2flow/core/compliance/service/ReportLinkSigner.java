package net.java21.data2flow.core.compliance.service;

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
 * 리포트 파일 서명 URL(API-CMP-19: 10분). {@code /api/v1/core/compliance/reports/{id}/revisions/{no}/content/{kind}?expires=..&signature=..}.
 * 키는 내보내기 링크처럼 비밀값 마스터 키에서 용도별로 이끌어 낸다. 링크가 있어도 내려받기는 로그인한 같은 조직·권한 사용자만 된다
 */
@Component
public class ReportLinkSigner {

    private final byte[] key;

    public ReportLinkSigner(Environment env) {
        String master = env.getProperty("data2flow.secrets.master-keys", "");
        if (master.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
        } else {
            this.key = HexFormat.of().parseHex(Tokens.sha256Hex("data2flow-report-link|" + master));
        }
    }

    public String url(long organizationId, long reportId, int revisionNo, long revisionId, String kind, Instant expires) {
        long epoch = expires.getEpochSecond();
        return "/api/v1/core/compliance/reports/" + reportId + "/revisions/" + revisionNo + "/content/" + kind + "?expires=" + epoch
                + "&signature=" + sign(organizationId, revisionId, kind, epoch);
    }

    /** 서명이 맞는가(만료는 따로 본다) */
    public boolean verify(long organizationId, long revisionId, String kind, long expires, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(organizationId, revisionId, kind, expires).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    String sign(long organizationId, long revisionId, String kind, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal((organizationId + "|" + revisionId + "|" + kind + "|" + expires).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
