package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.message.SourceTypes;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import net.java21.data2flow.core.source.domain.SourceModels;
import net.java21.data2flow.core.source.domain.SourceModels.SecretMeta;
import net.java21.data2flow.core.source.domain.SourceModels.SecretRow;
import net.java21.data2flow.core.source.dto.SourceDtos.SecretInfo;
import net.java21.data2flow.core.source.repository.DataSourceRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 소스 비밀값(DSC-01.05, BR-DSC-02): 요청 해석, AES-256-GCM 암호화 저장({@code source_secrets}, 키 ID kid 포함), 지문, 인증 방식별 필수 확인.
 * 평문은 {@link Secret}으로만 다루고 응답·감사·이벤트에는 종류와 지문만 싣는다.
 *
 * <p>요청 모양(API-DSC-02·04 {@code secret}):
 * <ul>
 *   <li>{@code {"kind": "PASSWORD"|"HEADER_VALUE"|"CA_CERT"|"CLIENT_CERT"|"CLIENT_KEY", "value": "…"}}</li>
 *   <li>웹(UI-DSC-02)은 인증 방식 이름을 kind로 보낸다: {@code USERPASS}→PASSWORD, {@code HEADER}→HEADER_VALUE,
 *       {@code MTLS}→value의 PEM에서 인증서(CLIENT_CERT)와 개인 키(CLIENT_KEY)를 나눈다</li>
 *   <li>{@code {"cert": "…", "key": "…", "ca"?: "…"}} (mTLS)</li>
 * </ul>
 * 값이 비어 있으면 아무것도 바꾸지 않는다(수정 시 빈 값이면 기존 값 유지).
 */
@Component
public class SourceSecrets {

    public static final String PASSWORD = "PASSWORD";
    public static final String HEADER_VALUE = "HEADER_VALUE";
    public static final String CLIENT_CERT = "CLIENT_CERT";
    public static final String CLIENT_KEY = "CLIENT_KEY";
    public static final String CA_CERT = "CA_CERT";
    /** M2 기본 유형에서 받는 종류(나머지 종류는 DSC-09.05 커넥터 인증, M5) */
    static final Set<String> KINDS = Set.of(PASSWORD, HEADER_VALUE, CLIENT_CERT, CLIENT_KEY, CA_CERT);
    static final int MAX_VALUE_CHARS = 64 * 1024;

    private static final Pattern PEM_CERT = Pattern.compile("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----");
    private static final Pattern PEM_KEY = Pattern.compile("-----BEGIN ([A-Z ]*)PRIVATE KEY-----[\\s\\S]+?-----END \\1PRIVATE KEY-----");

    private final SecretCipher cipher;
    private final DataSourceRepository repository;

    public SourceSecrets(SecretCipher cipher, DataSourceRepository repository) {
        this.cipher = cipher;
        this.repository = repository;
    }

    /** 인증 방식에 쓸 수 있는 비밀값 종류. CA_CERT(사설 CA)는 언제나 */
    public static Set<String> allowedKinds(String type, String auth) {
        if (!SourceTypes.MQTT_SUBSCRIBE.equals(type)) {
            return Set.of();
        }
        return switch (auth) {
            case SourceModels.AUTH_USERPASS -> Set.of(PASSWORD, CA_CERT);
            case SourceModels.AUTH_HEADER -> Set.of(HEADER_VALUE, CA_CERT);
            case SourceModels.AUTH_MTLS -> Set.of(CLIENT_CERT, CLIENT_KEY, CA_CERT);
            default -> Set.of(CA_CERT);
        };
    }

    /** 인증 방식에 꼭 있어야 하는 종류(DRAFT → ACTIVE 조건, domain-model §3.1) */
    public static Set<String> requiredKinds(String type, String auth) {
        if (!SourceTypes.MQTT_SUBSCRIBE.equals(type)) {
            return Set.of();
        }
        return switch (auth) {
            case SourceModels.AUTH_USERPASS -> Set.of(PASSWORD);
            case SourceModels.AUTH_HEADER -> Set.of(HEADER_VALUE);
            case SourceModels.AUTH_MTLS -> Set.of(CLIENT_CERT, CLIENT_KEY);
            default -> Set.of();
        };
    }

    /** 요청의 {@code secret}을 종류 → 값으로 푼다. 없거나 빈 값이면 빈 맵 */
    public static Map<String, Secret> parse(JsonNode node) {
        Map<String, Secret> result = new TreeMap<>();
        if (node == null || node.isNull()) {
            return result;
        }
        if (!node.isObject()) {
            throw invalid("secret", "Type");
        }
        if (node.has("cert") || node.has("key")) {
            put(result, CLIENT_CERT, node.get("cert"));
            put(result, CLIENT_KEY, node.get("key"));
            put(result, CA_CERT, node.get("ca"));
            return result;
        }
        String kind = node.path("kind").asString("").toUpperCase(Locale.ROOT);
        JsonNode value = node.get("value");
        String raw = value == null || value.isNull() ? "" : value.asString("");
        if (raw.isEmpty()) {
            return result;
        }
        switch (kind) {
            case "USERPASS", "PASSWORD" -> put(result, PASSWORD, value);
            case "HEADER", "HEADER_VALUE" -> put(result, HEADER_VALUE, value);
            case "MTLS" -> splitPem(raw, result);
            case CLIENT_CERT, CLIENT_KEY, CA_CERT -> put(result, kind, value);
            default -> throw invalid("secret.kind", "INVALID");
        }
        return result;
    }

