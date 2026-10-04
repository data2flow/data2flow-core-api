package net.java21.data2flow.core.board.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.board.domain.BoardErrorCode;
import net.java21.data2flow.core.board.domain.DashboardLayoutValidator;
import net.java21.data2flow.core.board.domain.DashboardTransfer;
import net.java21.data2flow.core.board.domain.TimeRanges;
import net.java21.data2flow.core.board.domain.WidgetTypes;
import net.java21.data2flow.core.board.dto.BoardDtos.Conflict;
import net.java21.data2flow.core.board.dto.BoardDtos.Created;
import net.java21.data2flow.core.board.dto.BoardDtos.DashboardResponse;
import net.java21.data2flow.core.board.dto.BoardDtos.DashboardSummary;
import net.java21.data2flow.core.board.dto.BoardDtos.DefaultDashboardResponse;
import net.java21.data2flow.core.board.dto.BoardDtos.ExportTarget;
import net.java21.data2flow.core.board.dto.BoardDtos.ExportedDashboard;
import net.java21.data2flow.core.board.dto.BoardDtos.ImportResult;
import net.java21.data2flow.core.board.dto.BoardDtos.TargetRule;
import net.java21.data2flow.core.board.dto.BoardDtos.Unmapped;
import net.java21.data2flow.core.board.dto.BoardDtos.WidgetData;
import net.java21.data2flow.core.board.dto.BoardDtos.WidgetDataRequest;
import net.java21.data2flow.core.board.dto.BoardDtos.WidgetPreviewRequest;
import net.java21.data2flow.core.board.dto.BoardDtos.WidgetTypeResponse;
import net.java21.data2flow.core.board.repository.DashboardBoardRepository;
import net.java21.data2flow.core.board.repository.DashboardBoardRepository.DashboardRow;
import net.java21.data2flow.core.board.repository.DashboardBoardRepository.NewDashboard;
import net.java21.data2flow.core.board.repository.WidgetDataRepository;
import net.java21.data2flow.core.common.FailureWithResponse;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 사용자 정의 대시보드(DSH-04.01·04.06·04.07·04.08, API-DSH-06·07·09·12·13).
 * <ul>
 *   <li>보기: DASHBOARD_READ(VIEWER+). PRIVATE는 소유자만, ORG는 조직 전체. 안 보이면 404 DASHBOARD_NOT_FOUND(BR-DSH-08)</li>
 *   <li>만들기·복제·가져오기: DASHBOARD_WRITE(ANALYST·OPERATOR+, VIEWER 403)</li>
 *   <li>고치기·지우기("대시보드 관리 권한자"): 소유자(DASHBOARD_WRITE) 또는 조직 관리자(OPS_MANAGE). 그 밖은 403</li>
 *   <li>저장은 낙관적 잠금. 판이 다르면 409 DASHBOARD_VERSION_CONFLICT + 최신 version·수정자(BR-DSH-07, AT-DSH-04.6)</li>
 *   <li>복제는 소유자가 나인 PRIVATE 사본(이름 뒤 " (복사본)")</li>
 * </ul>
 */
@Service
public class DashboardBoardService {

    static final Set<String> TABS = Set.of("mine", "shared", "favorite");
    static final Set<String> VISIBILITIES = Set.of("PRIVATE", "ORG");
    static final Set<String> RESOLUTIONS = Set.of("AUTO", "RAW", "1h", "1d");
    static final Set<String> REFRESHES = Set.of("LIVE", "30s", "1m", "5m", "OFF");
    static final String COPY_SUFFIX = " (복사본)";

    private final RoleChecker roleChecker;
    private final DashboardBoardRepository dashboards;
    private final WidgetDataRepository widgetData;
    private final WidgetDataService widgets;
    private final TelemetryQueryRepository telemetry;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public DashboardBoardService(RoleChecker roleChecker, DashboardBoardRepository dashboards, WidgetDataRepository widgetData,
                                 WidgetDataService widgets, TelemetryQueryRepository telemetry, Audits audits, JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.dashboards = dashboards;
        this.widgetData = widgetData;
        this.widgets = widgets;
        this.telemetry = telemetry;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-DSH-06 목록 */
    @Transactional(readOnly = true)
    public ListApiResponse<DashboardSummary> list(String tabParam, String keyword, Integer page, Integer size) {
        roleChecker.require(Permission.DASHBOARD_READ);
        CurrentUser user = roleChecker.currentUser();
        String tab = tabParam == null || tabParam.isBlank() ? "mine" : tabParam.strip().toLowerCase(Locale.ROOT);
        if (!TABS.contains(tab)) {
            throw new BusinessException(net.java21.data2flow.contracts.error.CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("tab", "INVALID", String.join("|", TABS))));
        }
        Set<Long> favorites = favoriteIds(user);
        PageParams params = PageParams.of(page, size);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.strip();
        List<DashboardSummary> items = dashboards.list(user.organizationId(), user.userId(), tab, kw, favorites, params.size(), params.offset())
                .stream().map(d -> new DashboardSummary(Long.toString(d.id()), d.name(), d.description(), d.visibility(),
                        Long.toString(d.ownerUserId()), d.ownerName(), favorites.contains(d.id()), d.widgetCount(), d.updatedAt()))
                .toList();
        return ListApiResponse.of(params, items, dashboards.count(user.organizationId(), user.userId(), tab, kw, favorites));
    }

