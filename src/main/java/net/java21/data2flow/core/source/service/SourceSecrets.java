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
    public static final String HMAC_KEY = "HMAC_KEY";
    public static final String TOKEN = "TOKEN";
    public static final String SAS_KEY = "SAS_KEY";
    public static final String API_KEY = "API_KEY";
    public static final String OAUTH2_CLIENT = "OAUTH2_CLIENT";
    public static final String AWS_KEYS = "AWS_KEYS";
    public static final String GCP_SERVICE_ACCOUNT = "GCP_SERVICE_ACCOUNT";
    /** 받는 종류(M2 기본 유형 5종 + DSC-09.05 커넥터 인증·DSC-01.03 Webhook 서명) */
    static final Set<String> KINDS = Set.of(PASSWORD, HEADER_VALUE, CLIENT_CERT, CLIENT_KEY, CA_CERT, HMAC_KEY, TOKEN, SAS_KEY, API_KEY,
            OAUTH2_CLIENT, AWS_KEYS, GCP_SERVICE_ACCOUNT);
    /** 커넥터 인증 방식을 모를 때(설정에 auth 없음) 받을 수 있는 종류 */
    static final Set<String> CONNECTOR_KINDS = Set.of(PASSWORD, HEADER_VALUE, CLIENT_CERT, CLIENT_KEY, CA_CERT, TOKEN, SAS_KEY, API_KEY,
            OAUTH2_CLIENT, AWS_KEYS, GCP_SERVICE_ACCOUNT);

    /**
     * 커넥터 인증 방식 → 비밀값 종류(DSC-09.05 인증 방식 매트릭스, BR-DSC-27). 키는 카탈로그 {@code authMethods}(contracts {@code AuthMethod})와
     * 소스 설정 {@code connection.auth}(또는 HTTP 커넥터 {@code auth.type}) 이름. 값의 첫 줄은 필수, 둘째 줄은 선택(CA_CERT는 늘 선택)
     */
    public static final Map<String, List<Set<String>>> AUTH_MATRIX = Map.ofEntries(
            Map.entry("NONE", List.of(Set.of(), Set.of())),
            Map.entry("USER_PASSWORD", List.of(Set.of(PASSWORD), Set.of())),
            Map.entry("USERPASS", List.of(Set.of(PASSWORD), Set.of())),
            Map.entry("BASIC", List.of(Set.of(PASSWORD), Set.of())),
            Map.entry("SASL_PLAIN", List.of(Set.of(PASSWORD), Set.of())),
            Map.entry("SASL_SCRAM_256", List.of(Set.of(PASSWORD), Set.of())),
            Map.entry("SASL_SCRAM_512", List.of(Set.of(PASSWORD), Set.of())),
            Map.entry("WS_HEADER", List.of(Set.of(HEADER_VALUE), Set.of())),
            Map.entry("HEADER", List.of(Set.of(HEADER_VALUE), Set.of())),
            Map.entry("MTLS", List.of(Set.of(CLIENT_CERT, CLIENT_KEY), Set.of())),
            Map.entry("TOKEN", List.of(Set.of(), Set.of(TOKEN, SAS_KEY))),
            Map.entry("BEARER", List.of(Set.of(TOKEN), Set.of())),
            Map.entry("API_KEY", List.of(Set.of(API_KEY), Set.of())),
            Map.entry("OAUTH2_CC", List.of(Set.of(OAUTH2_CLIENT), Set.of())),
            Map.entry("OAUTH2", List.of(Set.of(OAUTH2_CLIENT), Set.of())),
            Map.entry("AWS_SIGV4", List.of(Set.of(), Set.of(AWS_KEYS, PASSWORD))),
            Map.entry("GCP_SERVICE_ACCOUNT", List.of(Set.of(GCP_SERVICE_ACCOUNT), Set.of())));
    /** 인증서 만료 경고 시점(DSC-09.06 "만료 30일 전") */
    public static final java.time.Duration CERT_WARNING = java.time.Duration.ofDays(30);
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
        if (SourceTypes.WEBHOOK.equals(type)) {
            return Set.of(HMAC_KEY);
        }
        if (SourceTypes.CONNECTOR.equals(type)) {
            List<Set<String>> m = AUTH_MATRIX.get(auth == null ? "" : auth);
            if (m == null) {
                return CONNECTOR_KINDS;
            }
            Set<String> all = new java.util.TreeSet<>(m.get(0));
            all.addAll(m.get(1));
            all.add(CA_CERT);
            return Set.copyOf(all);
        }
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
        if (SourceTypes.WEBHOOK.equals(type)) {
            return Set.of(HMAC_KEY);
        }
        if (SourceTypes.CONNECTOR.equals(type)) {
            List<Set<String>> m = AUTH_MATRIX.get(auth == null ? "" : auth);
            return m == null ? Set.of() : m.get(0);
        }
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

    /** 커넥터 인증 방식별 {required, optional} 종류(API-DSC-56 응답 {@code authSecretKinds}, 화면 비밀값 칸) */
    public static Map<String, Map<String, List<String>>> matrixFor(List<String> authMethods) {
        Map<String, Map<String, List<String>>> out = new java.util.LinkedHashMap<>();
        for (String method : authMethods) {
            List<Set<String>> m = AUTH_MATRIX.get(method);
            if (m == null) {
                continue;
            }
            java.util.Set<String> optional = new java.util.TreeSet<>(m.get(1));
            optional.add(CA_CERT);
            out.put(method, Map.of("required", m.get(0).stream().sorted().toList(), "optional", List.copyOf(optional)));
        }
        return out;
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
            default -> {
                if (!KINDS.contains(kind)) {
                    throw invalid("secret.kind", "INVALID");
                }
                put(result, kind, value);
            }
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
            Instant notAfter = certificateNotAfter(e.getKey(), e.getValue());
            byte[] enc = cipher.encrypt(e.getValue(), context(sourceId, e.getKey()));
            String fp = fingerprint(e.getValue());
            repository.upsertSecret(organizationId, sourceId, e.getKey(), enc, cipher.keyId(enc), fp, notAfter, now);
            fingerprints.put(e.getKey(), fp);
        }
        return fingerprints;
    }

    /**
     * 무중단 교체(DSC-07.02, BR-DSC-09): 새 값을 {@code pending}으로만 저장한다(지금 값은 그대로). 종류 → 지문.
     * 그 종류의 값이 아직 없으면 바로 저장한다(교체할 이전 값이 없음)
     */
    public Map<String, String> storePending(long organizationId, long sourceId, Map<String, Secret> secrets, Instant now) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (Map.Entry<String, Secret> e : secrets.entrySet()) {
            Instant notAfter = certificateNotAfter(e.getKey(), e.getValue());
            byte[] enc = cipher.encrypt(e.getValue(), context(sourceId, e.getKey()));
            String fp = fingerprint(e.getValue());
            if (repository.setPendingSecret(organizationId, sourceId, e.getKey(), enc, cipher.keyId(enc), fp, notAfter, now) == 0) {
                repository.upsertSecret(organizationId, sourceId, e.getKey(), enc, cipher.keyId(enc), fp, notAfter, now);
            }
            fingerprints.put(e.getKey(), fp);
        }
        return fingerprints;
    }

    /** 교체 중인 새 값(실행 설정 API-DSC-50 {@code rotation.secrets}, 내부 전용) */
    public Map<String, Secret> decryptPending(long sourceId, List<SourceModels.PendingRow> rows) {
        Map<String, Secret> result = new TreeMap<>();
        for (SourceModels.PendingRow row : rows) {
            result.put(row.kind(), cipher.decrypt(row.ciphertext(), context(sourceId, row.kind())));
        }
        return result;
    }

    /**
     * PEM 인증서(CA_CERT·CLIENT_CERT)를 읽어 가장 이른 만료 시각을 돌려준다(DSC-09.06). 인증서가 아니면 400 SOURCE_CONFIG_INVALID
     * {@code secret.value} {@code PEM}. 다른 종류는 null
     */
    public static Instant certificateNotAfter(String kind, Secret secret) {
        if (!CA_CERT.equals(kind) && !CLIENT_CERT.equals(kind)) {
            return null;
        }
        try {
            java.security.cert.CertificateFactory f = java.security.cert.CertificateFactory.getInstance("X.509");
            java.util.Collection<? extends java.security.cert.Certificate> certs = f.generateCertificates(
                    new java.io.ByteArrayInputStream(secret.reveal().getBytes(StandardCharsets.US_ASCII)));
            Instant earliest = null;
            for (java.security.cert.Certificate c : certs) {
                Instant na = ((java.security.cert.X509Certificate) c).getNotAfter().toInstant();
                earliest = earliest == null || na.isBefore(earliest) ? na : earliest;
            }
            if (earliest == null) {
                throw invalid("secret.value", "PEM");
            }
            return earliest;
        } catch (java.security.cert.CertificateException | ClassCastException ex) {
            throw invalid("secret.value", "PEM");
        }
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
            return new SecretInfo(kind, false, null, null, false, null);
        }
        return info(chosen);
    }

    public static SecretInfo info(SecretMeta m) {
        return new SecretInfo(m.kind(), true, mask(m.fingerprint()), m.rotatedAt(), m.rotating(), m.certNotAfter());
    }

    /** 화면 표시: {@code ••••} + 지문 끝 4자리 */
    public static String mask(String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        return "••••" + fingerprint.substring(Math.max(0, fingerprint.length() - 4));
    }

    /** SHA-256 앞 8자리(16진수). 원문의 일부를 내보이지 않는다 */
    public static String fingerprint(Secret secret) {
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
