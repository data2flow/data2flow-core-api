package net.java21.data2flow.core.compliance.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 리포트 PDF 해시(BR-CMP-16, CMP-02.06). PDF는 해시 자리에 {@code "SHA-256 " + 0×64}를 적어 두고(쪽 아래와 문서 정보), 그 상태 파일의 SHA-256을
 * 계산한 뒤 자리를 그 값으로 바꾼다. 그래서 해시는 "해시 표시 영역을 뺀(0으로 둔) PDF 바이트"의 해시이고, 받은 파일은 자리를 다시 0으로 돌려
 * 계산해 보면 위·변조를 알 수 있다. 자리 글자는 압축하지 않은 내용 흐름의 기본 글꼴(Courier) 문자열이라 파일 바이트에 그대로 있다.
 */
public final class ReportHash {

    public static final String MARKER = "SHA-256 ";
    public static final String PLACEHOLDER = "0".repeat(64);
    private static final Pattern EMBEDDED = Pattern.compile(Pattern.quote(MARKER) + "([0-9a-f]{64})");

    private ReportHash() {
    }

    public record Sealed(byte[] pdf, String sha256) {
    }

    /** 자리를 채운 PDF와 해시. 자리가 없으면 IllegalStateException */
    public static Sealed seal(byte[] pdf) {
        byte[] marker = (MARKER + PLACEHOLDER).getBytes(StandardCharsets.US_ASCII);
        if (indexOf(pdf, marker, 0) < 0) {
            throw new IllegalStateException("PDF에 해시 자리가 없습니다");
        }
        String sha = sha256(pdf);
        byte[] out = pdf.clone();
        replaceAll(out, marker, (MARKER + sha).getBytes(StandardCharsets.US_ASCII));
        return new Sealed(out, sha);
    }

    /** 파일에 적힌 해시. 자리를 0으로 돌려 계산한 값과 같을 때만 돌려준다(다르면 변조) */
    public static Optional<String> verify(byte[] pdf) {
        Matcher m = EMBEDDED.matcher(new String(pdf, StandardCharsets.ISO_8859_1));
        if (!m.find() || PLACEHOLDER.equals(m.group(1))) {
            return Optional.empty();
        }
        String embedded = m.group(1);
        byte[] zeroed = pdf.clone();
        replaceAll(zeroed, (MARKER + embedded).getBytes(StandardCharsets.US_ASCII), (MARKER + PLACEHOLDER).getBytes(StandardCharsets.US_ASCII));
        return embedded.equals(sha256(zeroed)) ? Optional.of(embedded) : Optional.empty();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 같은 길이로만 바꾼다(PDF 오프셋을 지키려고) */
    private static void replaceAll(byte[] data, byte[] from, byte[] to) {
        if (from.length != to.length) {
            throw new IllegalArgumentException("같은 길이로만 바꿉니다");
        }
        int i = indexOf(data, from, 0);
        while (i >= 0) {
            System.arraycopy(to, 0, data, i, to.length);
            i = indexOf(data, from, i + from.length);
        }
    }

    private static int indexOf(byte[] data, byte[] needle, int start) {
        outer:
        for (int i = start; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
