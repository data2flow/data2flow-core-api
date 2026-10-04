package net.java21.data2flow.core.board.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.board.domain.BoardErrorCode;
import net.java21.data2flow.core.board.dto.BoardDtos.ShareLinkCreated;
import net.java21.data2flow.core.board.dto.BoardDtos.ShareLinkItem;
import net.java21.data2flow.core.board.dto.BoardDtos.SharedDashboard;
import net.java21.data2flow.core.board.dto.BoardDtos.WidgetData;
import net.java21.data2flow.core.board.dto.BoardDtos.WidgetDataRequest;
import net.java21.data2flow.core.board.repository.DashboardBoardRepository;
import net.java21.data2flow.core.board.repository.DashboardBoardRepository.DashboardRow;
import net.java21.data2flow.core.board.repository.DashboardBoardRepository.ShareLinkRow;
import net.java21.data2flow.core.branding.service.BrandingService;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 읽기 전용 공유 링크(DSH-06.03, BR-DSH-10·12, API-DSH-10·15).
 * <ul>
 *   <li>만들기·목록·폐기: DASHBOARD_WRITE(ANALYST+)이고 그 대시보드가 보여야 한다(안 보이면 404 DASHBOARD_NOT_FOUND)</li>
 *   <li>토큰은 32바이트 난수(base64url), DB에는 SHA-256만. 원문 URL은 만들 때 한 번만 준다. 만료 1~90일</li>
 *   <li>공개 보기는 요청마다 DB에서 토큰·만료·폐기·대시보드 상태를 확인한다(캐시 없음 → 폐기 즉시, 60초 기준 충족).
 *       없음·만료·폐기·보관된 대시보드·만든 사람이 더 이상 볼 수 없음 → 모두 404 SHARE_LINK_INVALID</li>
 *   <li>공개 응답에서 편집 정보(소유자·판 번호·editable)를 빼고, 데이터는 링크를 만든 사람의 공간 범위로만 계산한다</li>
 * </ul>
 */
@Service
public class ShareLinkService {

