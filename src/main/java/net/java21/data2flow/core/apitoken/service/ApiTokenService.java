package net.java21.data2flow.core.apitoken.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.ApiScope;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.apitoken.domain.ApiTokenErrorCode;
import net.java21.data2flow.core.apitoken.domain.ApiTokenSecrets;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.IssueRequest;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.IssueResponse;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.RotateResponse;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.TokenItem;
import net.java21.data2flow.core.apitoken.repository.ApiTokenRepository;
import net.java21.data2flow.core.apitoken.repository.ApiTokenRepository.NewToken;
import net.java21.data2flow.core.apitoken.repository.ApiTokenRepository.TokenRow;
import net.java21.data2flow.core.apitoken.repository.ServiceAccountRepository;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.outbox.service.OutboxWriter;
import net.java21.data2flow.core.role.service.SpaceDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 장기 토큰(API 키·MCP) 발급·목록·승인·폐기·교체(IAM-05.01~05.04, IAM-04.07, API-IAM-40~44, BR-IAM-18·19·34).
 *
 * <ul>
 *   <li>원문은 발급·교체 응답에서 한 번만 주고 DB에는 SHA-256만(BR-IAM-18). 그래서 발급에는 멱등 키 저장(응답 본문 보관)을 쓰지 않고,
 *       같은 이름 재발급을 막는 것으로 중복을 막는다(소유자 안 이름 고유)</li>
 *   <li>범위는 발급자 역할 권한을 넘을 수 없고, 쓰기·제어 범위({@link ApiScope#requiresApproval()})는 ADMIN·INTEGRATOR만 요청할 수 있다
 *       (OPERATOR·ANALYST는 읽기 범위만). ADMIN이 아니면 PENDING_APPROVAL로 만들고 ADMIN 승인 뒤 ACTIVE(BR-IAM-19)</li>
 *   <li>공간 범위는 발급자 범위의 부분집합. 발급자 범위가 제한돼 있는데 비우면 발급자 범위로 채운다</li>
 *   <li>만료일 필수, 지금부터 1년 이내(API_TOKEN_EXPIRY_INVALID). 분당 한도 기본 MCP 60·API 키 600(AIA-08.04·TSD-06.03), 1~6,000</li>
 *   <li>조직의 살아 있는 토큰(대기·활성·교체 유예)은 50개까지(NFR-11.01)</li>
 *   <li>토큰 관리는 사람이 화면에서만 한다: 장기 토큰 요청이면 403(requireInteractive, BR-IAM-19)</li>
 *   <li>폐기·교체 즉시 원천(DB)을 바꾸고, auth에 토큰 ID를 알려 gateway 검증 캐시를 지운다(API-IAM-37b tokenIds, 보통 1초 · 최대 30초)</li>
 * </ul>
 */
@Service
public class ApiTokenService {

    static final int ORGANIZATION_LIMIT = 50;
    static final int MAX_GRACE_HOURS = 24;
    static final Set<String> KINDS = Set.of("MCP", "API_KEY");
    static final Set<String> STATUSES = Set.of("PENDING_APPROVAL", "ACTIVE", "ROTATING", "REVOKED", "EXPIRED", "REJECTED");
    private static final Set<String> WRITE_SCOPE_ROLES = Set.of(BuiltinRole.ADMIN.name(), BuiltinRole.INTEGRATOR.name());

    private final RoleChecker roleChecker;
    private final ApiTokenRepository tokens;
    private final ServiceAccountRepository accounts;
    private final SpaceDirectory spaces;
    private final OutboxWriter outbox;
    private final Audits audits;
    private final Clock clock;

    public ApiTokenService(RoleChecker roleChecker, ApiTokenRepository tokens, ServiceAccountRepository accounts, SpaceDirectory spaces,
                           OutboxWriter outbox, Audits audits, Clock clock) {
        this.roleChecker = roleChecker;
        this.tokens = tokens;
        this.accounts = accounts;
        this.spaces = spaces;
        this.outbox = outbox;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-IAM-40 발급 — 201 */
    @Transactional
    public IssueResponse issue(IssueRequest req) {
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        AccessGrant grant = roleChecker.grant();
        if (req == null) {
            throw invalid("body", "Required");
        }
        String kind = req.kind() == null ? null : req.kind().strip().toUpperCase(Locale.ROOT);
        if (kind == null || !KINDS.contains(kind)) {
            throw invalid("kind", "Pattern");
        }
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty() || name.length() > 50) {
            throw invalid("name", "Size");
        }
        List<ApiScope> scopes = parseScopes(req.scopes());
        // 범위 초과(400)는 발급 권한(403)보다 먼저 본다: VIEWER가 mcp:write를 요청하면 400(TC-AIA-077)
        for (ApiScope scope : scopes) {
            boolean roleHas = grant.permissions().containsAll(scope.permissions());
            if (!roleHas || scope.requiresApproval() && !WRITE_SCOPE_ROLES.contains(grant.role())) {
                throw new BusinessException(ApiTokenErrorCode.API_TOKEN_SCOPE_EXCEEDED);
            }
        }
        roleChecker.require(Permission.API_TOKEN_ISSUE);
        String ownerType = "USER";
        long ownerId = user.userId();
        if (req.serviceAccountId() != null && !req.serviceAccountId().isBlank()) {
            roleChecker.requireAdmin();
            long accountId = parseId(req.serviceAccountId(), "serviceAccountId");
            ServiceAccountRepository.AccountRow account = accounts.find(orgId, accountId)
                    .filter(a -> "ACTIVE".equals(a.status()))
                    .orElseThrow(() -> new BusinessException(ApiTokenErrorCode.SERVICE_ACCOUNT_NOT_FOUND));
            ownerType = "SERVICE_ACCOUNT";
            ownerId = account.id();
        }
        List<Long> spaceScope = spaceScope(orgId, grant, req.spaceScope());
        Instant now = clock.instant();
        Instant expiresAt = req.expiresAt();
        if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant())) {
            throw new BusinessException(ApiTokenErrorCode.API_TOKEN_EXPIRY_INVALID);
        }
        int rate = req.rateLimitPerMin() == null ? defaultRate(kind) : req.rateLimitPerMin();
        if (rate < 1 || rate > 6000) {
            throw invalid("rateLimitPerMin", "Range");
        }
        if (tokens.countLive(orgId) >= ORGANIZATION_LIMIT) {
            throw new BusinessException(ApiTokenErrorCode.API_TOKEN_LIMIT_EXCEEDED);
        }
        if (tokens.existsName(orgId, ownerType, ownerId, name)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicated", name)));
        }
        boolean admin = BuiltinRole.ADMIN.name().equals(grant.role());
        boolean needsApproval = scopes.stream().anyMatch(ApiScope::requiresApproval);
        String status = needsApproval && !admin ? "PENDING_APPROVAL" : "ACTIVE";
        String token = ApiTokenSecrets.newToken();
        List<String> scopeCodes = scopes.stream().map(ApiScope::code).toList();
        long id = tokens.insert(new NewToken(orgId, ownerType, ownerId, kind, name, ApiTokenSecrets.displayPrefix(token),
                ApiTokenSecrets.hash(token), scopeCodes, spaceScope, expiresAt, rate, status, null,
                needsApproval && admin ? user.userId() : null, user.userId(), now));
        audits.record(audits.event(orgId, "API_TOKEN_CREATED").actor(user).target("API_TOKEN", Long.toString(id))
                .detail("kind", kind).detail("name", name).detail("scopes", scopeCodes).detail("spaceScope", spaceScope)
                .detail("ownerType", ownerType).detail("ownerId", Long.toString(ownerId)).detail("status", status)
                .detail("expiresAt", expiresAt.toString()).detail("rateLimitPerMin", rate));
        return new IssueResponse(Long.toString(id), token, ApiTokenSecrets.displayPrefix(token), status);
    }

    /** API-IAM-41 목록. owner=all은 ADMIN(조직 전체, 서비스 계정 토큰 포함), 기본 me */
    @Transactional(readOnly = true)
    public ListApiResponse<TokenItem> list(String owner, String status, String kind, Integer page, Integer size) {
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        boolean all = "all".equalsIgnoreCase(owner);
        if (all) {
            roleChecker.requireAdmin();
        } else if (owner != null && !owner.isBlank() && !"me".equalsIgnoreCase(owner)) {
            throw invalid("owner", "Pattern");
        }
        String st = upper(status);
        if (st != null && !STATUSES.contains(st)) {
            throw invalid("status", "Pattern");
        }
        String k = upper(kind);
        if (k != null && !KINDS.contains(k)) {
            throw invalid("kind", "Pattern");
        }
        PageParams params = PageParams.of(page, size);
        Long ownerUserId = all ? null : user.userId();
        Instant now = clock.instant();
        List<TokenItem> items = tokens.list(user.organizationId(), ownerUserId, st, k, params.size(), params.offset()).stream()
                .map(t -> item(t, now)).toList();
        return ListApiResponse.of(params, items, tokens.count(user.organizationId(), ownerUserId, st, k));
    }

    /** API-IAM-42 승인(ADMIN) — 대기 중인 토큰만 */
    @Transactional
    public void approve(long tokenId, String reason) {
        decide(tokenId, true, reason);
    }

    /** API-IAM-42 거절(ADMIN) */
    @Transactional
    public void reject(long tokenId, String reason) {
        decide(tokenId, false, reason);
    }

    private void decide(long tokenId, boolean approve, String reason) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        TokenRow t = tokens.find(orgId, tokenId).orElseThrow(ApiTokenService::notFound);
        int changed = tokens.updateStatus(orgId, t.id(), List.of("PENDING_APPROVAL"), approve ? "ACTIVE" : "REJECTED",
                approve ? user.userId() : null, clock.instant());
        if (changed == 0) {
            throw new BusinessException(ApiTokenErrorCode.API_TOKEN_STATE_CONFLICT);
        }
        audits.record(audits.event(orgId, approve ? "API_TOKEN_APPROVED" : "API_TOKEN_REJECTED").actor(user)
                .target("API_TOKEN", Long.toString(t.id())).detail("scopes", t.scopes()).detail("reason", reason));
    }

    /** API-IAM-43 폐기(소유자 / ADMIN) — 204. 이미 끝난 토큰도 204 */
    @Transactional
    public void revoke(long tokenId) {
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        TokenRow t = manageable(user, tokenId);
        int changed = tokens.updateStatus(user.organizationId(), t.id(), ApiTokenRepository.LIVE_STATUSES, "REVOKED", null, clock.instant());
        if (changed > 0) {
            outbox.authBlacklist(user.organizationId(), List.of(), List.of(), List.of(Long.toString(t.id())), "API_TOKEN_REVOKED");
            audits.record(audits.event(user.organizationId(), "API_TOKEN_REVOKED").actor(user).target("API_TOKEN", Long.toString(t.id()))
                    .detail("name", t.name()).detail("ownerType", t.ownerType()));
        }
    }

    /** API-IAM-44 교체(소유자 / ADMIN): 새 토큰(원문 1회) + 이전 토큰 유예 0~24시간(BR-IAM-34). ACTIVE 토큰만 */
    @Transactional
    public RotateResponse rotate(long tokenId, Integer graceHours) {
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        int grace = graceHours == null ? 0 : graceHours;
        if (grace < 0 || grace > MAX_GRACE_HOURS) {
            throw invalid("graceHours", "Range");
        }
        TokenRow old = manageable(user, tokenId);
        Instant now = clock.instant();
        if (!"ACTIVE".equals(old.status()) || !old.expiresAt().isAfter(now)) {
            throw new BusinessException(ApiTokenErrorCode.API_TOKEN_STATE_CONFLICT);
        }
        Instant graceUntil = now.plus(Duration.ofHours(grace));
        if (tokens.markRotated(user.organizationId(), old.id(), graceUntil, now) == 0) {
            throw new BusinessException(ApiTokenErrorCode.API_TOKEN_STATE_CONFLICT);
        }
        String token = ApiTokenSecrets.newToken();
        // 이름은 소유자 안에서 고유하므로 새 토큰이 이름을 잇고, 이전 토큰은 "이름#ID"로 바꾼다
        tokens.rename(user.organizationId(), old.id(), retiredName(old.name(), old.id()), now);
        long newId = tokens.insert(new NewToken(user.organizationId(), old.ownerType(), old.ownerId(), old.kind(), old.name(),
                ApiTokenSecrets.displayPrefix(token), ApiTokenSecrets.hash(token), old.scopes(), old.spaceScope(), old.expiresAt(),
                old.rateLimitPerMin(), "ACTIVE", old.id(), old.approvedBy(), user.userId(), now));
        if (grace == 0) {
            outbox.authBlacklist(user.organizationId(), List.of(), List.of(), List.of(Long.toString(old.id())), "API_TOKEN_ROTATED");
        }
        audits.record(audits.event(user.organizationId(), "API_TOKEN_ROTATED").actor(user).target("API_TOKEN", Long.toString(old.id()))
                .detail("newTokenId", Long.toString(newId)).detail("graceHours", grace).detail("graceUntil", graceUntil.toString()));
        return new RotateResponse(Long.toString(newId), token);
    }

    /** 소유자 본인(사용자 토큰) 또는 ADMIN. 남의 토큰·서비스 계정 토큰은 ADMIN이 아니면 404로 숨긴다 */
    private TokenRow manageable(CurrentUser user, long tokenId) {
        TokenRow t = tokens.find(user.organizationId(), tokenId).orElseThrow(ApiTokenService::notFound);
        boolean mine = "USER".equals(t.ownerType()) && t.ownerId() == user.userId();
        if (!mine) {
            if (!roleChecker.has(Permission.IAM_MANAGE)) {
                throw notFound();
            }
        }
        return t;
    }

    static String retiredName(String name, long id) {
        String suffix = "#" + id;
        String base = name.length() + suffix.length() > 50 ? name.substring(0, 50 - suffix.length()) : name;
        return base + suffix;
    }

    private List<Long> spaceScope(long orgId, AccessGrant grant, List<String> raw) {
        Set<Long> ids = new TreeSet<>();
        if (raw != null) {
            for (String s : raw) {
                ids.add(parseId(s, "spaceScope"));
            }
        }
        if (ids.isEmpty()) {
            return grant.spaceScope().unrestricted() ? List.of() : new ArrayList<>(new TreeSet<>(grant.spaceScope().allowedSpaceIds()));
        }
        if (spaces.existingSpaceIds(orgId, ids).size() != ids.size()) {
            throw new BusinessException(CoreErrorCode.SPACE_SCOPE_INVALID);
        }
        for (Long id : ids) {
            if (!grant.spaceScope().includes(id)) {
                throw new BusinessException(ApiTokenErrorCode.API_TOKEN_SCOPE_EXCEEDED);
            }
        }
        return new ArrayList<>(ids);
    }

    static List<ApiScope> parseScopes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw invalid("scopes", "NotEmpty");
        }
        Set<ApiScope> scopes = new LinkedHashSet<>();
        for (String code : raw) {
            scopes.add(ApiScope.fromCode(code == null ? "" : code.strip())
                    .orElseThrow(() -> invalid("scopes", "Pattern")));
        }
        return List.copyOf(scopes);
    }

    static int defaultRate(String kind) {
        return "MCP".equals(kind) ? 60 : 600;
    }

    static TokenItem item(TokenRow t, Instant now) {
        String status = t.status();
        if (("ACTIVE".equals(status) || "PENDING_APPROVAL".equals(status)) && !t.expiresAt().isAfter(now)) {
            status = "EXPIRED";
        } else if ("ROTATING".equals(status) && (t.graceUntil() == null || !t.graceUntil().isAfter(now))) {
            status = "REVOKED";
        }
        return new TokenItem(Long.toString(t.id()), t.kind(), t.name(), t.tokenPrefix(), t.ownerType(), Long.toString(t.ownerId()),
                t.ownerName(), t.scopes(), t.spaceScope().stream().map(String::valueOf).toList(), status, t.expiresAt(),
                t.rateLimitPerMin(), t.lastUsedAt(), t.lastUsedIp(), t.graceUntil(), t.createdAt());
    }

    private static long parseId(String raw, String field) {
        if (raw == null || !raw.strip().matches("[1-9][0-9]{0,18}")) {
            throw invalid(field, "Pattern");
        }
        return Long.parseLong(raw.strip());
    }

    private static String upper(String v) {
        return v == null || v.isBlank() ? null : v.strip().toUpperCase(Locale.ROOT);
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }

    private static BusinessException notFound() {
        return new BusinessException(ApiTokenErrorCode.API_TOKEN_NOT_FOUND);
    }
}