    /** API-DSH-06 상세 */
    @Transactional(readOnly = true)
    public DashboardResponse get(long id) {
        roleChecker.require(Permission.DASHBOARD_READ);
        return response(visible(id));
    }

    /** API-DSH-07 생성 — 201 */
    @Transactional
    public Created create(JsonNode body) {
        roleChecker.require(Permission.DASHBOARD_WRITE);
        CurrentUser user = roleChecker.currentUser();
        NewDashboard d = parse(body, null);
        Instant now = clock.instant();
        long id = dashboards.insert(user.organizationId(), d, user.userId(), now);
        audits.record(audits.event(user.organizationId(), "DASHBOARD_CREATED").actor(user).target("DASHBOARD", Long.toString(id))
                .detail("name", d.name()).detail("visibility", d.visibility()));
        return new Created(Long.toString(id), d.name(), d.visibility(), 1);
    }

    /** API-DSH-07 저장(PUT, 본문 baseVersion) */
    @Transactional
    public DashboardResponse update(long id, JsonNode body) {
        roleChecker.require(Permission.DASHBOARD_WRITE);
        CurrentUser user = roleChecker.currentUser();
        DashboardRow before = visible(id);
        requireEditable(before);
        int base = (int) VersionCheck.baseVersion(body);
        NewDashboard d = parse(body, before);
        Instant now = clock.instant();
        if (base != before.version() || dashboards.update(user.organizationId(), id, base, d, user.userId(), now) == 0) {
            DashboardRow latest = dashboards.findActive(user.organizationId(), id).orElse(before);
            throw new FailureWithResponse(BoardErrorCode.DASHBOARD_VERSION_CONFLICT, List.of(),
                    new Conflict(latest.version(), latest.updatedBy() == null ? null : Long.toString(latest.updatedBy()),
                            latest.updatedByName(), latest.updatedAt()));
        }
        audits.record(audits.event(user.organizationId(), "DASHBOARD_UPDATED").actor(user).target("DASHBOARD", Long.toString(id))
                .detail("name", d.name()).detail("version", base + 1));
        return response(dashboards.findActive(user.organizationId(), id).orElseThrow());
    }