    private final RoleChecker roleChecker;
    private final DashboardBoardService boards;
    private final DashboardBoardRepository repository;
    private final WidgetDataService widgets;
    private final PermissionLookup permissions;
    private final TelemetryQueryRepository telemetry;
    private final BrandingService branding;
    private final Audits audits;
    private final CoreProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    public ShareLinkService(RoleChecker roleChecker, DashboardBoardService boards, DashboardBoardRepository repository,
                            WidgetDataService widgets, PermissionLookup permissions, TelemetryQueryRepository telemetry,
                            BrandingService branding, Audits audits, CoreProperties properties, JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.boards = boards;
        this.repository = repository;
        this.widgets = widgets;
        this.permissions = permissions;
        this.telemetry = telemetry;
        this.branding = branding;
        this.audits = audits;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /** API-DSH-10 만들기 — 201, url은 이 응답에서만 */
    @Transactional
    public ShareLinkCreated create(long dashboardId, Integer expiresInDays) {
        roleChecker.require(Permission.DASHBOARD_WRITE);
        CurrentUser user = roleChecker.currentUser();
        DashboardRow d = boards.visible(dashboardId);
        if (expiresInDays == null || expiresInDays < 1 || expiresInDays > 90) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("expiresInDays", "Range", "1~90")));
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofDays(expiresInDays));
        String token = Tokens.newToken();
        long id = repository.insertShareLink(user.organizationId(), d.id(), Tokens.sha256Hex(token), expiresAt, user.userId(), now);
        audits.record(audits.event(user.organizationId(), "SHARE_LINK_CREATED").actor(user).target("SHARE_LINK", Long.toString(id))
                .detail("dashboardId", Long.toString(d.id())).detail("expiresAt", expiresAt.toString()));
        return new ShareLinkCreated(Long.toString(id), properties.webBaseUrl() + "/share/" + token, expiresAt);
    }

    /** API-DSH-10 목록(토큰·URL 없음) */
    @Transactional(readOnly = true)
    public List<ShareLinkItem> list(long dashboardId) {
        roleChecker.require(Permission.DASHBOARD_WRITE);
        CurrentUser user = roleChecker.currentUser();
        DashboardRow d = boards.visible(dashboardId);
        return repository.listShareLinks(user.organizationId(), d.id()).stream()
                .map(s -> new ShareLinkItem(Long.toString(s.id()), s.expiresAt(), s.revokedAt(), s.lastUsedAt(), Long.toString(s.createdBy()),
                        s.createdAt())).toList();
    }

    /** API-DSH-10 폐기 — 204(이미 폐기면 그대로 204) */
    @Transactional
    public void revoke(long dashboardId, long shareLinkId) {
        roleChecker.require(Permission.DASHBOARD_WRITE);
        CurrentUser user = roleChecker.currentUser();
        DashboardRow d = boards.visible(dashboardId);
        ShareLinkRow link = repository.findShareLink(user.organizationId(), d.id(), shareLinkId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (repository.revokeShareLink(user.organizationId(), link.id(), clock.instant()) > 0) {
            audits.record(audits.event(user.organizationId(), "SHARE_LINK_REVOKED").actor(user).target("SHARE_LINK", Long.toString(link.id()))
                    .detail("dashboardId", Long.toString(d.id())));
        }
    }

    /** API-DSH-15 공개 보기 */
    @Transactional
    public SharedDashboard view(String token) {
        Shared s = open(token);
        DashboardRow d = s.dashboard();
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("name", d.name());
        dashboard.put("layout", readOnlyLayout(d.layout()));
        dashboard.put("variables", json.readTree(d.variables()));
        dashboard.put("timeRange", json.readTree(d.timeRange()));
        dashboard.put("resolution", d.resolution());
        dashboard.put("refresh", d.refresh());
        return new SharedDashboard(dashboard, s.link().expiresAt(), branding.publicSummary(d.organizationId()));
    }

    /** API-DSH-15 공개 위젯 데이터 */
    @Transactional
    public WidgetData widgetData(String token, String widgetId, WidgetDataRequest req) {
        Shared s = open(token);
        DashboardRow d = s.dashboard();
        JsonNode widget = DashboardBoardService.findWidget(d, widgetId, json);
        WidgetDataRequest r = req == null ? new WidgetDataRequest(null, null, null) : req;
        WidgetDataService.Context ctx = new WidgetDataService.Context(d.organizationId(), s.grant().spaceScope(),
                telemetry.findTimezone(d.organizationId(), s.link().createdBy()));
        return widgets.compute(ctx, widget, json.readTree(d.variables()), r.variables(), r.timeRange(), json.readTree(d.timeRange()),
                r.resolution(), d.resolution());
    }

    record Shared(ShareLinkRow link, DashboardRow dashboard, AccessGrant grant) {
    }

    private Shared open(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            throw invalid();
        }
        Instant now = clock.instant();
        ShareLinkRow link = repository.findShareLinkByTokenHash(Tokens.sha256Hex(token)).orElseThrow(ShareLinkService::invalid);
        if (link.revokedAt() != null || !link.expiresAt().isAfter(now)) {
            throw invalid();
        }
        DashboardRow d = repository.findActive(link.organizationId(), link.dashboardId()).orElseThrow(ShareLinkService::invalid);
        AccessGrant grant = permissions.find(link.organizationId(), link.createdBy());
        if (!grant.has(Permission.DASHBOARD_READ) || !"ORG".equals(d.visibility()) && d.ownerUserId() != link.createdBy()) {
            throw invalid();
        }
        repository.touchShareLink(link.organizationId(), link.id(), now);
        return new Shared(link, d, grant);
    }

    /** 제어 위젯 컨트롤·편집 정보는 빼고 보낸다(BR-DSH-10). M5에는 제어 위젯이 없지만 저장된 정의에 있으면 지운다 */
    private JsonNode readOnlyLayout(String layoutJson) {
        ObjectNode layout = (ObjectNode) json.readTree(layoutJson);
        ArrayNode kept = layout.arrayNode();
        for (JsonNode w : layout.path("widgets").values()) {
            if (!"control".equals(w.path("type").asString(""))) {
                kept.add(w);
            }
        }
        layout.set("widgets", kept);
        return layout;
    }

    private static BusinessException invalid() {
        return new BusinessException(BoardErrorCode.SHARE_LINK_INVALID);
    }
}
