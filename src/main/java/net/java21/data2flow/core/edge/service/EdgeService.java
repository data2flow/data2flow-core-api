package net.java21.data2flow.core.edge.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.edge.domain.EdgeErrorCode;
import net.java21.data2flow.core.edge.domain.EdgeStates;
import net.java21.data2flow.core.edge.dto.EdgeDtos.ConfigVersionResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.EdgeResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.Ref;
import net.java21.data2flow.core.edge.dto.EdgeDtos.RegistrationResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.RequestResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.UpdateResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.UpdatesResponse;
import net.java21.data2flow.core.edge.repository.EdgeRepository;
import net.java21.data2flow.core.edge.repository.EdgeRepository.ConfigVersionRow;
import net.java21.data2flow.core.edge.repository.EdgeRepository.EdgeRow;
import net.java21.data2flow.core.edge.repository.EdgeRepository.UpdateRow;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 엣지 게이트웨이 원격 관리(DSC-08.03, BR-DSC-31·32)와 에이전트 업데이트(DSC-08.04, BR-DSC-33). 쓰기 SRC_ADMIN, 조회 SRC_READ.
 * <ul>
 *   <li>등록: 일회용 토큰(24시간, 해시만 저장)과 설치 명령. 에이전트가 ingress {@code /edge/v1/register}(API-DSC-63)로 등록하면 ingress가
 *       core 내부 API-DSC-78로 토큰을 확인한다</li>
 *   <li>설정 배포: 판(version)을 만들고 배포하면 다음 하트비트(API-DSC-79)에 내려간다. 에이전트가 적용 결과(APPLIED·FAILED_ROLLED_BACK)를 보고한다.
 *       되돌리기는 예전에 적용됐던 판을 다시 배포하는 것이다</li>
 *   <li>업데이트: 관리자가 승인한 엣지만(자동 업데이트 없음, AT-DSC-24.1). 상태 점검 실패면 에이전트가 5분 안에 이전 버전으로 돌아가고
 *       FAILED_ROLLED_BACK이 된다(AT-DSC-24.2)</li>
 *   <li>재시작·로그 수집은 다음 하트비트로 전달, 폐기(REVOKED)는 되돌릴 수 없고 설정 변경 EDGE로 ingress가 1분 안에 연결을 끊는다</li>
 * </ul>
 */
@Service
public class EdgeService {

    static final String TARGET = "EDGE";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EdgeRepository edges;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;
    private final List<String> agentVersions;
    private final String hookBaseUrl;
    private final String image;

