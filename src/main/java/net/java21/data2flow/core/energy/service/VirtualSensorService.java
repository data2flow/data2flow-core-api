package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.event.EnergyConfigChanged.Scope;
import net.java21.data2flow.contracts.message.event.EnergyVirtualSensorUpdated;
import net.java21.data2flow.core.analytics.service.AnalyticsClient;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.AfterCommit;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.domain.OccupancyExposurePolicy;
import net.java21.data2flow.core.energy.dto.EnergyInternalDtos.VirtualSensorEntry;
import net.java21.data2flow.core.energy.dto.EnergyInternalDtos.VirtualSensorInput;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.AchDay;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.AchSeries;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.DecayWindow;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.Input;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.OccupancyNow;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.OccupancyPoint;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.OccupancySeries;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.SensorView;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.UpdateRequest;
import net.java21.data2flow.core.energy.repository.VirtualSensorRepository;
import net.java21.data2flow.core.energy.repository.VirtualSensorRepository.ConfigRow;
import net.java21.data2flow.core.energy.repository.VirtualSensorRepository.DerivedPoint;
import net.java21.data2flow.core.energy.repository.VirtualSensorRepository.EnabledRow;
import net.java21.data2flow.core.energy.repository.VirtualSensorRepository.SpaceGeometry;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static net.java21.data2flow.core.energy.service.EnergyAccess.invalid;

/**
 * 공간 가상 센서(ENE-02.01 환기율 ACH, ENE-02.02 재실 추정, API-ENE-20~22, UC-ENE-06·07, BR-ENE-09·10). 계산은 analytics가 하고(ACH 매일,
 * 재실 5분마다) 결과는 EVT-ENE-03으로 받는다. 재실 추정 값은 공간 가상 기기의 측정 항목 {@code occupancy_est}로 저장된다(pipeline API-ING-25).
 * <ul>
 *   <li>조회 ENE_READ, 변경 ENE_ADMIN. 범위 밖·없는 공간은 404 SPACE_NOT_FOUND</li>
 *   <li>ACH를 켜려면 공간 면적·층고(부피)가 있어야 하고(409 ENE_SPACE_VOLUME_REQUIRED), 공간에 CO2 기기가 있어야 한다
 *       (409 ENE_VIRTUAL_SENSOR_INPUT_MISSING, errors[].code = 없는 측정 항목)</li>
 *   <li>변경하면 EVT-ENE-07(VIRTUAL_SENSOR)을 내고, 커밋 뒤 analytics에 바로 반영을 요청한다(API-ENE-82, 실패는 5분 캐시로 회복)</li>
 * </ul>
 */
@Service
public class VirtualSensorService {

    static final String RELOAD = "/internal/analytics/energy/virtual-sensors/reload";
    static final String ACH_PATH = "/internal/analytics/energy/spaces/%d/ach";
    static final String ACH = "ACH";
    static final String CO2 = "co2";
    static final List<String> ACH_OUTPUTS = List.of("ach", "ventilation_m3h");
    static final int MAX_RANGE_DAYS = 366;
    static final String OCCUPANCY = "OCCUPANCY";
    static final String OCCUPANCY_METRIC = "occupancy_est";
    static final List<String> OCCUPANCY_OUTPUTS = List.of(OCCUPANCY_METRIC);
    /** 재실 추정 입력 신호 → 측정 키(BuiltinCatalog: activity=PIR, LAeq=등가 소음, illumination=조도) */
    static final Map<String, String> SIGNALS = new LinkedHashMap<>();
    static {
        SIGNALS.put("pir", "activity");
        SIGNALS.put("co2", CO2);
        SIGNALS.put("noise", "LAeq");
        SIGNALS.put("illuminance", "illumination");
    }
    static final List<String> DEFAULT_SIGNALS = List.of("pir", "co2", "noise");
    static final int MIN_SIGNALS = 2;
    /** 이보다 오래된 재실 추정은 현재 상태로 쓰지 않는다(UNKNOWN) */
    static final java.time.Duration STALE = java.time.Duration.ofMinutes(15);
    static final int MAX_OCCUPANCY_DAYS = 31;
    private static final Set<String> ACH_PARAMS = Set.of("outdoorCo2Mode", "outdoorCo2Ppm", "minDecayMinutes");

