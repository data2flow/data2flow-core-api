package net.java21.data2flow.core.extservice.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.contracts.secret.SecretMasker;
import net.java21.data2flow.core.audit.service.AuditCodes;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.extservice.dto.ExternalServiceDtos.ExternalServiceResponse;
import net.java21.data2flow.core.extservice.dto.ExternalServiceDtos.TestExternalServiceRequest;
import net.java21.data2flow.core.extservice.dto.ExternalServiceDtos.TestResultResponse;
import net.java21.data2flow.core.extservice.dto.ExternalServiceDtos.UpdateExternalServiceRequest;
import net.java21.data2flow.core.extservice.repository.ExternalServiceRepository;
import net.java21.data2flow.core.extservice.repository.ExternalServiceRepository.ExternalServiceRow;
import net.java21.data2flow.core.mail.service.MailService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 외부 서비스 설정(OPS-07.02): LLM·메일·날씨·지도·대기질. 비밀값은 AES-256-GCM으로 암호화해 저장하고(NFR-03.02) 조회 응답에는
 * 설정 여부만 준다(BR-OPS-05). 비밀값을 비워 보내면 기존 값을 유지한다(AT-OPS-14.3). 설정 JSON에 비밀값 이름의 키는 넣을 수 없다.
 * 연결 테스트는 M1에서 MAIL(SMTP 접속)만 한다. LLM·날씨 등은 파사드·가짜 구현 단계(ADR-040)라 502로 알린다.
 */
@Service
public class ExternalServiceService {

