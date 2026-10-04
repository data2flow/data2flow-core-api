package net.java21.data2flow.core.dataexchange.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * S3 호환 전달 대상(TSD-07.02). AWS SDK 대신 표준 HTTP + 서명 v4(경로 방식 {@code /bucket/prefix/key}, 본문은
 * {@code UNSIGNED-PAYLOAD}로 파일에서 흘려 보냄)로 PUT·DELETE만 한다(pipeline 콜드 보관과 같은 방식, ADR-050).
 */
public final class S3DeliveryTarget implements DeliveryTarget {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    static final String UNSIGNED = "UNSIGNED-PAYLOAD";

    /** 설정: endpoint(https://…), bucket, region(기본 us-east-1), prefix(선택) */
    public record Settings(String endpoint, String bucket, String region, String prefix, String accessKey, String secretKey) {
    }

    private final Settings settings;
    private final HttpClient http;
    private final Duration timeout;
    private final Clock clock;

    public S3DeliveryTarget(Settings settings, Duration timeout, Clock clock) {
        this.settings = settings;
        this.timeout = timeout;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public void put(String name, Path file, String contentType) throws IOException {
        HttpResponse<String> r = send("PUT", key(name), HttpRequest.BodyPublishers.ofFile(file), contentType);
        if (r.statusCode() / 100 != 2) {
            throw new IOException("S3 PUT " + r.statusCode() + " " + preview(r.body()));
        }
    }

    @Override
    public void delete(String name) throws IOException {
        HttpResponse<String> r = send("DELETE", key(name), HttpRequest.BodyPublishers.noBody(), null);
        if (r.statusCode() / 100 != 2 && r.statusCode() != 404) {
            throw new IOException("S3 DELETE " + r.statusCode() + " " + preview(r.body()));
        }
    }

    @Override
    public void close() {
        http.close();
    }

    private String key(String name) {
        String prefix = settings.prefix() == null ? "" : settings.prefix().replaceAll("^/+|/+$", "");
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private HttpResponse<String> send(String method, String key, HttpRequest.BodyPublisher body, String contentType) throws IOException {
        String endpoint = settings.endpoint().replaceAll("/+$", "");
        URI uri = URI.create(endpoint + "/" + encode(settings.bucket()) + "/" + encodePath(key));
        String region = settings.region() == null || settings.region().isBlank() ? "us-east-1" : settings.region();
        java.time.Instant now = clock.instant();
        String amzDate = AMZ_DATE.format(now);
        String date = DATE.format(now);
        String host = uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        String canonicalHeaders = "host:" + host + "\nx-amz-content-sha256:" + UNSIGNED + "\nx-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n" + uri.getRawPath() + "\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + UNSIGNED;
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] k = hmac(("AWS4" + nullToEmpty(settings.secretKey())).getBytes(StandardCharsets.UTF_8), date);
        k = hmac(k, region);
        k = hmac(k, "s3");
        k = hmac(k, "aws4_request");
        String signature = hex(hmac(k, stringToSign));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout.multipliedBy(20))
                .header("x-amz-date", amzDate).header("x-amz-content-sha256", UNSIGNED)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + nullToEmpty(settings.accessKey()) + "/" + scope
                        + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature)
                .method(method, body);
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("중단됨");
        }
    }

    static String encodePath(String key) {
        StringBuilder out = new StringBuilder();
        for (String segment : key.split("/", -1)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(encode(segment));
        }
        return out.toString();
    }

    static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static String preview(String body) {
        return body == null ? "" : body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
