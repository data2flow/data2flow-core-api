package net.java21.data2flow.core.dashboard.service;

import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.dashboard.domain.DashboardErrorCode;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.ItemRef;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.PreferencesResponse;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.RecentRequest;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.RecentResponse;
import net.java21.data2flow.core.dashboard.repository.PreferencesRepository;
import net.java21.data2flow.core.dashboard.repository.PreferencesRepository.OrgDefaults;
import net.java21.data2flow.core.dashboard.repository.PreferencesRepository.PrefsRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 내 화면 설정(API-DSH-12): 테마(DSH-07.02), 언어, 시간대(DSH-07.04), 첫 화면, 즐겨찾기·최근 본 항목(DSH-07.05), 투어, 온도 단위.
 *
 * <ul>
 *   <li>본인 설정이라 역할 권한은 묻지 않는다(로그인만). 저장은 baseVersion 낙관적 잠금(409 VERSION_CONFLICT), 온 키만 바꾼다</li>
 *   <li>시간대가 없으면 조직 기본값(org_settings.timezone)을 쓴다(DSH-07.04). 저장·API 시각은 UTC</li>
 *   <li>즐겨찾기는 최대 {@value #MAX_FAVORITES}개(SPACE·DEVICE·DASHBOARD). 최근 본 항목은 최대 {@value #MAX_RECENT}개, 다시 보면 맨 앞으로</li>
 *   <li>조회할 때 권한이 사라진(범위 밖·삭제된) 공간·기기는 목록에서 뺀다. 대시보드·플로우·분석은 그 기능(M3·M5·M6)이 생기기 전까지
 *       확인할 원천이 없어 이름 없이 그대로 둔다</li>
 * </ul>
 */
@Service
public class PreferencesService {

    static final int MAX_FAVORITES = 50;
    static final int MAX_RECENT = 20;
    static final int MAX_TOURS = 100;
    static final Set<String> THEMES = Set.of("LIGHT", "DARK", "SYSTEM");
    static final Set<String> LOCALES = Set.of("ko", "en", "ja", "zh");
    static final Set<String> HOMES = Set.of("HOME", "DASHBOARD");
    static final Set<String> UNITS = Set.of("C", "F");
    static final Set<String> FAVORITE_TYPES = Set.of("SPACE", "DEVICE", "DASHBOARD");
    static final Set<String> RECENT_TYPES = Set.of("DASHBOARD", "SPACE", "DEVICE", "FLOW", "ANALYSIS");

    private final RoleChecker roleChecker;
    private final PreferencesRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    public PreferencesService(RoleChecker roleChecker, PreferencesRepository repository, JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    /** GET /core/accounts/me/preferences */
    @Transactional(readOnly = true)
    public PreferencesResponse get() {
        CurrentUser user = roleChecker.currentUser();
        PrefsRow row = repository.findPrefs(user.organizationId(), user.userId()).orElse(PrefsRow.DEFAULT);
        return response(user, row);
    }

    /** PUT /core/accounts/me/preferences — 온 키만 바꾼다 */
    @Transactional
    public PreferencesResponse update(JsonNode body) {
        CurrentUser user = roleChecker.currentUser();
        int baseVersion = (int) VersionCheck.baseVersion(body);
        var stored = repository.findPrefs(user.organizationId(), user.userId());
        PrefsRow current = stored.orElse(PrefsRow.DEFAULT);
        VersionCheck.require(baseVersion, current.version());
        List<FieldErrorDetail> errors = new ArrayList<>();
        String theme = enumField(body, "theme", current.theme(), THEMES, false, errors, true);
        String locale = enumField(body, "locale", current.locale(), LOCALES, true, errors, false);
        String home = enumField(body, "home", current.home(), HOMES, false, errors, true);
        String unit = enumField(body, "temperatureUnit", current.temperatureUnit(), UNITS, true, errors, true);
        String timeZone = timeZone(body, current.timeZone(), errors);
        Long dashboard = idField(body, "defaultDashboardId", current.defaultDashboardId(), errors);
        String favorites = body.has("favorites") ? favorites(body.get("favorites"), errors) : current.favoritesJson();
        List<String> tours = body.has("toursDismissed") ? tours(body.get("toursDismissed"), errors) : current.toursDismissed();
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        Instant now = clock.instant();
        if (stored.isEmpty()) {
            PrefsRow created = new PrefsRow(theme, locale, timeZone, home, dashboard, favorites, current.recentJson(), tours, unit, 1);
            VersionCheck.requireUpdated(repository.insertPrefs(user.organizationId(), user.userId(), created, now));
        } else {
            PrefsRow changed = new PrefsRow(theme, locale, timeZone, home, dashboard, favorites, current.recentJson(), tours, unit,
                    current.version());
            VersionCheck.requireUpdated(repository.updatePrefs(user.organizationId(), user.userId(), baseVersion, changed, now));
        }
        return response(user, repository.findPrefs(user.organizationId(), user.userId()).orElseThrow());
    }

    /** POST /core/accounts/me/recent — 맨 앞에 넣고 같은 항목은 지우고 20개까지 */
    @Transactional
    public RecentResponse recordRecent(RecentRequest request) {
        CurrentUser user = roleChecker.currentUser();
        String type = request == null || request.type() == null ? "" : request.type().toUpperCase(Locale.ROOT);
        String id = request == null ? null : request.id();
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (!RECENT_TYPES.contains(type)) {
            errors.add(new FieldErrorDetail("type", "INVALID", String.join("|", RECENT_TYPES)));
        }
        if (id == null || !id.matches("\\d{1,18}")) {
            errors.add(new FieldErrorDetail("id", "INVALID", null));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        SpaceScope scope = roleChecker.spaceScope();
        long target = Long.parseLong(id);
        if ("SPACE".equals(type) && repository.findSpaceNames(user.organizationId(), List.of(target), scope).isEmpty()) {
            throw new BusinessException(DashboardErrorCode.SPACE_NOT_FOUND);
        }
        if ("DEVICE".equals(type) && repository.findDeviceNames(user.organizationId(), List.of(target), scope).isEmpty()) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        Instant now = clock.instant();
        PrefsRow row = repository.lockPrefs(user.organizationId(), user.userId()).orElse(null);
        if (row == null) {
            repository.insertPrefs(user.organizationId(), user.userId(), PrefsRow.DEFAULT, now);
            row = repository.lockPrefs(user.organizationId(), user.userId()).orElseThrow();
        }
        ArrayNode next = json.createArrayNode();
        ObjectNode first = next.addObject();
        first.put("type", type);
        first.put("id", id);
        first.put("at", now.toString());
        for (JsonNode item : json.readTree(row.recentJson()).values()) {
            if (next.size() >= MAX_RECENT) {
                break;
            }
            if (!(type.equals(item.path("type").asString("")) && id.equals(item.path("id").asString("")))) {
                next.add(item);
            }
        }
        repository.updateRecent(user.organizationId(), user.userId(), json.writeValueAsString(next), now);
        return new RecentResponse(visible(user, scope, json.writeValueAsString(next), true));
    }

    private PreferencesResponse response(CurrentUser user, PrefsRow row) {
        SpaceScope scope = roleChecker.spaceScope();
        OrgDefaults org = repository.findOrgDefaults(user.organizationId());
        String effectiveLocale = row.locale() != null ? row.locale()
                : repository.findUserLocale(user.organizationId(), user.userId()).orElse(org.locale());
        String effectiveUnit = row.temperatureUnit() != null ? row.temperatureUnit() : "IMPERIAL".equals(org.unitSystem()) ? "F" : "C";
        return new PreferencesResponse(row.theme(), row.locale(), effectiveLocale, row.timeZone(),
                row.timeZone() != null ? row.timeZone() : org.timeZone(), org.timeZone(), row.home(),
                row.defaultDashboardId() == null ? null : Long.toString(row.defaultDashboardId()),
                visible(user, scope, row.favoritesJson(), false), visible(user, scope, row.recentJson(), true),
                row.toursDismissed(), row.temperatureUnit(), effectiveUnit, row.version());
    }

    /** 저장된 항목 중 보이는 것만 이름을 붙여 돌려준다(공간·기기는 범위 밖·삭제면 뺀다) */
    private List<ItemRef> visible(CurrentUser user, SpaceScope scope, String stored, boolean withTime) {
        List<JsonNode> items = new ArrayList<>();
        Set<Long> spaces = new HashSet<>();
        Set<Long> devices = new HashSet<>();
        for (JsonNode item : json.readTree(stored).values()) {
            String id = item.path("id").asString("");
            if (!id.matches("\\d{1,18}")) {
                continue;
            }
            items.add(item);
            String type = item.path("type").asString("");
            if ("SPACE".equals(type)) {
                spaces.add(Long.parseLong(id));
            } else if ("DEVICE".equals(type)) {
                devices.add(Long.parseLong(id));
            }
        }
        Map<Long, String> spaceNames = repository.findSpaceNames(user.organizationId(), spaces, scope);
        Map<Long, String> deviceNames = repository.findDeviceNames(user.organizationId(), devices, scope);
        List<ItemRef> result = new ArrayList<>();
        for (JsonNode item : items) {
            String type = item.path("type").asString("");
            long id = Long.parseLong(item.path("id").asString());
            String name = null;
            if ("SPACE".equals(type) || "DEVICE".equals(type)) {
                name = ("SPACE".equals(type) ? spaceNames : deviceNames).get(id);
                if (name == null) {
                    continue;
                }
            }
            Instant at = null;
            if (withTime && item.hasNonNull("at")) {
                at = Instant.parse(item.get("at").asString());
            }
            result.add(new ItemRef(type, Long.toString(id), name, at));
        }
        return result;
    }

    private String favorites(JsonNode node, List<FieldErrorDetail> errors) {
        ArrayNode out = json.createArrayNode();
        if (node == null || node.isNull()) {
            return "[]";
        }
        if (!node.isArray()) {
            errors.add(new FieldErrorDetail("favorites", "TYPE", null));
            return "[]";
        }
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode item : node.values()) {
            String type = item.path("type").asString("").toUpperCase(Locale.ROOT);
            JsonNode idNode = item.get("id");
            String id = idNode == null || idNode.isNull() ? "" : idNode.asString("");
            if (!FAVORITE_TYPES.contains(type) || !id.matches("\\d{1,18}")) {
                errors.add(new FieldErrorDetail("favorites[" + index + "]", "INVALID", null));
            } else if (seen.add(type + ":" + id)) {
                ObjectNode o = out.addObject();
                o.put("type", type);
                o.put("id", id);
            }
            index++;
        }
        if (out.size() > MAX_FAVORITES) {
            errors.add(new FieldErrorDetail("favorites", "SIZE", "0~" + MAX_FAVORITES));
        }
        return json.writeValueAsString(out);
    }

    private static List<String> tours(JsonNode node, List<FieldErrorDetail> errors) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            errors.add(new FieldErrorDetail("toursDismissed", "TYPE", null));
            return List.of();
        }
        Set<String> tours = new LinkedHashSet<>();
        for (JsonNode item : node.values()) {
            String t = item.asString("");
            if (t.isBlank() || t.length() > 64) {
                errors.add(new FieldErrorDetail("toursDismissed", "INVALID", t));
            } else {
                tours.add(t);
            }
        }
        if (tours.size() > MAX_TOURS) {
            errors.add(new FieldErrorDetail("toursDismissed", "SIZE", "0~" + MAX_TOURS));
        }
        return List.copyOf(tours);
    }

    private static String enumField(JsonNode body, String field, String current, Set<String> allowed, boolean nullable,
                                    List<FieldErrorDetail> errors, boolean upper) {
        if (!body.has(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node.isNull()) {
            if (!nullable) {
                errors.add(new FieldErrorDetail(field, "NOT_NULL", null));
            }
            return nullable ? null : current;
        }
        String raw = node.asString("");
        String value = upper ? raw.toUpperCase(Locale.ROOT) : raw.toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            errors.add(new FieldErrorDetail(field, "INVALID", raw));
            return current;
        }
        return value;
    }

    private static String timeZone(JsonNode body, String current, List<FieldErrorDetail> errors) {
        if (!body.has("timeZone")) {
            return current;
        }
        JsonNode node = body.get("timeZone");
        if (node.isNull() || node.asString("").isBlank()) {
            return null;
        }
        String tz = node.asString("");
        if (!ZoneId.getAvailableZoneIds().contains(tz) && !"UTC".equals(tz)) {
            errors.add(new FieldErrorDetail("timeZone", "INVALID", tz));
            return current;
        }
        return tz;
    }

    private static Long idField(JsonNode body, String field, Long current, List<FieldErrorDetail> errors) {
        if (!body.has(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node.isNull()) {
            return null;
        }
        String raw = node.asString("");
        if (!raw.matches("\\d{1,18}")) {
            errors.add(new FieldErrorDetail(field, "INVALID", raw));
            return current;
        }
        return Long.parseLong(raw);
    }
}
