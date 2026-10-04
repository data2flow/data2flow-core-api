package net.java21.data2flow.core.external.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.external.KmaGrid;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.calendar.domain.CalendarModels;
import net.java21.data2flow.core.calendar.domain.ICalParser;
import net.java21.data2flow.core.calendar.domain.ICalParser.ICalEvent;
import net.java21.data2flow.core.external.domain.ExternalErrorCode;
import net.java21.data2flow.core.external.dto.ExternalDtos.ContextSourceView;
import net.java21.data2flow.core.external.dto.ExternalDtos.IcalUploadResponse;
import net.java21.data2flow.core.external.dto.ExternalDtos.LastSync;
import net.java21.data2flow.core.external.dto.ExternalDtos.ProviderView;
import net.java21.data2flow.core.external.dto.ExternalDtos.RefreshResponse;
import net.java21.data2flow.core.external.dto.ExternalDtos.SiteContextResponse;
import net.java21.data2flow.core.external.dto.ExternalDtos.StationView;
import net.java21.data2flow.core.external.dto.ExternalDtos.UsageDay;
import net.java21.data2flow.core.external.dto.ExternalDtos.UsageRecorded;
import net.java21.data2flow.core.external.dto.ExternalDtos.UsageToday;
import net.java21.data2flow.core.external.provider.AirQualityProvider;
import net.java21.data2flow.core.external.provider.ProviderDescriptor;
import net.java21.data2flow.core.external.provider.ProviderException;
import net.java21.data2flow.core.external.provider.WeatherProvider;
import net.java21.data2flow.core.external.repository.ContextSourceRepository;
import net.java21.data2flow.core.external.repository.ContextSourceRepository.ContextSource;
import net.java21.data2flow.core.external.repository.ContextSourceRepository.UsageRow;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.source.domain.SourceModels.SecretMeta;
import net.java21.data2flow.core.source.repository.DataSourceRepository;
import net.java21.data2flow.core.source.repository.SourceHealthRepository;
import net.java21.data2flow.core.source.service.SourceSecrets;
import net.java21.data2flow.core.source.service.SourceStateService;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceType;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import net.java21.data2flow.core.space.service.SpaceSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 외부 맥락 소스(DSC-06.01~06.05, UC-DSC-09, UI-DSC-04). 사이트마다 유형별 소스 1개(BR-DSC-15): 기상청 날씨(KMA_WEATHER), 대기질(AIRKOREA),
 * 공휴일(HOLIDAY), 학사일정(ICAL). 기상청·에어코리아 정기 수집은 ingress(API-DSC-50으로 설정·API_KEY를 받음)가 하고, 공휴일·iCal은 core가
 * 직접 갱신한다({@link ContextSyncService}).
 * <ul>
 *   <li>카드 조회·켜기/설정: API-DSC-44·45(제안, SRC_READ·SRC_ADMIN)</li>
 *   <li>API-DSC-40 호출량(최근 N일 ≤ 90), API-DSC-41 지금 갱신, API-DSC-42 가까운 측정소 5곳, API-DSC-43 iCal 파일 업로드(≤2MB)</li>
 *   <li>내부 API-DSC-78(제안): ingress가 공공 API 호출량을 알려 주고 한도 판정을 받는다</li>
 * </ul>
 */
@Service
public class ContextSourceService {

    public static final List<String> TYPES = List.of("KMA_WEATHER", "AIRKOREA", "HOLIDAY", "ICAL");
    static final Map<String, String> DECODERS = Map.of("KMA_WEATHER", "builtin-kma", "AIRKOREA", "builtin-airkorea",
            "HOLIDAY", "builtin-holiday", "ICAL", "builtin-ical");
    static final Map<String, String> NAMES = Map.of("KMA_WEATHER", "기상청 날씨", "AIRKOREA", "대기질(에어코리아)",
            "HOLIDAY", "공휴일", "ICAL", "학사일정(iCal)");
    static final Set<String> KMA_ITEMS = Set.of("T1H", "REH", "RN1", "WSD", "PTY", "VEC", "UUU", "VVV");
    static final Set<String> AIR_ITEMS = Set.of("PM10", "PM25", "O3", "NO2", "CO", "SO2");
    static final Set<String> CONFIG_KEYS = Set.of("enabled", "apiKey", "nx", "ny", "items", "forecast", "stationName", "countryCode", "url",
            "fileObjectKey", "typeMapping", "refreshHours", "dailyQuota", "unitCost");
    static final String API_KEY = "API_KEY";
    static final String FILE_KEY_PREFIX = "db:ical_files/";
    static final int MAX_ICAL_BYTES = 2 * 1024 * 1024;