    /** API-DSH-07 삭제 — 204(보관). 이 대시보드를 기본으로 쓰던 사용자 설정은 비운다 */
    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.DASHBOARD_WRITE);
        CurrentUser user = roleChecker.currentUser();
        DashboardRow before = visible(id);
        requireEditable(before);
        dashboards.archive(user.organizationId(), id, user.userId(), clock.instant());
        dashboards.clearDefaultDashboard(user.organizationId(), id);
        audits.record(audits.event(user.organizationId(), "DASHBOARD_DELETED").actor(user).target("DASHBOARD", Long.toString(id))
                .detail("name", before.name()));
    }

    /** API-DSH-07 복제 — 201, 내 PRIVATE 사본 */
    @Transactional
    public Created duplicate(long id) {
        roleChecker.require(Permission.DASHBOARD_WRITE);
        CurrentUser user = roleChecker.currentUser();
        DashboardRow source = visible(id);
        String name = source.name().length() + COPY_SUFFIX.length() > 100
                ? source.name().substring(0, 100 - COPY_SUFFIX.length()) + COPY_SUFFIX : source.name() + COPY_SUFFIX;
        NewDashboard copy = new NewDashboard(name, source.description(), "PRIVATE", source.layout(), source.variables(), source.timeRange(),
                source.resolution(), source.refresh(), source.templateSource());
        long newId = dashboards.insert(user.organizationId(), copy, user.userId(), clock.instant());
        audits.record(audits.event(user.organizationId(), "DASHBOARD_CREATED").actor(user).target("DASHBOARD", Long.toString(newId))
                .detail("name", name).detail("copiedFrom", Long.toString(id)));
        return new Created(Long.toString(newId), name, "PRIVATE", 1);
    }

    /** API-DSH-07 내보내기 */
    @Transactional(readOnly = true)
    public ExportedDashboard export(long id) {
        roleChecker.require(Permission.DASHBOARD_READ);
        CurrentUser user = roleChecker.currentUser();
        DashboardRow d = visible(id);
        JsonNode layout = json.readTree(d.layout());
        List<ExportTarget> targets = new ArrayList<>();
        for (JsonNode w : layout.path("widgets").values()) {
            int index = 0;
            for (JsonNode t : w.path("targets").values()) {
                targets.add(exportTarget(user.organizationId(), w.path("id").asString("") + "/" + index++, t));
            }
        }
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("schemaVersion", DashboardTransfer.FORMAT_VERSION);
        dashboard.put("name", d.name());
        dashboard.put("description", d.description());
        dashboard.put("layout", layout);
        dashboard.put("variables", json.readTree(d.variables()));
        dashboard.put("timeRange", json.readTree(d.timeRange()));
        dashboard.put("resolution", d.resolution());
        dashboard.put("refresh", d.refresh());
        return new ExportedDashboard(DashboardTransfer.FORMAT_VERSION, clock.instant(), dashboard, targets);
    }

    private ExportTarget exportTarget(long orgId, String ref, JsonNode t) {
        String kind = t.path("kind").asString("");
        String metric = t.hasNonNull("metricKey") ? t.get("metricKey").asString() : null;
        String deviceRaw = t.hasNonNull("deviceId") ? t.get("deviceId").asString("") : null;
        String spaceRaw = t.hasNonNull("spaceId") ? t.get("spaceId").asString("") : null;
        if (deviceRaw != null && deviceRaw.matches("\\d{1,18}")) {
            var device = widgetData.findDevices(orgId, List.of(Long.parseLong(deviceRaw))).stream().findFirst();
            return new ExportTarget(ref, kind, deviceRaw, device.map(WidgetDataRepository.DeviceRef::externalId).orElse(null),
                    device.map(WidgetDataRepository.DeviceRef::name).orElse(null), null, null, metric);
        }
        if (spaceRaw != null && spaceRaw.matches("\\d{1,18}")) {
            var space = widgetData.findSpace(orgId, Long.parseLong(spaceRaw));
            return new ExportTarget(ref, kind, null, null, null, spaceRaw, space.map(WidgetDataRepository.SpaceRef::name).orElse(null), metric);
        }
        return new ExportTarget(ref, kind, deviceRaw, null, null, spaceRaw, null, metric);
    }

    /** API-DSH-07 가져오기 — 201, 매핑 실패 목록 */
    @Transactional
    public ImportResult importDashboard(JsonNode body) {
        roleChecker.require(Permission.DASHBOARD_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DashboardTransfer.Mapped mapped = DashboardTransfer.map(body, new DashboardTransfer.Lookup() {
            @Override
            public Optional<String> deviceExternalId(long deviceId) {
                return widgetData.findDevices(orgId, List.of(deviceId)).stream().findFirst().map(d -> d.externalId() == null ? "" : d.externalId());
            }

            @Override
            public Optional<Long> deviceByExternalId(String externalId) {
                return widgetData.findDeviceIdByExternalId(orgId, externalId);
            }

            @Override
            public boolean spaceExists(long spaceId) {
                return widgetData.findSpace(orgId, spaceId).isPresent();
            }

            @Override
            public Optional<Long> spaceByName(String name) {
                return widgetData.findSpaceIdByName(orgId, name);
            }
        });
        ObjectNode def = mapped.dashboard();
        if (!def.has("visibility")) {
            def.put("visibility", "PRIVATE");
        }
        NewDashboard d = parse(def, null);
        long id = dashboards.insert(orgId, d, user.userId(), clock.instant());
        audits.record(audits.event(orgId, "DASHBOARD_CREATED").actor(user).target("DASHBOARD", Long.toString(id))
                .detail("name", d.name()).detail("imported", true).detail("unmapped", mapped.unmapped().size()));
        return new ImportResult(Long.toString(id), d.name(), 1,
                mapped.unmapped().stream().map(u -> new Unmapped(u.ref(), u.reason())).toList());
    }

    /** API-DSH-12 기본 대시보드 지정(null이면 해제). 보이는 대시보드만 */
    @Transactional
    public DefaultDashboardResponse setDefault(String dashboardId) {
        roleChecker.require(Permission.DASHBOARD_READ);
        CurrentUser user = roleChecker.currentUser();
        Long id = null;
        if (dashboardId != null && !dashboardId.isBlank()) {
            if (!dashboardId.strip().matches("\\d{1,18}")) {
                throw new BusinessException(net.java21.data2flow.contracts.error.CommonErrorCode.INVALID_REQUEST,
                        List.of(new FieldErrorDetail("dashboardId", "INVALID", dashboardId)));
            }
            id = visible(Long.parseLong(dashboardId.strip())).id();
        }
        dashboards.upsertDefaultDashboard(user.organizationId(), user.userId(), id, clock.instant());
        return new DefaultDashboardResponse(id == null ? null : Long.toString(id));
    }

    /** API-DSH-09 위젯 데이터 */
    @Transactional(readOnly = true)
    public WidgetData widgetData(long id, String widgetId, WidgetDataRequest req) {
        roleChecker.require(Permission.DASHBOARD_READ);
        CurrentUser user = roleChecker.currentUser();
        DashboardRow d = visible(id);
        JsonNode widget = findWidget(d, widgetId);
        WidgetDataRequest r = req == null ? new WidgetDataRequest(null, null, null) : req;
        return widgets.compute(context(user.organizationId(), user.userId()), widget, json.readTree(d.variables()), r.variables(), r.timeRange(),
                json.readTree(d.timeRange()), r.resolution(), d.resolution());
    }

    /** API-DSH-09 미리 보기(대시보드 없이, 편집 중) */
    @Transactional(readOnly = true)
    public WidgetData preview(WidgetPreviewRequest req) {
        roleChecker.require(Permission.DASHBOARD_READ);
        CurrentUser user = roleChecker.currentUser();
        if (req == null || req.widget() == null || !req.widget().isObject()) {
            throw new BusinessException(BoardErrorCode.WIDGET_QUERY_INVALID, List.of(new FieldErrorDetail("widget", "REQUIRED", null)));
        }
        return widgets.compute(context(user.organizationId(), user.userId()), req.widget(), req.variableDefinitions(), req.variables(),
                req.timeRange(), json.readTree("{\"relative\":\"24h\"}"), req.resolution(), "AUTO");
    }

    /** API-DSH-13 위젯 종류 */
    public List<WidgetTypeResponse> widgetTypes() {
        roleChecker.require(Permission.DASHBOARD_READ);
        return WidgetTypes.all().stream().map(t -> new WidgetTypeResponse(t.type(), t.label(), t.optionsSchema(),
                new TargetRule(t.minTargets(), t.maxTargets(), t.kinds()))).toList();
    }

    // ------------------------------------------------------------------ 공용

    WidgetDataService.Context context(long orgId, long userId) {
        return new WidgetDataService.Context(orgId, roleChecker.spaceScope(), telemetry.findTimezone(orgId, userId));
    }

    static JsonNode findWidget(DashboardRow d, String widgetId, JsonMapper json) {
        for (JsonNode w : json.readTree(d.layout()).path("widgets").values()) {
            if (w.path("id").asString("").equals(widgetId)) {
                return w;
            }
        }
        throw new BusinessException(net.java21.data2flow.contracts.error.CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    private JsonNode findWidget(DashboardRow d, String widgetId) {
        return findWidget(d, widgetId, json);
    }

    /** 보이는 대시보드(같은 조직·ACTIVE·ORG 또는 본인 소유). 아니면 404 */
    DashboardRow visible(long id) {
        CurrentUser user = roleChecker.currentUser();
        DashboardRow d = dashboards.findActive(user.organizationId(), id)
                .orElseThrow(() -> new BusinessException(BoardErrorCode.DASHBOARD_NOT_FOUND));
        if (!"ORG".equals(d.visibility()) && d.ownerUserId() != user.userId()) {
            throw new BusinessException(BoardErrorCode.DASHBOARD_NOT_FOUND);
        }
        return d;
    }

    boolean editable(DashboardRow d) {
        CurrentUser user = roleChecker.currentUser();
        return roleChecker.has(Permission.DASHBOARD_WRITE) && (d.ownerUserId() == user.userId() || roleChecker.has(Permission.OPS_MANAGE));
    }

    private void requireEditable(DashboardRow d) {
        if (!editable(d)) {
            throw new BusinessException(net.java21.data2flow.contracts.error.CommonErrorCode.PERMISSION_DENIED);
        }
    }

    private DashboardResponse response(DashboardRow d) {
        return new DashboardResponse(Long.toString(d.id()), d.name(), d.description(), d.visibility(), Long.toString(d.ownerUserId()),
                json.readTree(d.layout()), json.readTree(d.variables()), json.readTree(d.timeRange()), d.resolution(), d.refresh(),
                d.templateSource(), editable(d), d.version(), d.updatedBy() == null ? null : Long.toString(d.updatedBy()), d.updatedAt());
    }

    private Set<Long> favoriteIds(CurrentUser user) {
        Set<Long> ids = new HashSet<>();
        for (JsonNode f : json.readTree(dashboards.findFavoritesJson(user.organizationId(), user.userId())).values()) {
            String id = f.path("id").asString("");
            if ("DASHBOARD".equals(f.path("type").asString("")) && id.matches("\\d{1,18}")) {
                ids.add(Long.parseLong(id));
            }
        }
        return ids;
    }

    /** 본문 검사(생성은 before=null). 필드가 없으면 저장된 값(수정) 또는 기본값(생성) */
    NewDashboard parse(JsonNode body, DashboardRow before) {
        if (body == null || !body.isObject()) {
            throw new BusinessException(net.java21.data2flow.contracts.error.CommonErrorCode.INVALID_REQUEST);
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        String name = text(body, "name", before == null ? null : before.name());
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            errors.add(new FieldErrorDetail("name", "SIZE", "1~100"));
        }
        String description = text(body, "description", before == null ? null : before.description());
        if (description != null && description.length() > 500) {
            errors.add(new FieldErrorDetail("description", "SIZE", "0~500"));
        }
        String visibility = choice(body, "visibility", before == null ? "PRIVATE" : before.visibility(), VISIBILITIES, true, errors);
        String resolution = choice(body, "resolution", before == null ? "AUTO" : before.resolution(), RESOLUTIONS, false, errors);
        String refresh = choice(body, "refresh", before == null ? "LIVE" : before.refresh(), REFRESHES, false, errors);
        if (!errors.isEmpty()) {
            throw new BusinessException(net.java21.data2flow.contracts.error.CommonErrorCode.INVALID_REQUEST, errors);
        }
        JsonNode variables = body.has("variables") ? body.get("variables")
                : before == null ? json.readTree("[]") : json.readTree(before.variables());
        if (variables == null || variables.isNull()) {
            variables = json.readTree("[]");
        }
        Set<String> names = DashboardLayoutValidator.validateVariables(variables);
        JsonNode layout = body.has("layout") ? body.get("layout")
                : before == null ? json.readTree("{\"widgets\":[]}") : json.readTree(before.layout());
        if (layout != null && layout.isArray()) {
            // API-DSH-07 요청 표기 "layout[]": 위젯 배열만 보내도 받는다
            ObjectNode wrapped = json.createObjectNode();
            wrapped.set("widgets", layout);
            layout = wrapped;
        }
        DashboardLayoutValidator.validate(layout, names);
        JsonNode timeRange = body.has("timeRange") ? body.get("timeRange")
                : before == null ? json.readTree("{\"relative\":\"24h\"}") : json.readTree(before.timeRange());
        TimeRanges.validate(timeRange, clock.instant());
        return new NewDashboard(name.strip(), description, visibility, json.writeValueAsString(layout), json.writeValueAsString(variables),
                json.writeValueAsString(timeRange), resolution, refresh, before == null ? text(body, "templateSource", null) : before.templateSource());
    }

    private static String text(JsonNode body, String field, String fallback) {
        if (!body.has(field)) {
            return fallback;
        }
        JsonNode n = body.get(field);
        return n.isNull() ? null : n.asString("");
    }

    private static String choice(JsonNode body, String field, String fallback, Set<String> allowed, boolean upper, List<FieldErrorDetail> errors) {
        if (!body.has(field) || body.get(field).isNull()) {
            return fallback;
        }
        String raw = body.get(field).asString("");
        String value = upper || "AUTO".equalsIgnoreCase(raw) || "RAW".equalsIgnoreCase(raw) || "LIVE".equalsIgnoreCase(raw)
                || "OFF".equalsIgnoreCase(raw) ? raw.toUpperCase(Locale.ROOT) : raw;
        if (!allowed.contains(value)) {
            errors.add(new FieldErrorDetail(field, "INVALID", String.join("|", allowed)));
            return fallback;
        }
        return value;
    }
}