    public EdgeService(EdgeRepository edges, CoreEventPublisher publisher, RoleChecker roleChecker, Audits audits,
                       JsonMapper json, Clock clock,
                       @Value("${data2flow.core.edge.agent-versions:1.0.0}") String agentVersions,
                       @Value("${data2flow.core.edge.hook-base-url:https://data2flow-hook.java21.net}") String hookBaseUrl,
                       @Value("${data2flow.core.edge.image:ghcr.io/data2flow/data2flow-edge}") String image) {
        this.edges = edges;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
        this.agentVersions = Arrays.stream(agentVersions.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        this.hookBaseUrl = hookBaseUrl.endsWith("/") ? hookBaseUrl.substring(0, hookBaseUrl.length() - 1) : hookBaseUrl;
        this.image = image;
    }

    // ---------------------------------------------------------------- 조회

    @Transactional(readOnly = true)
    public ListApiResponse<EdgeResponse> list(String siteId, Integer page, Integer size) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Long site = id(siteId, "siteId");
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, edges.list(orgId, site, params.size(), params.offset()).stream().map(this::response).toList(),
                edges.count(orgId, site));
    }

    @Transactional(readOnly = true)
    public EdgeResponse get(long edgeId) {
        roleChecker.require(Permission.SRC_READ);
        return response(load(roleChecker.currentUser().organizationId(), edgeId));
    }

    // ---------------------------------------------------------------- 등록·수정·삭제

    /** API-DSC-62 생성(201): {@code {name, siteId}} → 일회용 등록 토큰과 설치 명령 */
    @Transactional
    public RegistrationResponse create(JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        JsonNode b = body == null ? JsonNodeFactory.instance.objectNode() : body;
        String name = name(b.get("name"));
        Long siteId = id(b.path("siteId").isNull() ? null : b.path("siteId").asString(null), "siteId");
        if (siteId == null) {
            throw invalid("siteId", "NotNull");
        }
        String type = edges.findSpaceType(orgId, siteId).orElse(null);
        if (!"SITE".equals(type)) {
            throw invalid("siteId", type == null ? "NOT_FOUND" : "NOT_SITE");
        }
        if (edges.existsName(orgId, name, null)) {
            throw invalid("name", "DUPLICATE");
        }
        Instant now = clock.instant();
        long id;
        try {
            id = edges.insert(orgId, name, siteId, user.userId(), now);
        } catch (DuplicateKeyException ex) {
            throw invalid("name", "DUPLICATE");
        }
        RegistrationResponse r = issueToken(orgId, id, name, EdgeStates.REGISTERING, now);
        audits.record(audits.event(orgId, "EDGE_CREATED").actor(user).target(TARGET, Long.toString(id)).detail("name", name)
                .detail("siteId", Long.toString(siteId)));
        return r;
    }

    /** 등록 토큰 재발급(만료·분실). 아직 등록 전(REGISTERING)만, 이전 토큰은 바로 만료 */
    @Transactional
    public RegistrationResponse reissueToken(long edgeId) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        EdgeRow e = lock(orgId, edgeId);
        if (!EdgeStates.REGISTERING.equals(e.status())) {
            throw new BusinessException(EdgeErrorCode.EDGE_STATE_CONFLICT);
        }
        Instant now = clock.instant();
        edges.updateTokensExpired(orgId, edgeId, now);
        audits.record(audits.event(orgId, "EDGE_TOKEN_REISSUED").actor(user).target(TARGET, Long.toString(edgeId)));
        return issueToken(orgId, edgeId, e.name(), e.status(), now);
    }

    /** API-DSC-62 PATCH 이름 {@code {name, baseVersion}} */
    @Transactional
    public EdgeResponse patch(long edgeId, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        int base = (int) VersionCheck.baseVersion(body);
        EdgeRow e = lock(orgId, edgeId);
        VersionCheck.require(base, e.version());
        String name = body.has("name") ? name(body.get("name")) : e.name();
        if (edges.existsName(orgId, name, edgeId)) {
            throw invalid("name", "DUPLICATE");
        }
        VersionCheck.requireUpdated(edges.updateName(orgId, edgeId, base, name, clock.instant()));
        audits.record(audits.event(orgId, "EDGE_UPDATED").actor(user).target(TARGET, Long.toString(edgeId)).detail("name", name));
        return response(load(orgId, edgeId));
    }

    /** API-DSC-62 DELETE: 폐기했거나 아직 등록 전인 엣지만(연결된 엣지는 먼저 폐기) */
    @Transactional
    public void delete(long edgeId) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        EdgeRow e = lock(orgId, edgeId);
        if (!EdgeStates.REVOKED.equals(e.status()) && !EdgeStates.REGISTERING.equals(e.status())) {
            throw new BusinessException(EdgeErrorCode.EDGE_STATE_CONFLICT);
        }
        edges.delete(orgId, edgeId);
        publisher.configDeleted(EntityType.EDGE, edgeId, e.version() + 1L, orgId);
        audits.record(audits.event(orgId, "EDGE_DELETED").actor(user).target(TARGET, Long.toString(edgeId)).detail("name", e.name()));
    }

    // ---------------------------------------------------------------- 설정 판(API-DSC-64)

    /** 설정 판 만들기 {@code {targets[{connectorKey, config}], decoders?}} → 201 {version, result: PENDING} */
    @Transactional
    public ConfigVersionResponse createConfigVersion(long edgeId, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        EdgeRow e = lock(orgId, edgeId);
        requireNotRevoked(e);
        JsonNode targets = body == null ? null : body.get("targets");
        if (targets == null || !targets.isArray() || targets.isEmpty() || targets.size() > 50) {
            throw invalid("targets", "INVALID");
        }
        int i = 0;
        for (JsonNode t : targets) {
            String field = "targets[" + i++ + "]";
            if (!t.isObject() || !t.path("connectorKey").isString() || !t.get("connectorKey").asString().matches("[a-z0-9-]{1,40}")) {
                throw invalid(field + ".connectorKey", "INVALID");
            }
            if (!t.path("config").isObject()) {
                throw invalid(field + ".config", "Type");
            }
        }
        JsonNode decoders = body.get("decoders");
        if (decoders != null && !decoders.isNull() && !decoders.isObject() && !decoders.isArray()) {
            throw invalid("decoders", "Type");
        }
        if (json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
            throw invalid("targets", "Size");
        }
        Instant now = clock.instant();
        int versionNo = edges.nextConfigVersion(orgId, edgeId);
        edges.insertConfigVersion(orgId, edgeId, versionNo, json.writeValueAsString(targets),
                decoders == null || decoders.isNull() ? null : json.writeValueAsString(decoders), user.userId(), now);
        audits.record(audits.event(orgId, "EDGE_CONFIG_CREATED").actor(user).target(TARGET, Long.toString(edgeId)).detail("version", versionNo));
        return configResponse(e, edges.findConfigVersion(orgId, edgeId, versionNo).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<ConfigVersionResponse> configVersions(long edgeId) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        EdgeRow e = load(orgId, edgeId);
        return edges.listConfigVersions(orgId, edgeId).stream().map(c -> configResponse(e, c)).toList();
    }

    /** 배포: 다음 하트비트로 내려간다(BR-DSC-32) */
    @Transactional
    public ConfigVersionResponse deploy(long edgeId, int versionNo) {
        return deploy(edgeId, versionNo, false);
    }

    /** 되돌리기: 예전에 적용됐던(APPLIED) 판을 다시 배포한다 */
    @Transactional
    public ConfigVersionResponse rollback(long edgeId, int versionNo) {
        return deploy(edgeId, versionNo, true);
    }

    private ConfigVersionResponse deploy(long edgeId, int versionNo, boolean rollback) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        EdgeRow e = lock(orgId, edgeId);
        requireNotRevoked(e);
        ConfigVersionRow c = edges.findConfigVersion(orgId, edgeId, versionNo)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (rollback && (!"APPLIED".equals(c.result()) || (e.appliedConfigVersion() != null && e.appliedConfigVersion() == versionNo))) {
            throw new BusinessException(EdgeErrorCode.EDGE_STATE_CONFLICT);
        }
        Instant now = clock.instant();
        edges.updateConfigDeployed(orgId, edgeId, versionNo, now);
        edges.updateDesiredConfig(orgId, edgeId, versionNo, now);
        publisher.configChanged(EntityType.EDGE, edgeId, e.version(), orgId);
        audits.record(audits.event(orgId, rollback ? "EDGE_CONFIG_ROLLED_BACK" : "EDGE_CONFIG_DEPLOYED").actor(user)
                .target(TARGET, Long.toString(edgeId)).detail("version", versionNo));
        EdgeRow after = load(orgId, edgeId);
        return configResponse(after, edges.findConfigVersion(orgId, edgeId, versionNo).orElseThrow());
    }

    // ---------------------------------------------------------------- 업데이트(API-DSC-65)

    @Transactional(readOnly = true)
    public UpdatesResponse updates(long edgeId) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        EdgeRow e = load(orgId, edgeId);
        return new UpdatesResponse(e.agentVersion(), latest(), agentVersions,
                edges.listUpdates(orgId, edgeId).stream().map(EdgeService::updateResponse).toList());
    }

    /** 관리자 승인 {@code {toVersion}} → 201. 연결된(ONLINE) 엣지만, 진행 중 업데이트가 없고 게시된 다른 버전이어야 한다 */
    @Transactional
    public UpdateResponse approveUpdate(long edgeId, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        EdgeRow e = lock(orgId, edgeId);
        String to = body == null || !body.path("toVersion").isString() ? null : body.get("toVersion").asString().strip();
        if (to == null || !agentVersions.contains(to)) {
            throw invalid("toVersion", to == null ? "NotBlank" : "NOT_PUBLISHED");
        }
        Instant now = clock.instant();
        if (!EdgeStates.ONLINE.equals(EdgeStates.effective(e.status(), e.lastSeenAt(), now)) || to.equals(e.agentVersion())
                || edges.findOpenUpdate(orgId, edgeId).isPresent()) {
            throw new BusinessException(EdgeErrorCode.EDGE_STATE_CONFLICT);
        }
        long id = edges.insertUpdate(orgId, edgeId, e.agentVersion() == null ? "unknown" : e.agentVersion(), to, user.userId(), now);
        audits.record(audits.event(orgId, "EDGE_UPDATE_APPROVED").actor(user).target(TARGET, Long.toString(edgeId)).detail("from", e.agentVersion())
                .detail("to", to));
        return edges.listUpdates(orgId, edgeId).stream().filter(u -> u.id() == id).findFirst().map(EdgeService::updateResponse).orElseThrow();
    }

    // ---------------------------------------------------------------- 원격 명령(API-DSC-67)

    @Transactional
    public RequestResponse command(long edgeId, String action, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        EdgeRow e = lock(orgId, edgeId);
        requireNotRevoked(e);
        Instant now = clock.instant();
        String requestId = UUID.randomUUID().toString();
        switch (action) {
            case "restart" -> edges.insertCommand(orgId, edgeId, requestId, "RESTART", "{}", user.userId(), now);
            case "collect-logs" -> {
                JsonNode m = body == null ? null : body.get("minutes");
                if (m == null || !m.isIntegralNumber() || m.asInt() < 1 || m.asInt() > 1440) {
                    throw invalid("minutes", "Range");
                }
                edges.insertCommand(orgId, edgeId, requestId, "COLLECT_LOGS", "{\"minutes\":" + m.asInt() + "}", user.userId(), now);
            }
            case "revoke" -> {
                edges.updateStatus(orgId, edgeId, EdgeStates.REVOKED, now);
                publisher.configChanged(EntityType.EDGE, edgeId, e.version() + 1L, orgId);
            }
            default -> throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        audits.record(audits.event(orgId, "EDGE_" + action.toUpperCase(Locale.ROOT).replace('-', '_')).actor(user)
                .target(TARGET, Long.toString(edgeId)).detail("requestId", requestId));
        return new RequestResponse(requestId);
    }

    // ---------------------------------------------------------------- 도우미

    private RegistrationResponse issueToken(long orgId, long edgeId, String name, String status, Instant now) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = "d2fe_" + HexFormat.of().formatHex(raw);
        Instant expires = now.plus(EdgeStates.TOKEN_TTL);
        edges.insertToken(orgId, edgeId, sha256(token), expires, now);
        String version = latest();
        String install = "docker run -d --name data2flow-edge --restart unless-stopped -v data2flow-edge:/var/lib/data2flow-edge"
                + " -e D2F_ENDPOINT=" + hookBaseUrl + " -e D2F_REGISTRATION_TOKEN=" + token + " " + image + ":" + version;
        String offline = hookBaseUrl + "/edge/v1/packages/" + version + "/data2flow-edge-" + version + ".tar.gz";
        return new RegistrationResponse(Long.toString(edgeId), name, status, token, expires, install, offline);
    }

    String latest() {
        return agentVersions.isEmpty() ? null : agentVersions.getLast();
    }

    EdgeResponse response(EdgeRow e) {
        String latest = latest();
        boolean available = latest != null && e.agentVersion() != null && !latest.equals(e.agentVersion())
                && !EdgeStates.REVOKED.equals(e.status());
        return new EdgeResponse(Long.toString(e.id()), e.name(), new Ref(Long.toString(e.siteId()), e.siteName()),
                EdgeStates.effective(e.status(), e.lastSeenAt(), clock.instant()), e.agentVersion(), e.arch(), e.certFingerprint(),
                e.appliedConfigVersion(), e.desiredConfigVersion(), e.bufferUsedBytes(), e.bufferItems(), e.droppedItems(), e.throughput(),
                e.lastSeenAt(), e.revokedAt(), latest, available, e.version(), e.createdAt(), e.updatedAt());
    }

    private ConfigVersionResponse configResponse(EdgeRow e, ConfigVersionRow c) {
        return new ConfigVersionResponse(c.versionNo(), json.readTree(c.targets()), c.decoders() == null ? null : json.readTree(c.decoders()),
                c.result(), c.error(), c.createdAt(), c.deployedAt(),
                e.desiredConfigVersion() != null && e.desiredConfigVersion() == c.versionNo(),
                e.appliedConfigVersion() != null && e.appliedConfigVersion() == c.versionNo());
    }

    static UpdateResponse updateResponse(UpdateRow u) {
        return new UpdateResponse(Long.toString(u.id()), u.fromVersion(), u.toVersion(), u.status(), u.createdAt(), u.startedAt(), u.finishedAt());
    }

    private EdgeRow load(long orgId, long edgeId) {
        return edges.findById(orgId, edgeId).orElseThrow(() -> new BusinessException(EdgeErrorCode.EDGE_NOT_FOUND));
    }

    private EdgeRow lock(long orgId, long edgeId) {
        return edges.lockById(orgId, edgeId).orElseThrow(() -> new BusinessException(EdgeErrorCode.EDGE_NOT_FOUND));
    }

    private static void requireNotRevoked(EdgeRow e) {
        if (EdgeStates.REVOKED.equals(e.status())) {
            throw new BusinessException(EdgeErrorCode.EDGE_STATE_CONFLICT);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String name(JsonNode n) {
        String name = n == null || n.isNull() ? null : n.asString("").strip();
        if (name == null || name.isEmpty() || name.length() > 100) {
            throw invalid("name", name == null || name.isEmpty() ? "NotBlank" : "Size");
        }
        return name;
    }

    private static Long id(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!raw.strip().matches("\\d{1,18}")) {
            throw invalid(field, "Type");
        }
        return Long.parseLong(raw.strip());
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }

}