    private final VirtualSensorRepository repo;
    private final EnergyAccess access;
    private final RoleChecker roleChecker;
    private final EnergyEvents events;
    private final AnalyticsClient analytics;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public VirtualSensorService(VirtualSensorRepository repo, EnergyAccess access, RoleChecker roleChecker, EnergyEvents events,
                                AnalyticsClient analytics, Audits audits, JsonMapper json, Clock clock) {
        this.repo = repo;
        this.access = access;
        this.roleChecker = roleChecker;
        this.events = events;
        this.analytics = analytics;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SensorView> list(String spaceId) {
        long orgId = roleChecker.currentUser().organizationId();
        long space = space(Permission.ENE_READ, orgId, spaceId);
        SpaceGeometry geometry = repo.geometry(orgId, space).orElseThrow(() -> new BusinessException(EnergyErrorCode.SPACE_NOT_FOUND));
        Map<String, ConfigRow> rows = new LinkedHashMap<>();
        repo.list(orgId, space).forEach(r -> rows.put(r.kind(), r));
        return List.of(view(orgId, space, ACH, rows.get(ACH), geometry), view(orgId, space, OCCUPANCY, rows.get(OCCUPANCY), geometry));
    }

    @Transactional
    public SensorView update(String spaceId, String kindRaw, UpdateRequest body) {
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        long space = space(Permission.ENE_ADMIN, orgId, spaceId);
        String kind = kind(kindRaw);
        if (body == null || body.enabled() == null) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "enabled", "NotNull");
        }
        boolean ach = ACH.equals(kind);
        ObjectNode params = ach ? achParams(body.params()) : occupancyParams(body.params());
        SpaceGeometry geometry = repo.geometry(orgId, space).orElseThrow(() -> new BusinessException(EnergyErrorCode.SPACE_NOT_FOUND));
        Instant now = clock.instant();
        if (body.enabled() && ach) {
            if (geometry.volumeM3() == null) {
                throw new BusinessException(EnergyErrorCode.ENE_SPACE_VOLUME_REQUIRED);
            }
            if (repo.inputDevices(orgId, space, CO2).isEmpty()) {
                throw new BusinessException(EnergyErrorCode.ENE_VIRTUAL_SENSOR_INPUT_MISSING, List.of(new FieldErrorDetail("inputs", CO2, null)));
            }
        } else if (body.enabled()) {
            List<String> selected = signals(params);
            List<String> missing = selected.stream().filter(sig -> repo.inputDevices(orgId, space, SIGNALS.get(sig)).isEmpty()).toList();
            if (selected.size() - missing.size() < MIN_SIGNALS) {
                throw new BusinessException(EnergyErrorCode.ENE_VIRTUAL_SENSOR_INPUT_MISSING,
                        missing.stream().map(sig -> new FieldErrorDetail("inputs", sig, null)).toList());
            }
            repo.ensureVirtualDevice(orgId, space, geometry.name(), user.userId(), now);
        }
        repo.upsert(orgId, space, kind, body.enabled(), json.writeValueAsString(params), ach ? ACH_OUTPUTS : OCCUPANCY_OUTPUTS, user.userId(), now);
        audits.record(audits.event(orgId, EnergyAuditCodes.ENE_VIRTUAL_SENSOR_CHANGED).actor(user).target("SPACE", Long.toString(space))
                .detail("kind", kind).detail("enabled", body.enabled().toString()));
        events.configChanged(orgId, Scope.VIRTUAL_SENSOR, space);
        Map<String, Object> reload = Map.of("orgId", Long.toString(orgId), "spaceId", Long.toString(space));
        AfterCommit.run("virtual-sensor reload " + space, () -> analytics.call(HttpMethod.POST, RELOAD, null, reload, null));
        return view(orgId, space, kind, repo.find(orgId, space, kind).orElseThrow(), geometry);
    }

    /** API-ENE-21: analytics 일별 ACH에 환기량(ACH × 부피)을 붙인다 */
    @Transactional(readOnly = true)
    public AchSeries ach(String spaceId, String from, String to) {
        long orgId = roleChecker.currentUser().organizationId();
        long space = space(Permission.ENE_READ, orgId, spaceId);
        LocalDate start = date("from", from);
        LocalDate end = date("to", to);
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "to", "Range");
        }
        SpaceGeometry geometry = repo.geometry(orgId, space).orElseThrow(() -> new BusinessException(EnergyErrorCode.SPACE_NOT_FOUND));
        Double volume = geometry.volumeM3();
        ConfigRow config = repo.find(orgId, space, ACH).orElse(null);
        double outdoor = config == null ? 420 : json.readTree(config.paramsJson()).path("outdoorCo2Ppm").asDouble(420);
        JsonNode res = analytics.call(HttpMethod.GET, ACH_PATH.formatted(space), Map.of("from", start.toString(), "to", end.toString()), null,
                EnergyErrorCode.SPACE_NOT_FOUND);
        List<AchDay> items = new ArrayList<>();
        for (JsonNode n : res.path("items")) {
            double ach = n.path("ach").asDouble();
            JsonNode w = n.path("decayWindow");
            items.add(new AchDay(n.path("day").asString(), ach, number(n, "ciLow"), number(n, "ciHigh"), volume == null ? null : ach * volume,
                    new DecayWindow(instant(w, "from"), instant(w, "to")), number(n, "fitR2")));
        }
        return new AchSeries(Long.toString(space), volume, outdoor, items);
    }

    /**
     * API-ENE-22 재실 추정 이력: 공간 가상 기기의 {@code occupancy_est}를 노출 규칙(BR-ENE-10)으로 바꿔 돌려준다. 숫자 필드는 없다.
     * 기간은 31일 이내(기본 최근 24시간)
     */
    @Transactional(readOnly = true)
    public OccupancySeries occupancy(String spaceId, String from, String to) {
        long orgId = roleChecker.currentUser().organizationId();
        long space = space(Permission.ENE_READ, orgId, spaceId);
        Instant end = to == null || to.isBlank() ? clock.instant() : instantParam("to", to);
        Instant start = from == null || from.isBlank() ? end.minus(java.time.Duration.ofDays(1)) : instantParam("from", from);
        if (!end.isAfter(start) || java.time.Duration.between(start, end).toDays() >= MAX_OCCUPANCY_DAYS) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "to", "Range");
        }
        SpaceGeometry geometry = repo.geometry(orgId, space).orElseThrow(() -> new BusinessException(EnergyErrorCode.SPACE_NOT_FOUND));
        List<OccupancyPoint> items = new ArrayList<>();
        repo.findVirtualDevice(orgId, space).ifPresent(device -> {
            for (DerivedPoint p : repo.derived(orgId, device, OCCUPANCY_METRIC, start, end)) {
                OccupancyExposurePolicy.Exposure e = OccupancyExposurePolicy.expose(p.value(), geometry.capacity());
                items.add(new OccupancyPoint(p.time(), e.state(), e.band()));
            }
        });
        return new OccupancySeries(Long.toString(space), true, items);
    }

    /** EVT-ENE-03: 마지막 계산 시각·상태. 설정이 없으면 false */
    @Transactional
    public boolean onUpdated(long orgId, EnergyVirtualSensorUpdated e, Instant at) {
        return repo.recordComputed(orgId, e.spaceId(), e.kind().name(), e.status().name(), e.missing(), at) > 0;
    }

    /** API-ENE-70 {@code virtualSensors[]}: 켜진 설정과 입력 기기·부피·시간대 */
    @Transactional(readOnly = true)
    public List<VirtualSensorEntry> bundle(long orgId) {
        List<VirtualSensorEntry> out = new ArrayList<>();
        for (EnabledRow r : repo.enabled(orgId)) {
            ConfigRow c = r.config();
            JsonNode params = json.readTree(c.paramsJson());
            if (ACH.equals(c.kind())) {
                List<String> devices = repo.inputDevices(orgId, c.spaceId(), CO2).stream().map(String::valueOf).toList();
                out.add(new VirtualSensorEntry(Long.toString(c.spaceId()), c.kind(), true, params, c.outputMetrics(),
                        r.space().timezone(), r.space().volumeM3(), List.of(new VirtualSensorInput(CO2, devices, CO2)), null));
            } else if (OCCUPANCY.equals(c.kind())) {
                Long device = repo.findVirtualDevice(orgId, c.spaceId()).orElse(null);
                if (device == null) {
                    continue;  // 켤 때 만들므로 보통 없을 수 없다(기기를 지운 경우) — 다시 켜면 생긴다
                }
                List<VirtualSensorInput> inputs = new ArrayList<>();
                for (String sig : signals(params)) {
                    String key = SIGNALS.get(sig);
                    inputs.add(new VirtualSensorInput(key, repo.inputDevices(orgId, c.spaceId(), key).stream().map(String::valueOf).toList(), sig));
                }
                out.add(new VirtualSensorEntry(Long.toString(c.spaceId()), c.kind(), true, params, c.outputMetrics(), r.space().timezone(),
                        r.space().volumeM3(), inputs, Long.toString(device)));
            }
        }
        return out;
    }

    private SensorView view(long orgId, long space, String kind, ConfigRow row, SpaceGeometry geometry) {
        return ACH.equals(kind) ? achView(orgId, space, row, geometry) : occupancyView(orgId, space, row, geometry);
    }

    private SensorView occupancyView(long orgId, long space, ConfigRow row, SpaceGeometry geometry) {
        boolean enabled = row != null && row.enabled();
        JsonNode params = row == null ? occupancyParams(null) : json.readTree(row.paramsJson());
        List<String> selected = signals(params);
        List<Input> inputs = new ArrayList<>();
        int available = 0;
        for (Map.Entry<String, String> e : SIGNALS.entrySet()) {
            List<Long> devices = repo.inputDevices(orgId, space, e.getValue());
            boolean chosen = selected.contains(e.getKey());
            if (chosen && !devices.isEmpty()) {
                available++;
            }
            inputs.add(new Input(e.getValue(), !devices.isEmpty(), devices.stream().map(String::valueOf).toList(), e.getKey(), chosen));
        }
        String status;
        List<String> missing = null;
        if (!enabled) {
            status = "DISABLED";
        } else if (available < MIN_SIGNALS) {
            status = "INPUT_MISSING";
            missing = inputs.stream().filter(i -> i.selected() && !i.available()).map(Input::signal).toList();
        } else if ("INPUT_MISSING".equals(row.lastStatus())) {
            status = "INPUT_MISSING";
            missing = row.lastMissing();
        } else {
            status = "OK";
        }
        Long device = repo.findVirtualDevice(orgId, space).orElse(null);
        OccupancyNow current = null;
        if (enabled) {
            DerivedPoint latest = device == null ? null : repo.latestDerived(orgId, device, OCCUPANCY_METRIC).orElse(null);
            if (latest == null || latest.time().isBefore(clock.instant().minus(STALE))) {
                current = new OccupancyNow(OccupancyExposurePolicy.UNKNOWN, null, latest == null ? null : latest.time());
            } else {
                OccupancyExposurePolicy.Exposure e = OccupancyExposurePolicy.expose(latest.value(), geometry.capacity());
                current = new OccupancyNow(e.state(), e.band(), latest.time());
            }
        }
        return new SensorView(OCCUPANCY, enabled, params, OCCUPANCY_OUTPUTS, inputs, row == null ? null : row.lastComputedAt(), status, missing,
                geometry.volumeM3(), device == null ? null : Long.toString(device), current);
    }

    private SensorView achView(long orgId, long space, ConfigRow row, SpaceGeometry geometry) {
        List<Long> devices = repo.inputDevices(orgId, space, CO2);
        List<Input> inputs = List.of(new Input(CO2, !devices.isEmpty(), devices.stream().map(String::valueOf).toList(), CO2, true));
        boolean enabled = row != null && row.enabled();
        JsonNode params = row == null ? achParams(null) : json.readTree(row.paramsJson());
        String status;
        List<String> missing = null;
        if (!enabled) {
            status = "DISABLED";
        } else if (devices.isEmpty()) {
            status = "INPUT_MISSING";
            missing = List.of(CO2);
        } else if ("INPUT_MISSING".equals(row.lastStatus())) {
            status = "INPUT_MISSING";
            missing = row.lastMissing();
        } else {
            status = "OK";
        }
        return new SensorView(ACH, enabled, params, ACH_OUTPUTS, inputs, row == null ? null : row.lastComputedAt(), status, missing,
                geometry.volumeM3(), null, null);
    }

    private long space(Permission permission, long orgId, String raw) {
        Long id = EnergyAccess.id(raw, EnergyErrorCode.SPACE_NOT_FOUND);
        if (id == null) {
            throw new BusinessException(EnergyErrorCode.SPACE_NOT_FOUND);
        }
        access.space(permission, orgId, id);
        return id;
    }

    /** 경로의 kind(대소문자 무관). 열쾌적(ENE-02.03)은 아직 받지 않는다 */
    static String kind(String raw) {
        String kind = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ACH", "OCCUPANCY", "THERMAL_COMFORT").contains(kind)) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "kind", "INVALID");
        }
        if ("THERMAL_COMFORT".equals(kind)) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "kind", "NOT_SUPPORTED");
        }
        return kind;
    }

    /** ACH 설정 {@code {outdoorCo2Mode: FIXED, outdoorCo2Ppm: 300~600(기본 420), minDecayMinutes: 15~240(기본 30)}} */
    ObjectNode achParams(JsonNode in) {
        if (in != null && !in.isNull() && !in.isObject()) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "params", "Type");
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (in != null && in.isObject()) {
            for (String key : in.propertyNames()) {
                if (!ACH_PARAMS.contains(key)) {
                    errors.add(new FieldErrorDetail("params." + key, "UNKNOWN", null));
                }
            }
        }
        JsonNode mode = in == null ? null : in.get("outdoorCo2Mode");
        if (mode != null && !mode.isNull() && !"FIXED".equals(mode.asString(""))) {
            errors.add(new FieldErrorDetail("params.outdoorCo2Mode", "INVALID", "FIXED"));
        }
        double ppm = range(in, "outdoorCo2Ppm", 420, 300, 600, false, errors);
        double minutes = range(in, "minDecayMinutes", 30, 15, 240, true, errors);
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        ObjectNode out = json.createObjectNode();
        out.put("outdoorCo2Mode", "FIXED");
        out.put("outdoorCo2Ppm", ppm);
        out.put("minDecayMinutes", (int) minutes);
        return out;
    }

    /** 재실 추정 설정 {@code {inputs: [pir|co2|noise|illuminance] 2~4개, 중복 없음(기본 pir·co2·noise)}} */
    ObjectNode occupancyParams(JsonNode in) {
        if (in != null && !in.isNull() && !in.isObject()) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "params", "Type");
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        List<String> signals = new ArrayList<>();
        if (in != null && in.isObject()) {
            for (String key : in.propertyNames()) {
                if (!"inputs".equals(key)) {
                    errors.add(new FieldErrorDetail("params." + key, "UNKNOWN", null));
                }
            }
            JsonNode inputs = in.get("inputs");
            if (inputs != null && !inputs.isNull()) {
                if (!inputs.isArray()) {
                    errors.add(new FieldErrorDetail("params.inputs", "Type", null));
                } else {
                    for (JsonNode v : inputs) {
                        String sig = v.isString() ? v.asString().strip().toLowerCase(Locale.ROOT) : "";
                        if (!SIGNALS.containsKey(sig)) {
                            errors.add(new FieldErrorDetail("params.inputs", "INVALID", String.join(",", SIGNALS.keySet())));
                        } else if (signals.contains(sig)) {
                            errors.add(new FieldErrorDetail("params.inputs", "DUPLICATE", sig));
                        } else {
                            signals.add(sig);
                        }
                    }
                    if (errors.isEmpty() && signals.size() < MIN_SIGNALS) {
                        errors.add(new FieldErrorDetail("params.inputs", "Size", MIN_SIGNALS + "~" + SIGNALS.size()));
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        if (signals.isEmpty()) {
            signals.addAll(DEFAULT_SIGNALS);
        }
        ObjectNode out = json.createObjectNode();
        var arr = out.putArray("inputs");
        SIGNALS.keySet().stream().filter(signals::contains).forEach(arr::add);  // 정해진 순서로
        return out;
    }

    /** 저장된 재실 추정 설정의 신호(모르는 값은 버림) */
    static List<String> signals(JsonNode params) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : params.path("inputs")) {
            String sig = v.asString("");
            if (SIGNALS.containsKey(sig) && !out.contains(sig)) {
                out.add(sig);
            }
        }
        return out;
    }

    private static Instant instantParam(String field, String raw) {
        try {
            return java.time.OffsetDateTime.parse(raw.strip()).toInstant();
        } catch (DateTimeParseException ex) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, field, "DateTime");
        }
    }

    private static double range(JsonNode in, String key, double fallback, double min, double max, boolean integer, List<FieldErrorDetail> errors) {
        JsonNode v = in == null ? null : in.get(key);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isNumber() || (integer && !v.canConvertToExactIntegral()) || v.asDouble() < min || v.asDouble() > max) {
            errors.add(new FieldErrorDetail("params." + key, "Range", (integer ? (int) min + "~" + (int) max : min + "~" + max)));
            return fallback;
        }
        return v.asDouble();
    }

    private static LocalDate date(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, field, "NotNull");
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException ex) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, field, "Date");
        }
    }

    private static Double number(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isNumber() ? v.asDouble() : null;
    }

    private static Instant instant(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isString() ? java.time.OffsetDateTime.parse(v.asString()).toInstant() : null;
    }
}