    private final RoleChecker roleChecker;
    private final SpaceRepository spaces;
    private final ContextSourceRepository repository;
    private final DataSourceRepository dataSources;
    private final SourceSecrets secrets;
    private final SourceStateService states;
    private final SourceHealthRepository health;
    private final ContextSyncService syncs;
    private final ApiUsageService usage;
    private final ExternalProviders providers;
    private final ExternalProperties properties;
    private final DeploymentOrganization deployment;
    private final Audits audits;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public ContextSourceService(RoleChecker roleChecker, SpaceRepository spaces, ContextSourceRepository repository,
                                DataSourceRepository dataSources, SourceSecrets secrets, SourceStateService states, SourceHealthRepository health,
                                ContextSyncService syncs, ApiUsageService usage, ExternalProviders providers, ExternalProperties properties,
                                DeploymentOrganization deployment, Audits audits, JsonMapper json, PlatformTransactionManager txManager,
                                Clock clock) {
        this.roleChecker = roleChecker;
        this.spaces = spaces;
        this.repository = repository;
        this.dataSources = dataSources;
        this.secrets = secrets;
        this.states = states;
        this.health = health;
        this.syncs = syncs;
        this.usage = usage;
        this.providers = providers;
        this.properties = properties;
        this.deployment = deployment;
        this.audits = audits;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 사이트 카드

    /** API-DSC-44(제안) 사이트의 외부 맥락 카드 4개 */
    public SiteContextResponse site(long siteId) {
        roleChecker.require(Permission.SRC_READ);
        long org = roleChecker.currentUser().organizationId();
        return readTx.execute(status -> siteResponse(org, site(org, siteId)));
    }

    private SiteContextResponse siteResponse(long org, Space site) {
        Map<String, ContextSource> byType = new HashMap<>();
        repository.listBySite(org, site.id()).forEach(s -> byType.put(s.type(), s));
        List<ContextSourceView> views = TYPES.stream().map(t -> view(t, byType.get(t))).toList();
        boolean located = site.latitude() != null && site.longitude() != null;
        return new SiteContextResponse(Long.toString(site.id()), site.name(), site.latitude(), site.longitude(), !located, site.kmaNx(),
                site.kmaNy(), views);
    }

    /**
     * API-DSC-45(제안) 카드 켜기·끄기·설정. 없으면 만들고(코드 {@code ctx-<유형>-<사이트>}), 켜면 ACTIVE·끄면 PAUSED.
     * 공휴일·iCal은 켜는 즉시 한 번 동기화한다(UC-DSC-09 3·4단계).
     */
    public ContextSourceView put(long siteId, String rawType, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        String type = normalizeType(rawType);
        if (body == null || !body.isObject() || !body.path("enabled").isBoolean()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("enabled", "NotNull", null)));
        }
        ContextSource saved = tx.execute(status -> save(org, user, site(org, siteId), type, body));
        if ("ACTIVE".equals(saved.lifecycle()) && (type.equals("HOLIDAY") || type.equals("ICAL"))) {
            syncs.sync(saved);
        }
        return readTx.execute(status -> view(type, repository.findById(org, saved.id()).orElseThrow()));
    }

    private ContextSource save(long org, CurrentUser user, Space site, String type, JsonNode body) {
        ContextSource existing = repository.listBySite(org, site.id()).stream().filter(s -> s.type().equals(type)).findFirst().orElse(null);
        boolean enabled = body.get("enabled").asBoolean();
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (String key : body.propertyNames()) {
            if (!CONFIG_KEYS.contains(key)) {
                errors.add(new FieldErrorDetail(key, "UNKNOWN_FIELD", null));
            }
        }
        ObjectNode conn = existing == null ? json.createObjectNode() : ((ObjectNode) existing.connection()).deepCopy();
        String apiKey = body.path("apiKey").isString() ? body.get("apiKey").asString().strip() : "";
        boolean keyStored = existing != null && dataSources.findSecretMeta(org, existing.id()).stream().map(SecretMeta::kind)
                .anyMatch(API_KEY::equals);
        boolean located = site.latitude() != null && site.longitude() != null;
        switch (type) {
            case "KMA_WEATHER" -> {
                if (enabled && !located) {
                    throw new BusinessException(ExternalErrorCode.SITE_LOCATION_REQUIRED);
                }
                KmaGrid.Point auto = located ? grid(site) : null;
                int nx = intField(body, "nx", conn.path("nx").asInt(auto == null ? 0 : auto.nx()), 1, KmaGrid.Point.MAX_NX, errors);
                int ny = intField(body, "ny", conn.path("ny").asInt(auto == null ? 0 : auto.ny()), 1, KmaGrid.Point.MAX_NY, errors);
                if (nx > 0 && ny > 0) {
                    conn.put("nx", nx).put("ny", ny);
                }
                conn.put("gridAuto", auto != null && auto.nx() == nx && auto.ny() == ny);
                items(body, conn, KMA_ITEMS, List.of("T1H", "REH", "RN1", "WSD"), errors);
                conn.put("forecast", body.has("forecast") ? body.path("forecast").asBoolean(true) : conn.path("forecast").asBoolean(true));
            }
            case "AIRKOREA" -> {
                if (enabled && !located) {
                    throw new BusinessException(ExternalErrorCode.SITE_LOCATION_REQUIRED);
                }
                String station = body.has("stationName") ? body.path("stationName").asString("").strip() : conn.path("stationName").asString("");
                if (station.isBlank() && located) {
                    station = providers.airQuality().nearestStations(site.latitude().doubleValue(), site.longitude().doubleValue(), 1)
                            .stream().map(AirQualityProvider.Station::stationName).findFirst().orElse("");
                }
                if (station.length() > 50) {
                    errors.add(new FieldErrorDetail("stationName", "Size", "≤50"));
                }
                conn.put("stationName", station);
                conn.put("stationAuto", !body.has("stationName"));
                items(body, conn, AIR_ITEMS, List.of("PM10", "PM25", "O3"), errors);
            }
            case "HOLIDAY" -> {
                String country = body.has("countryCode") ? body.path("countryCode").asString("").toUpperCase(Locale.ROOT)
                        : conn.path("countryCode").asString("KR");
                if (!"KR".equals(country)) {
                    errors.add(new FieldErrorDetail("countryCode", "UNSUPPORTED", "KR"));
                }
                conn.put("countryCode", "KR");
            }
            case "ICAL" -> icalConfig(org, body, conn, errors);
            default -> throw new IllegalStateException(type);
        }
        if (body.has("dailyQuota")) {
            if (body.get("dailyQuota").isNull()) {
                conn.remove("dailyQuota");
            } else {
                conn.put("dailyQuota", intField(body, "dailyQuota", 0, 1, 1_000_000, errors));
            }
        }
        if (body.has("unitCost")) {
            JsonNode c = body.get("unitCost");
            if (c.isNull()) {
                conn.remove("unitCost");
            } else if (!c.isNumber() || c.decimalValue().signum() < 0 || c.decimalValue().compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
                errors.add(new FieldErrorDetail("unitCost", "Range", "0~1000000"));
            } else {
                conn.put("unitCost", c.decimalValue());
            }
        }
        if (enabled && (type.equals("KMA_WEATHER") || type.equals("AIRKOREA")) && apiKey.isEmpty() && !keyStored) {
            errors.add(new FieldErrorDetail("apiKey", "NotBlank", null));
        }
        if (!apiKey.isEmpty() && (type.equals("HOLIDAY") || type.equals("ICAL"))) {
            errors.add(new FieldErrorDetail("apiKey", "NOT_FOR_TYPE", null));
        }
        if (apiKey.length() > 500) {
            errors.add(new FieldErrorDetail("apiKey", "Size", "≤500"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ExternalErrorCode.SOURCE_CONFIG_INVALID, errors, errors.getFirst().field());
        }
        Instant now = clock.instant();
        String lifecycle = enabled ? "ACTIVE" : "PAUSED";
        long id;
        boolean activated;
        if (existing == null) {
            String code = code(org, type, site.id());
            id = repository.insert(org, code, NAMES.get(type) + " · " + site.name(), type, lifecycle, conn, DECODERS.get(type), site.id(),
                    user.userId(), now);
            activated = enabled;
            audits.record(audits.event(org, "SOURCE_CREATED").actor(user).target("SOURCE", Long.toString(id))
                    .detail("type", type).detail("siteId", site.id()).detail("lifecycle", lifecycle));
        } else {
            id = existing.id();
            repository.update(org, id, lifecycle, conn, user.userId(), now);
            activated = enabled && !"ACTIVE".equals(existing.lifecycle());
            audits.record(audits.event(org, "SOURCE_UPDATED").actor(user).target("SOURCE", Long.toString(id))
                    .detail("type", type).detail("lifecycle", lifecycle).detail("fields", new TreeSet<>(body.propertyNames())));
        }
        if (!apiKey.isEmpty()) {
            Map<String, String> fp = secrets.store(org, id, Map.of(API_KEY, Secret.of(apiKey)), now);
            audits.record(audits.event(org, "SOURCE_SECRET_CHANGED").actor(user).target("SOURCE", Long.toString(id))
                    .detail("kinds", fp.keySet()));
        }
        if (activated) {
            states.activated(org, id);
        } else if (!enabled) {
            health.lockState(org, id);
            health.updateConnectionState(org, id, "DISABLED", null, now, now);
        }
        ContextSource saved = repository.findById(org, id).orElseThrow();
        states.configChanged(org, id, saved.version());
        if (enabled && (type.equals("HOLIDAY") || type.equals("ICAL"))) {
            repository.scheduleNow(org, id, now);
        }
        return saved;
    }

    private void icalConfig(long org, JsonNode body, ObjectNode conn, List<FieldErrorDetail> errors) {
        if (body.has("url")) {
            String url = body.path("url").asString("").strip();
            if (url.isEmpty()) {
                conn.remove("url");
            } else if (!validIcalUrl(url, properties.icalAllowHttp()) || url.length() > 2000) {
                errors.add(new FieldErrorDetail("url", "INVALID", "https:// 또는 webcal://"));
            } else {
                conn.put("url", url);
                conn.remove("fileObjectKey");
            }
        }
        if (body.has("fileObjectKey")) {
            String key = body.path("fileObjectKey").asString("").strip();
            if (key.isEmpty()) {
                conn.remove("fileObjectKey");
            } else if (!key.startsWith(FILE_KEY_PREFIX) || repository.findIcalFile(org, icalFileId(key)).isEmpty()) {
                errors.add(new FieldErrorDetail("fileObjectKey", "NOT_FOUND", null));
            } else {
                conn.put("fileObjectKey", key);
                conn.remove("url");
            }
        }
        if (body.path("enabled").asBoolean() && conn.path("url").asString("").isBlank() && conn.path("fileObjectKey").asString("").isBlank()) {
            errors.add(new FieldErrorDetail("url", "NotBlank", "url 또는 fileObjectKey"));
        }
        if (body.has("typeMapping")) {
            JsonNode m = body.get("typeMapping");
            ObjectNode mapping = json.createObjectNode();
            if (!m.isObject() || m.size() > 50) {
                errors.add(new FieldErrorDetail("typeMapping", "Type", "≤50"));
            } else {
                for (Map.Entry<String, JsonNode> e : m.properties()) {
                    String t = e.getValue().asString("").toUpperCase(Locale.ROOT);
                    if (!CalendarModels.TYPES.contains(t) || e.getKey().isBlank() || e.getKey().length() > 100) {
                        errors.add(new FieldErrorDetail("typeMapping." + e.getKey(), "INVALID", null));
                    } else {
                        mapping.put(e.getKey().strip(), t);
                    }
                }
            }
            conn.set("typeMapping", mapping);
        }
        int hours = intField(body, "refreshHours", conn.path("refreshHours").asInt(6), 1, 168, errors);
        conn.put("refreshHours", hours);
    }

    // ---------------------------------------------------------------- API-DSC-40~43

    /** API-DSC-40 최근 days일(오늘 포함, 1~90, 기본 30) 하루 호출·실패·한도. 기록 없는 날은 0 */
    public List<UsageDay> apiUsage(long sourceId, Integer days) {
        roleChecker.require(Permission.SRC_READ);
        long org = roleChecker.currentUser().organizationId();
        int n = days == null ? 30 : days;
        if (n < 1 || n > 90) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("days", "Range", "1~90")));
        }
        return readTx.execute(status -> {
            ContextSource s = source(org, sourceId);
            Integer quota = usage.quotaOf(s);
            BigDecimal unitCost = s.connection().path("unitCost").isNumber() ? s.connection().get("unitCost").decimalValue() : null;
            LocalDate today = usage.today();
            LocalDate from = today.minusDays(n - 1L);
            Map<LocalDate, UsageRow> rows = new HashMap<>();
            repository.listUsage(org, sourceId, from, today).forEach(r -> rows.put(r.day(), r));
            List<UsageDay> out = new ArrayList<>();
            for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
                UsageRow r = rows.getOrDefault(d, new UsageRow(d, 0, 0, quota, null));
                Integer q = r.quota() == null ? quota : r.quota();
                boolean warning = q != null && q > 0 && r.calls() * 10L >= q * 8L;
                out.add(new UsageDay(d, r.calls(), r.failures(), q, warning, q != null && q > 0 && r.calls() >= q,
                        unitCost == null ? null : unitCost.multiply(BigDecimal.valueOf(r.calls()))));
            }
            return out;
        });
    }

    /**
     * API-DSC-41 지금 갱신. 공휴일·iCal은 바로 동기화한 결과, 기상청·에어코리아는 파사드로 한 번 확인 호출(호출량에 셈)하고 ingress가 설정을
     * 다시 읽어 바로 수집하도록 설정 변경을 알린다(status=REQUESTED, 확인 실패면 FAILED). 일일 한도에 닿았으면 429
     */
    public RefreshResponse refreshNow(long sourceId) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        ContextSource s = readTx.execute(status -> source(org, sourceId));
        if (!"ACTIVE".equals(s.lifecycle())) {
            throw new BusinessException(ExternalErrorCode.SOURCE_CONFIG_INVALID, List.of(new FieldErrorDetail("lifecycle", "NOT_ACTIVE", null)),
                    "lifecycle");
        }
        usage.requireAllowed(s);
        String jobId = UUID.randomUUID().toString();
        audits.record(audits.event(org, "SOURCE_REFRESH_REQUESTED").actor(user).target("SOURCE", Long.toString(sourceId))
                .detail("jobId", jobId));
        if (s.type().equals("HOLIDAY") || s.type().equals("ICAL")) {
            ContextSyncService.Outcome o = syncs.sync(s);
            return new RefreshResponse(jobId, Long.toString(sourceId), o.ok() ? "SUCCEEDED" : "FAILED", o.added(), o.updated(), o.removed(),
                    o.error());
        }
        String error = probe(s);
        tx.executeWithoutResult(status -> states.configChanged(org, sourceId, s.version()));
        return new RefreshResponse(jobId, Long.toString(sourceId), error == null ? "REQUESTED" : "FAILED", 0, 0, 0, error);
    }

    /** 파사드로 한 번 확인(실제 어댑터면 호출량에 셈). 실패 사유 또는 null */
    private String probe(ContextSource s) {
        String key = apiKey(s);
        Instant now = clock.instant();
        boolean real = false;
        try {
            if (s.type().equals("KMA_WEATHER")) {
                WeatherProvider p = providers.weather(key);
                real = !p.descriptor().simulated();
                p.nowcast(new KmaGrid.Point(s.connection().path("nx").asInt(), s.connection().path("ny").asInt()), now);
            } else {
                AirQualityProvider p = providers.airQuality(key);
                real = !p.descriptor().simulated();
                p.latest(s.connection().path("stationName").asString(""));
            }
            if (real) {
                usage.record(s, 1, 0);
            }
            return null;
        } catch (ProviderException ex) {
            if (real) {
                usage.record(s, 1, 1);
            }
            return ex.kind().name() + ": " + ex.getMessage();
        }
    }

    /** API-DSC-42 가까운 측정소 5곳(가까운 순) */
    public List<StationView> stations(Double lat, Double lng) {
        roleChecker.require(Permission.SRC_ADMIN);
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (lat == null || lat < -90 || lat > 90) {
            errors.add(new FieldErrorDetail("lat", "Range", "-90~90"));
        }
        if (lng == null || lng < -180 || lng > 180) {
            errors.add(new FieldErrorDetail("lng", "Range", "-180~180"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        try {
            return providers.airQuality().nearestStations(lat, lng, 5).stream()
                    .map(st -> new StationView(st.stationName(), st.address(), st.lat(), st.lng(), st.distanceKm(), st.items())).toList();
        } catch (ProviderException ex) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** API-DSC-43 iCal 파일(.ics ≤2MB) 저장 → 유형 매핑 화면용 카테고리 */
    public IcalUploadResponse uploadIcal(MultipartFile file) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        if (file == null || file.isEmpty()) {
            throw fileInvalid("NotNull");
        }
        if (file.getSize() > MAX_ICAL_BYTES) {
            throw fileInvalid("Size");
        }
        String name = file.getOriginalFilename() == null ? "calendar.ics" : file.getOriginalFilename();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".ics")) {
            throw fileInvalid("EXTENSION");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException ex) {
            throw fileInvalid("UNREADABLE");
        }
        List<ICalEvent> events;
        try {
            events = ICalParser.parse(new String(data, StandardCharsets.UTF_8), SpaceSupport.DEFAULT_ZONE);
        } catch (ICalParser.ICalException ex) {
            throw fileInvalid("ICAL_INVALID");
        }
        TreeSet<String> categories = new TreeSet<>();
        events.forEach(e -> categories.addAll(e.categories()));
        String fileName = name.length() > 200 ? name.substring(name.length() - 200) : name;
        long id = tx.execute(status -> repository.insertIcalFile(org, fileName, data, events.size(), user.userId(), clock.instant()));
        audits.record(audits.event(org, "ICAL_FILE_UPLOADED").actor(user).target("ICAL_FILE", Long.toString(id))
                .detail("fileName", fileName).detail("eventCount", events.size()));
        return new IcalUploadResponse(FILE_KEY_PREFIX + id, events.size(), List.copyOf(categories));
    }

    /** 내부 API-DSC-78(제안): ingress의 공공 API 호출 결과를 더하고 한도 판정을 돌려준다(calls=0이면 판정만) */
    public UsageRecorded recordInternal(long sourceId, JsonNode body) {
        int calls = body == null ? 0 : body.path("calls").asInt(0);
        int failures = body == null ? 0 : body.path("failures").asInt(0);
        if (calls < 0 || calls > 100_000 || failures < 0 || failures > calls) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("calls", "Range", "0~100000, failures ≤ calls")));
        }
        ContextSource s = readTx.execute(status -> repository.findInternal(sourceId, deployment.restriction())
                .filter(c -> TYPES.contains(c.type()))
                .orElseThrow(() -> new BusinessException(ExternalErrorCode.SOURCE_NOT_FOUND)));
        ApiUsageService.Usage u = calls == 0 ? usage.current(s) : usage.record(s, calls, failures);
        return new UsageRecorded(u.day(), u.calls(), u.failures(), u.quota(), u.warning(), u.exhausted(), u.resumeAt());
    }

    // ---------------------------------------------------------------- 도우미

    ContextSourceView view(String type, ContextSource s) {
        if (s == null) {
            return new ContextSourceView(type, null, false, null, null, provider(type, false), null, false, null, null, null);
        }
        boolean keyStored = dataSources.findSecretMeta(s.organizationId(), s.id()).stream().map(SecretMeta::kind).anyMatch(API_KEY::equals);
        String state = health.findState(s.organizationId(), s.id()).map(st -> st.connectionState()).orElse(null);
        LastSync last = repository.findSync(s.organizationId(), s.id())
                .map(x -> new LastSync(x.lastAttemptAt(), x.lastStatus(), x.added(), x.updated(), x.removed(), x.lastError(), x.nextDueAt()))
                .orElse(null);
        ApiUsageService.Usage u = usage.current(s);
        UsageToday today = new UsageToday(u.day(), u.calls(), u.failures(), u.quota(), u.warning(), u.exhausted(), u.resumeAt());
        return new ContextSourceView(type, Long.toString(s.id()), "ACTIVE".equals(s.lifecycle()), s.lifecycle(), state,
                provider(type, keyStored), s.connection(), keyStored, last, today, s.version());
    }

    private ProviderView provider(String type, boolean keyStored) {
        ProviderDescriptor d = switch (type) {
            case "KMA_WEATHER" -> keyStored ? new ProviderDescriptor("KMA", true, false) : providers.weather(null).descriptor();
            case "AIRKOREA" -> keyStored ? new ProviderDescriptor("AIRKOREA", true, false) : providers.airQuality().descriptor();
            case "HOLIDAY" -> providers.holidays().descriptor();
            default -> new ProviderDescriptor("ICAL", true, false);
        };
        return new ProviderView(d.key(), d.available(), d.simulated());
    }

    private String apiKey(ContextSource s) {
        Secret secret = secrets.decrypt(s.id(), dataSources.findSecrets(s.organizationId(), s.id())).get(API_KEY);
        return secret == null ? null : secret.reveal();
    }

    private ContextSource source(long org, long sourceId) {
        return repository.findById(org, sourceId).filter(s -> TYPES.contains(s.type()) && !"ARCHIVED".equals(s.lifecycle()))
                .filter(s -> s.siteId() == null || roleChecker.spaceScope().includes(s.siteId()))
                .orElseThrow(() -> new BusinessException(ExternalErrorCode.SOURCE_NOT_FOUND));
    }

    private Space site(long org, long siteId) {
        Space site = spaces.findById(org, siteId).filter(s -> s.type() == SpaceType.SITE)
                .orElseThrow(() -> new BusinessException(ExternalErrorCode.SPACE_NOT_FOUND));
        roleChecker.requireSpace(site.id(), ExternalErrorCode.SPACE_NOT_FOUND);
        return site;
    }

    private String code(long org, String type, long siteId) {
        String base = "ctx-" + type.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + siteId;
        String code = base;
        for (int i = 2; repository.existsCode(org, code); i++) {
            code = base + "-" + i;
        }
        return code;
    }

    private static KmaGrid.Point grid(Space site) {
        try {
            return KmaGrid.toGrid(site.latitude().doubleValue(), site.longitude().doubleValue());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ExternalErrorCode.SITE_LOCATION_REQUIRED);
        }
    }

    private void items(JsonNode body, ObjectNode conn, Set<String> allowed, List<String> defaults, List<FieldErrorDetail> errors) {
        if (!body.has("items") && conn.has("items")) {
            return;
        }
        ArrayNode out = json.createArrayNode();
        JsonNode raw = body.get("items");
        if (raw == null || raw.isNull()) {
            defaults.forEach(out::add);
        } else if (!raw.isArray() || raw.isEmpty()) {
            errors.add(new FieldErrorDetail("items", "NotEmpty", null));
        } else {
            for (JsonNode i : raw) {
                String v = i.asString("").toUpperCase(Locale.ROOT).replace(".", "");
                if (!allowed.contains(v)) {
                    errors.add(new FieldErrorDetail("items", "INVALID", v));
                } else {
                    out.add(v);
                }
            }
        }
        conn.set("items", out);
    }

    private static int intField(JsonNode body, String field, int current, int min, int max, List<FieldErrorDetail> errors) {
        if (!body.has(field) || body.get(field).isNull()) {
            return current;
        }
        JsonNode n = body.get(field);
        if (!n.isIntegralNumber() || n.asLong() < min || n.asLong() > max) {
            errors.add(new FieldErrorDetail(field, "Range", min + "~" + max));
            return current;
        }
        return n.asInt();
    }

    static String normalizeType(String raw) {
        String t = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        if (t.equals("KMA")) {
            t = "KMA_WEATHER";
        }
        if (!TYPES.contains(t)) {
            throw new BusinessException(ExternalErrorCode.SOURCE_CONFIG_INVALID, List.of(new FieldErrorDetail("type", "UNSUPPORTED", null)), "type");
        }
        return t;
    }

    static boolean validIcalUrl(String url, boolean allowHttp) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("webcal://") || (allowHttp && lower.startsWith("http://"));
    }

    /** webcal:// → https:// */
    static String httpUrl(String url) {
        return url.regionMatches(true, 0, "webcal://", 0, 9) ? "https://" + url.substring(9) : url;
    }

    static long icalFileId(String key) {
        String id = key.substring(FILE_KEY_PREFIX.length());
        if (!id.matches("\\d{1,18}")) {
            return -1;
        }
        return Long.parseLong(id);
    }

    private static BusinessException fileInvalid(String code) {
        return new BusinessException(ExternalErrorCode.SOURCE_CONFIG_INVALID, List.of(new FieldErrorDetail("file", code, null)), "file");
    }
}
