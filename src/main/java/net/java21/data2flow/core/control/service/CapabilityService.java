package net.java21.data2flow.core.control.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.capability.CapabilityCatalog;
import net.java21.data2flow.contracts.capability.CapabilityDefinition;
import net.java21.data2flow.contracts.capability.StandardCapabilities;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.control.domain.ControlErrorCode;
import net.java21.data2flow.core.control.dto.ControlDtos.CapabilityResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.CapabilitySummary;
import net.java21.data2flow.core.control.repository.CapabilityRepository;
import net.java21.data2flow.core.control.repository.CapabilityRepository.CapabilityRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 기능 카탈로그(ACT-01.01·01.02, API-ACT-25): 표준 7종(contracts JSON, 수정 불가) + 조직의 사용자 정의 {@code custom.*}(BR-ACT-22).
 * 조회는 DEV_READ, 추가·수정은 CAPABILITY_MANAGE(ADMIN·INTEGRATOR).
 */
@Service
public class CapabilityService {

    static final String AUDIT_CREATED = "CAPABILITY_CREATED";
    static final String AUDIT_UPDATED = "CAPABILITY_UPDATED";
    private static final Logger log = LoggerFactory.getLogger(CapabilityService.class);

    private final CapabilityRepository repository;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public CapabilityService(CapabilityRepository repository, RoleChecker roleChecker, Audits audits, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** 조직의 카탈로그(표준 + 사용자 정의). 저장된 정의가 깨졌으면 그 항목만 빼고 로그를 남긴다 */
    @Transactional(readOnly = true)
    public CapabilityCatalog catalog(long organizationId) {
        List<CapabilityDefinition> custom = new ArrayList<>();
        for (CapabilityRow row : repository.listByOrganization(organizationId)) {
            try {
                custom.add(definition(row));
            } catch (RuntimeException ex) {
                log.warn("사용자 정의 기능 {} 정의를 읽지 못했습니다: {}", row.name(), ex.getMessage());
            }
        }
        return CapabilityCatalog.of(custom);
    }

    /** API-ACT-25 목록 — DEV_READ */
    public ListApiResponse<CapabilitySummary> list(Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        CapabilityCatalog catalog = catalog(roleChecker.currentUser().organizationId());
        PageParams params = PageParams.of(page, size);
        List<CapabilitySummary> all = catalog.all().stream()
                .map(d -> new CapabilitySummary(d.name(), d.version(), d.standard(), d.matterCluster(), d.attributes().size(),
                        d.commands().size()))
                .toList();
        int from = (int) Math.min(params.offset(), all.size());
        int to = Math.min(from + params.size(), all.size());
        return ListApiResponse.of(params, all.subList(from, to), all.size());
    }

    /** API-ACT-25 상세 — DEV_READ. 없으면 404 CAPABILITY_NOT_FOUND */
    public CapabilityResponse get(String name) {
        roleChecker.require(Permission.DEV_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Optional<CapabilityDefinition> standard = StandardCapabilities.find(name);
        if (standard.isPresent()) {
            return response(standard.get(), null);
        }
        CapabilityRow row = repository.findByName(orgId, name)
                .orElseThrow(() -> new BusinessException(ControlErrorCode.CAPABILITY_NOT_FOUND));
        return response(definition(row), row.updatedAt());
    }

    /** API-ACT-25 추가 — CAPABILITY_MANAGE. 표준 이름 409 CAPABILITY_NAME_RESERVED, 접두사·정의 오류 400 */
    @Transactional
    public CapabilityResponse create(JsonNode body) {
        roleChecker.require(Permission.CAPABILITY_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        String name = body == null ? null : text(body.get("name"));
        if (name == null) {
            throw invalid("name", "NotBlank", null);
        }
        if (StandardCapabilities.isStandardName(name)) {
            throw new BusinessException(ControlErrorCode.CAPABILITY_NAME_RESERVED);
        }
        if (!StandardCapabilities.isValidCustomName(name)) {
            throw invalid("name", "Pattern", "custom.*");
        }
        CapabilityDefinition definition = parse(name, 1, body);
        Instant now = clock.instant();
        try {
            repository.insert(user.organizationId(), name, write(definition.attributes()), write(definition.commands()),
                    write(definition.expectedEffects()), definition.matterCluster(), now);
        } catch (DuplicateKeyException ex) {
            throw invalid("name", "DUPLICATED", null);
        }
        audits.record(audits.event(user.organizationId(), AUDIT_CREATED).actor(user).target("CAPABILITY", name));
        return response(definition, now);
    }

    /** API-ACT-25 수정(custom.*만) — CAPABILITY_MANAGE. 표준 기능은 409 CAPABILITY_NAME_RESERVED(BR-ACT-22) */
    @Transactional
    public CapabilityResponse update(String name, JsonNode body) {
        roleChecker.require(Permission.CAPABILITY_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        if (StandardCapabilities.isStandardName(name)) {
            throw new BusinessException(ControlErrorCode.CAPABILITY_NAME_RESERVED);
        }
        CapabilityRow row = repository.findByName(user.organizationId(), name)
                .orElseThrow(() -> new BusinessException(ControlErrorCode.CAPABILITY_NOT_FOUND));
        CapabilityDefinition definition = parse(name, row.versionNo() + 1, body);
        Instant now = clock.instant();
        repository.update(user.organizationId(), name, write(definition.attributes()), write(definition.commands()),
                write(definition.expectedEffects()), definition.matterCluster(), now);
        audits.record(audits.event(user.organizationId(), AUDIT_UPDATED).actor(user).target("CAPABILITY", name)
                .detail("version", definition.version()));
        return response(definition, now);
    }

    /** API-ACT-41 내부: 조직의 사용자 정의 기능 전체(표준은 contracts) */
    @Transactional(readOnly = true)
    public List<CapabilityResponse> internalCustom(long organizationId) {
        List<CapabilityResponse> result = new ArrayList<>();
        for (CapabilityRow row : repository.listByOrganization(organizationId)) {
            try {
                result.add(response(definition(row), row.updatedAt()));
            } catch (RuntimeException ex) {
                log.warn("사용자 정의 기능 {} 정의를 읽지 못했습니다: {}", row.name(), ex.getMessage());
            }
        }
        return result;
    }

    CapabilityDefinition definition(CapabilityRow row) {
        ObjectNode node = json.createObjectNode();
        node.put("name", row.name());
        node.put("version", row.versionNo());
        node.put("standard", false);
        if (row.matterCluster() != null) {
            node.put("matterCluster", row.matterCluster());
        }
        node.set("attributes", json.readTree(row.attributes()));
        node.set("commands", json.readTree(row.commands()));
        if (row.expectedEffects() != null) {
            node.set("expectedEffects", json.readTree(row.expectedEffects()));
        }
        return json.treeToValue(node, CapabilityDefinition.class);
    }

    private CapabilityDefinition parse(String name, int version, JsonNode body) {
        if (body == null || !body.has("attributes") || !body.get("attributes").isArray()) {
            throw invalid("attributes", "NotNull", null);
        }
        ObjectNode node = json.createObjectNode();
        node.put("name", name);
        node.put("version", version);
        node.put("standard", false);
        String matter = text(body.get("matterCluster"));
        if (matter != null) {
            node.put("matterCluster", matter);
        }
        node.set("attributes", body.get("attributes"));
        node.set("commands", body.has("commands") && body.get("commands").isArray() ? body.get("commands") : json.createArrayNode());
        if (body.has("expectedEffects") && body.get("expectedEffects").isArray()) {
            node.set("expectedEffects", body.get("expectedEffects"));
        }
        try {
            return json.treeToValue(node, CapabilityDefinition.class);
        } catch (RuntimeException ex) {
            String message = ex.getCause() instanceof IllegalArgumentException iae ? iae.getMessage() : ex.getMessage();
            throw invalid("attributes", "INVALID", message);
        }
    }

    private String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    private static CapabilityResponse response(CapabilityDefinition d, Instant updatedAt) {
        return new CapabilityResponse(d.name(), d.version(), d.standard(), d.matterCluster(), d.attributes(), d.commands(),
                d.expectedEffects(), updatedAt);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || !node.isString() || node.asString().isBlank() ? null : node.asString().strip();
    }

    static BusinessException invalid(String field, String code, String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, message)));
    }
}