    private static void splitPem(String raw, Map<String, Secret> result) {
        Matcher cert = PEM_CERT.matcher(raw);
        Matcher key = PEM_KEY.matcher(raw);
        if (!cert.find() || !key.find()) {
            throw invalid("secret.value", "PEM");
        }
        StringBuilder certs = new StringBuilder(cert.group());
        while (cert.find()) {
            certs.append('\n').append(cert.group());
        }
        result.put(CLIENT_CERT, Secret.of(certs.toString()));
        result.put(CLIENT_KEY, Secret.of(key.group()));
    }

    private static void put(Map<String, Secret> result, String kind, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isString()) {
            throw invalid("secret", "Type");
        }
        String v = value.asString();
        if (v.isEmpty()) {
            return;
        }
        if (v.length() > MAX_VALUE_CHARS) {
            throw invalid("secret.value", "Size");
        }
        result.put(kind, Secret.of(v));
    }

    /** 새 값이 인증 방식에 맞는 종류인가 */
    public static void checkKinds(String type, String auth, Map<String, Secret> incoming) {
        Set<String> allowed = allowedKinds(type, auth);
        for (String kind : incoming.keySet()) {
            if (!allowed.contains(kind)) {
                throw new BusinessException(SourceErrorCode.SOURCE_AUTH_UNSUPPORTED,
                        List.of(new FieldErrorDetail("secret.kind", "NOT_FOR_AUTH", null)));
            }
        }
    }

    /** 필수 종류가 (저장된 것 + 새 값)에 다 있는가. 없으면 SOURCE_SECRET_REQUIRED */
    public static void requirePresent(String type, String auth, Set<String> available) {
        for (String kind : requiredKinds(type, auth)) {
            if (!available.contains(kind)) {
                throw new BusinessException(SourceErrorCode.SOURCE_SECRET_REQUIRED,
                        List.of(new FieldErrorDetail("secret", kind, null)));
            }
        }
    }

    /** 암호화해 저장한다. 종류 → 지문 */
    public Map<String, String> store(long organizationId, long sourceId, Map<String, Secret> secrets, Instant now) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (Map.Entry<String, Secret> e : secrets.entrySet()) {
            byte[] enc = cipher.encrypt(e.getValue(), context(sourceId, e.getKey()));
            String fp = fingerprint(e.getValue());
            repository.upsertSecret(organizationId, sourceId, e.getKey(), enc, cipher.keyId(enc), fp, now);
            fingerprints.put(e.getKey(), fp);
        }
        return fingerprints;
    }

    /** 복호화(내부 실행 설정·연결 테스트 전용) */
    public Map<String, Secret> decrypt(long sourceId, List<SecretRow> rows) {
        Map<String, Secret> result = new TreeMap<>();
        for (SecretRow row : rows) {
            result.put(row.kind(), cipher.decrypt(row.ciphertext(), context(sourceId, row.kind())));
        }
        return result;
    }

    /** 화면용 정보. primary는 인증 방식의 첫 필수 종류(없으면 첫 번째) */
    public static SecretInfo primary(String type, String auth, List<SecretMeta> metas) {
        Set<String> required = requiredKinds(type, auth);
        SecretMeta chosen = metas.stream().filter(m -> required.contains(m.kind()) && !CLIENT_KEY.equals(m.kind())).findFirst()
                .orElse(metas.isEmpty() ? null : metas.getFirst());
        if (chosen == null) {
            String kind = required.stream().sorted().findFirst().orElse(null);
            return new SecretInfo(kind, false, null, null, false);
        }
        return info(chosen);
    }

    public static SecretInfo info(SecretMeta m) {
        return new SecretInfo(m.kind(), true, mask(m.fingerprint()), m.rotatedAt(), m.rotating());
    }

    /** 화면 표시: {@code ••••} + 지문 끝 4자리 */
    public static String mask(String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        return "••••" + fingerprint.substring(Math.max(0, fingerprint.length() - 4));
    }

    /** SHA-256 앞 8자리(16진수). 원문의 일부를 내보이지 않는다 */
    static String fingerprint(Secret secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.reveal().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String context(long sourceId, String kind) {
        return "data2flow_core.source_secrets:" + sourceId + ":" + kind;
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(SourceErrorCode.SOURCE_CONFIG_INVALID, List.of(new FieldErrorDetail(field, code, null)), field);
    }
}
