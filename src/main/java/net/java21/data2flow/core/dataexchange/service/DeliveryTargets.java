package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.common.FailureWithResponse;
import net.java21.data2flow.core.dataexchange.domain.ExchangeErrorCode;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.TestStep;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.TestTargetResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 전달 대상 만들기·검증·연결 테스트(TSD-07.02, API-TSD-56, AT-TSD-17.4). 대상 설정(JSON)은 저장하고 자격(JSON)은 SecretCipher로 따로 둔다.
 * <ul>
 *   <li>S3: {@code {endpoint, bucket, region?, prefix?}} + 자격 {@code {accessKey, secretKey}}</li>
 *   <li>SFTP: {@code {host, port?, username, directory?, hostKeySha256?}} + 자격 {@code {password} 또는 {privateKey}}</li>
 * </ul>
 * 연결 테스트는 시험 파일을 쓰고 지운다(CONNECT·WRITE·DELETE). 하나라도 실패하면 502 {@code EXPORT_TARGET_UNWRITABLE} + 단계 결과.
 */
@Component
public class DeliveryTargets {

    private final ExchangeProperties properties;
    private final Clock clock;

    public DeliveryTargets(ExchangeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 대상 종류·설정 검사(저장 전). 종류는 대문자로 돌려준다 */
    public String validate(String targetType, JsonNode target) {
        String type = targetType == null ? "" : targetType.strip().toUpperCase(Locale.ROOT);
        if (!"S3".equals(type) && !"SFTP".equals(type)) {
            throw invalid("targetType");
        }
        if (target == null || !target.isObject()) {
            throw invalid("target");
        }
        if ("S3".equals(type)) {
            String endpoint = text(target, "endpoint");
            if (endpoint == null || !endpoint.matches("(?i)https?://[^\\s/]+(/.*)?")) {
                throw invalid("target.endpoint");
            }
            String bucket = text(target, "bucket");
            if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9]")) {
                throw invalid("target.bucket");
            }
        } else {
            String host = text(target, "host");
            if (host == null || host.length() > 253 || !host.matches("[A-Za-z0-9.\\-:]+")) {
                throw invalid("target.host");
            }
            if (text(target, "username") == null) {
                throw invalid("target.username");
            }
            JsonNode port = target.get("port");
            if (port != null && !port.isNull() && (!port.canConvertToInt() || port.asInt() < 1 || port.asInt() > 65535)) {
                throw invalid("target.port");
            }
        }
        return type;
    }

    /** 대상 열기(접속까지). 실패면 IOException */
    public DeliveryTarget open(String type, JsonNode target, JsonNode credential) throws IOException {
        JsonNode c = credential == null ? null : credential;
        if ("S3".equals(type)) {
            return new S3DeliveryTarget(new S3DeliveryTarget.Settings(text(target, "endpoint"), text(target, "bucket"), text(target, "region"),
                    text(target, "prefix"), c == null ? null : text(c, "accessKey"), c == null ? null : text(c, "secretKey")),
                    properties.targetTimeout(), clock);
        }
        JsonNode port = target.get("port");
        return new SftpDeliveryTarget(new SftpDeliveryTarget.Settings(text(target, "host"), port == null || port.isNull() ? 22 : port.asInt(),
                text(target, "username"), text(target, "directory"), text(target, "hostKeySha256"),
                c == null ? null : text(c, "password"), c == null ? null : text(c, "privateKey")), properties.targetTimeout());
    }

    /** API-TSD-56 연결 테스트: 시험 파일 쓰기 → 지우기 */
    public TestTargetResponse test(String targetType, JsonNode target, JsonNode credential) {
        String type = validate(targetType, target);
        List<TestStep> steps = new ArrayList<>();
        String failure = null;
        Path temp = null;
        try (DeliveryTarget t = open(type, target, credential)) {
            String detail = t instanceof SftpDeliveryTarget s ? "hostKey " + s.fingerprint() : null;
            steps.add(new TestStep("CONNECT", true, detail));
            String name = ".data2flow-write-test-" + UUID.randomUUID() + ".txt";
            temp = Files.createTempFile("d2f-target-test", ".txt");
            Files.writeString(temp, "data2flow write test", StandardCharsets.UTF_8);
            try {
                t.put(name, temp, "text/plain");
                steps.add(new TestStep("WRITE", true, name));
            } catch (IOException ex) {
                failure = ex.getMessage();
                steps.add(new TestStep("WRITE", false, failure));
            }
            if (failure == null) {
                try {
                    t.delete(name);
                    steps.add(new TestStep("DELETE", true, null));
                } catch (IOException ex) {
                    failure = ex.getMessage();
                    steps.add(new TestStep("DELETE", false, failure));
                }
            }
        } catch (IOException | RuntimeException ex) {
            failure = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            steps.add(new TestStep("CONNECT", false, failure));
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 임시 파일
                }
            }
        }
        TestTargetResponse response = new TestTargetResponse(failure == null, steps);
        if (failure != null) {
            throw new FailureWithResponse(ExchangeErrorCode.EXPORT_TARGET_UNWRITABLE, List.of(), response, failure);
        }
        return response;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.isString() ? v.stringValue().strip() : v.toString();
        return s.isEmpty() ? null : s;
    }

    static boolean isHttpUrl(String raw) {
        try {
            URI u = URI.create(raw);
            return ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