    static final Set<String> KINDS = Set.of("LLM", "MAIL", "WEATHER", "MAP", "AIRQUALITY");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ExternalServiceRepository repository;
    private final SecretCipher cipher;
    private final MailService mail;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public ExternalServiceService(ExternalServiceRepository repository, SecretCipher cipher, MailService mail,
                                  RoleChecker roleChecker, Audits audits, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.mail = mail;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-OPS-41 목록 */
    @Transactional(readOnly = true)
    public List<ExternalServiceResponse> list() {
        roleChecker.require(Permission.OPS_MANAGE);
        long orgId = roleChecker.currentUser().organizationId();
        return repository.findAll(orgId).stream().map(this::toResponse).toList();
    }

    /** API-OPS-41 저장(없으면 만든다). 수정은 baseVersion이 맞아야 한다(BR-OPS-21) */
    @Transactional
    public ExternalServiceResponse put(String rawKind, UpdateExternalServiceRequest req) {
        roleChecker.require(Permission.OPS_MANAGE);
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String kind = kind(rawKind);
        Map<String, Object> settings = validateSettings(kind, req.settings());
        String settingsJson = json.writeValueAsString(settings);
        ExternalServiceRow existing = repository.findByKind(orgId, kind).orElse(null);
        byte[] secretEnc = existing == null ? null : existing.secretEnc();
        boolean secretChanged = req.secret() != null && !req.secret().isEmpty();
        if (secretChanged) {
            secretEnc = cipher.encrypt(req.secret(), secretContext(orgId, kind));
        }
        if (existing == null) {
            if (req.baseVersion() != null && req.baseVersion() != 0) {
                VersionCheck.require(req.baseVersion(), 0);
            }
            repository.insert(orgId, kind, req.provider(), settingsJson, secretEnc, req.enabled(), user.userId(), clock.instant());
        } else {
            if (req.baseVersion() == null) {
                throw new BusinessException(CoreErrorCode.SETTING_INVALID, "baseVersion");
            }
            VersionCheck.require(req.baseVersion(), existing.version());
            VersionCheck.requireUpdated(repository.update(orgId, kind, req.baseVersion(), req.provider(), settingsJson, secretEnc,
                    req.enabled(), user.userId(), clock.instant()));
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("provider", req.provider());
        after.put("settings", settings);
        after.put("enabled", req.enabled());
        audits.record(audits.event(orgId, AuditCodes.EXTERNAL_SERVICE_CHANGED).actor(user).target("EXTERNAL_SERVICE", kind)
                .detail("after", after).detail("secretChanged", secretChanged));
        return toResponse(repository.findByKind(orgId, kind).orElseThrow());
    }

    /** API-OPS-42 연결 테스트. 저장 전 값으로도 할 수 있다 */
    @Transactional
    public TestResultResponse test(String rawKind, TestExternalServiceRequest req) {
        roleChecker.require(Permission.OPS_MANAGE);
        long orgId = roleChecker.currentUser().organizationId();
        String kind = kind(rawKind);
        ExternalServiceRow saved = repository.findByKind(orgId, kind).orElse(null);
        Map<String, Object> settings = req != null && req.settings() != null ? validateSettings(kind, req.settings())
                : saved == null ? null : json.readValue(saved.settings(), MAP);
        if (settings == null) {
            throw new BusinessException(CoreErrorCode.SETTING_INVALID, "settings");
        }
        Secret secret = req != null && req.secret() != null && !req.secret().isEmpty() ? req.secret()
                : saved == null || saved.secretEnc() == null ? null : cipher.decrypt(saved.secretEnc(), secretContext(orgId, kind));
        if (!MailService.KIND.equals(kind)) {
            throw new BusinessException(CoreErrorCode.EXTERNAL_SERVICE_TEST_FAILED, "UNSUPPORTED");
        }
        try {
            mail.testConnection(settings, secret);
        } catch (Exception ex) {
            String cause = ex.getClass().getSimpleName();
            if (saved != null) {
                repository.updateTestResult(orgId, kind, "FAILED: " + cause, clock.instant());
            }
            throw new BusinessException(CoreErrorCode.EXTERNAL_SERVICE_TEST_FAILED, cause);
        }
        if (saved != null) {
            repository.updateTestResult(orgId, kind, "OK", clock.instant());
        }
        return new TestResultResponse(true, "CONNECTED");
    }

    private ExternalServiceResponse toResponse(ExternalServiceRow row) {
        return new ExternalServiceResponse(row.kind(), row.provider(), json.readValue(row.settings(), MAP),
                row.secretEnc() != null, row.enabled(), row.lastTestAt(), row.lastTestResult(), row.version());
    }

    static String kind(String raw) {
        String kind = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw new BusinessException(CoreErrorCode.SETTING_INVALID, "kind");
        }
        return kind;
    }

    /** 비밀값은 settings가 아니라 secret 필드로만 받는다. MAIL은 host·port·from 형식을 확인한다 */
    static Map<String, Object> validateSettings(String kind, Map<String, Object> raw) {
        Map<String, Object> settings = raw == null ? Map.of() : raw;
        for (String key : settings.keySet()) {
            if (SecretMasker.isSensitiveKey(key)) {
                throw new BusinessException(CoreErrorCode.SETTING_INVALID, "settings." + key);
            }
        }
        if (MailService.KIND.equals(kind)) {
            Object host = settings.get("host");
            if (host == null || String.valueOf(host).isBlank() || String.valueOf(host).length() > 253) {
                throw new BusinessException(CoreErrorCode.SETTING_INVALID, "settings.host");
            }
            int port;
            try {
                port = Integer.parseInt(String.valueOf(settings.getOrDefault("port", 587)));
            } catch (NumberFormatException ex) {
                throw new BusinessException(CoreErrorCode.SETTING_INVALID, "settings.port");
            }
            if (port < 1 || port > 65535) {
                throw new BusinessException(CoreErrorCode.SETTING_INVALID, "settings.port");
            }
            Object from = settings.get("from");
            if (from == null || !String.valueOf(from).matches("[^@\\s]+@[^@\\s]+")) {
                throw new BusinessException(CoreErrorCode.SETTING_INVALID, "settings.from");
            }
        }
        return settings;
    }

    static String secretContext(long organizationId, String kind) {
        return "data2flow_core.external_service_config.secret_enc:" + organizationId + ":" + kind;
    }
}
