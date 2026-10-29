package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.compliance.domain.ComplianceErrorCode;
import net.java21.data2flow.core.compliance.domain.CoverageChecks;
import net.java21.data2flow.core.compliance.domain.CoverageChecks.Calibration;
import net.java21.data2flow.core.compliance.domain.CoverageChecks.CalibrationStatus;
import net.java21.data2flow.core.compliance.domain.CoverageChecks.DeviceSamples;
import net.java21.data2flow.core.compliance.domain.CoverageChecks.Display;
import net.java21.data2flow.core.compliance.domain.CoverageChecks.Quality;
import net.java21.data2flow.core.compliance.domain.CoverageChecks.SpaceArea;
import net.java21.data2flow.core.compliance.domain.ProfileModels.Coverage;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.CalibrationRow;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.CoverageView;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.DensityRow;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.DisplayActual;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.Period;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.ProfileRef;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.PublicDisplay;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.QualityRow;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.Recommendation;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.Ref;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.Summary;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.DeviceRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository.ProfileRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository.VersionRow;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository.DeviceCalibration;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository;
import net.java21.data2flow.core.compliance.repository.CoverageRepository;
import net.java21.data2flow.core.compliance.repository.CoverageRepository.AreaSpace;
import net.java21.data2flow.core.compliance.repository.CoverageRepository.DeviceInfo;
import net.java21.data2flow.core.compliance.repository.CoverageRepository.Samples;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 모니터링 커버리지 점검 API-CMP-25(CMP-03.01~04, UC-CMP-11). CMP_READ.
 *
 * <ul>
 *   <li>대상: 고른 공간(보통 사이트)과 하위 공간. 사용자 공간 범위 밖 공간은 빠지고, 남는 공간이 없으면 404(BR-CMP-23)</li>
 *   <li>프로필의 적정성 규칙(profile_coverage_rules)을 쓴다. DEVICE_DENSITY가 없으면 설치 밀도는 빈 목록, PUBLIC_DISPLAY_DENSITY가 없으면
 *       공개 표시는 null이다. 교정 주기와 품질 기준은 규칙이 없으면 기본값(365일, 결측 10%, 900초)</li>
 *   <li>설치 밀도 대상은 규칙의 spaceUsages 용도(없으면 용도가 있는 방·구역)이고, 실제 대수는 그 공간과 하위의 ACTIVE 실제 기기 중
 *       필수 항목(LIMIT, 없으면 규칙 항목 전부)을 하나 이상 보고하는 기기 수다</li>
 *   <li>공개 표시의 실제 수는 공개 화면·QR(OCC-02)이 아직 없어 0이고 actualAvailable=false로 알린다</li>
 *   <li>교정 주기는 프로필 기본(자산 정보·모델에 교정 주기 칸이 아직 없다). 마지막 교정일은 완료한 CALIBRATION 작업 지시의 완료일</li>
 *   <li>데이터 품질은 오늘 전 30일(조직 시간대)을 방·구역마다 본다</li>
 * </ul>
 */
@Service
public class CoverageService {

    static final int QUALITY_DAYS = 30;
    static final Set<String> LEAF_TYPES = Set.of("ROOM", "ZONE");

    private final CoverageRepository coverage;
    private final ComplianceApplicationRepository applications;
    private final ComplianceProfileRepository profiles;
    private final ComplianceReportRepository reports;
    private final ComplianceStatusRepository status;
    private final RoleChecker roleChecker;
    private final Clock clock;

    public CoverageService(CoverageRepository coverage, ComplianceApplicationRepository applications, ComplianceProfileRepository profiles,
                           ComplianceReportRepository reports, ComplianceStatusRepository status, RoleChecker roleChecker, Clock clock) {
        this.coverage = coverage;
        this.applications = applications;
        this.profiles = profiles;
        this.reports = reports;
        this.status = status;
        this.roleChecker = roleChecker;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CoverageView check(String siteId, String profileId) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        long rootId = id(siteId, ComplianceErrorCode.SPACE_NOT_FOUND);
        AreaSpace root = coverage.space(orgId, rootId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
        SpaceScope scope = roleChecker.spaceScope();
        List<AreaSpace> spaces = coverage.subtree(orgId, root.path()).stream().filter(s -> scope.unrestricted() || scope.includes(s.id())).toList();
        if (spaces.isEmpty()) {
            throw new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND);
        }

        ProfileRef profileRef = null;
        List<MetricRule> rules = List.of();
        Map<String, JsonNode> params = new HashMap<>();
        if (profileId != null && !profileId.isBlank()) {
            ProfileRow p = profiles.findVisible(orgId, id(profileId, ComplianceErrorCode.CMP_PROFILE_NOT_FOUND))
                    .orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_PROFILE_NOT_FOUND));
            VersionRow v = profiles.version(orgId, p.id(), p.currentVersion())
                    .orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_PROFILE_NOT_FOUND));
            profileRef = new ProfileRef(Long.toString(p.id()), p.name(), v.versionNo());
            rules = profiles.rules(v.id());
            for (Coverage c : profiles.coverage(v.id())) {
                params.put(c.kind(), c.params());
            }
        }

        ZoneId zone = ZoneId.of(status.timezone(orgId));
        LocalDate today = LocalDate.now(clock.withZone(zone));
        List<DeviceRow> devices = applications.activeRealDevices(orgId, spaces.stream().map(AreaSpace::id).toList());
        Set<String> required = requiredKeys(rules);

        List<DensityRow> density = density(spaces, devices, required, params.get("DEVICE_DENSITY"));
        PublicDisplay display = publicDisplay(spaces, params.get("PUBLIC_DISPLAY_DENSITY"));
        List<CalibrationRow> calibration = calibration(orgId, spaces, devices, positive(params.get("CALIBRATION_INTERVAL"), "defaultIntervalDays",
                CoverageChecks.DEFAULT_CALIBRATION_DAYS), zone, today);
        LocalDate from = today.minusDays(QUALITY_DAYS);
        JsonNode q = params.get("DATA_QUALITY");
        double maxMissing = q != null && q.path("maxMissingRatio").isNumber() ? q.path("maxMissingRatio").doubleValue()
                : CoverageChecks.DEFAULT_MAX_MISSING_RATIO;
        List<QualityRow> quality = quality(orgId, spaces, devices, required.isEmpty() ? allKeys(devices) : required,
                from.atStartOfDay(zone).toInstant(), today.atStartOfDay(zone).toInstant(), maxMissing,
                positive(q, "maxReportIntervalSec", CoverageChecks.DEFAULT_MAX_INTERVAL_SEC));

        Summary summary = new Summary(densitySignal(density, params.containsKey("DEVICE_DENSITY")), displaySignal(display),
                calibrationSignal(calibration), qualitySignal(quality));
        return new CoverageView(Long.toString(root.id()), profileRef, new Period(from, today.minusDays(1)), summary, density, display, calibration,
                quality);
    }

    private List<DensityRow> density(List<AreaSpace> spaces, List<DeviceRow> devices, Set<String> required, JsonNode rule) {
        if (rule == null) {
            return List.of();
        }
        int perDevice = positive(rule, "maxAreaPerDeviceM2", 0);
        if (perDevice <= 0) {
            return List.of();
        }
        Set<String> usages = new LinkedHashSet<>();
        rule.path("spaceUsages").values().forEach(u -> usages.add(u.asString()));
        List<DensityRow> out = new ArrayList<>();
        for (AreaSpace s : spaces) {
            if (!LEAF_TYPES.contains(s.type()) || s.usage() == null || !usages.isEmpty() && !usages.contains(s.usage())) {
                continue;
            }
            int actual = (int) devices.stream()
                    .filter(d -> d.spacePath().startsWith(s.path()) && (required.isEmpty() || d.metricKeys().stream().anyMatch(required::contains)))
                    .count();
            CoverageChecks.Density d = CoverageChecks.density(s.areaM2(), perDevice, actual);
            out.add(new DensityRow(ref(s), s.usage(), s.areaM2(), d.required(), actual, d.shortage(), d.undeterminedReason()));
        }
        return out;
    }

    private PublicDisplay publicDisplay(List<AreaSpace> spaces, JsonNode rule) {
        int perDisplay = positive(rule, "areaPerDisplayM2", 0);
        if (rule == null || perDisplay <= 0) {
            return null;
        }
        List<SpaceArea> regular = spaces.stream().filter(s -> LEAF_TYPES.contains(s.type()) && CoverageChecks.REGULAR_USAGES.contains(s.usage()))
                .map(s -> new SpaceArea(s.id(), s.name(), s.areaM2())).toList();
        Display d = CoverageChecks.publicDisplay(regular, perDisplay, 0);
        return new PublicDisplay(d.scopeAreaM2(), d.required(), new DisplayActual(0, 0), d.shortage(), false,
                d.recommendations().stream().map(s -> new Recommendation(Long.toString(s.spaceId()), s.name(), s.areaM2())).toList());
    }

    private List<CalibrationRow> calibration(long orgId, List<AreaSpace> spaces, List<DeviceRow> devices, int intervalDays, ZoneId zone,
                                             LocalDate today) {
        Map<Long, DeviceCalibration> cals = new HashMap<>();
        reports.calibrations(orgId, devices.stream().map(DeviceRow::id).toList(), zone.getId()).forEach(c -> cals.put(c.deviceId(), c));
        List<CalibrationRow> out = new ArrayList<>();
        for (DeviceRow d : devices) {
            DeviceCalibration c = cals.get(d.id());
            LocalDate last = c == null ? null : c.lastCalibratedOn();
            Calibration due = CoverageChecks.calibration(last, intervalDays, today);
            out.add(new CalibrationRow(new Ref(Long.toString(d.id()), d.name()), ref(spaceOf(spaces, d.spacePath())), c == null ? null : c.model(),
                    intervalDays, last, due.dueOn(), due.elapsedDays(), due.status().name()));
        }
        // 초과 → 임박 → 기록 없음 → 정상
        out.sort(Comparator.comparingInt((CalibrationRow r) -> switch (CalibrationStatus.valueOf(r.status())) {
            case OVERDUE -> 0;
            case DUE_SOON -> 1;
            case NO_RECORD -> 2;
            case OK -> 3;
        }).thenComparing(r -> Long.parseLong(r.device().id())));
        return out;
    }

    private List<QualityRow> quality(long orgId, List<AreaSpace> spaces, List<DeviceRow> devices, Set<String> keys, Instant from, Instant to,
                                     double maxMissing, int maxInterval) {
        Map<Long, DeviceInfo> info = coverage.devices(orgId, devices.stream().map(DeviceRow::id).toList());
        Map<Long, Samples> samples = coverage.samples(orgId, info.keySet(), keys, from, to);
        Map<AreaSpace, List<DeviceSamples>> bySpace = new LinkedHashMap<>();
        for (DeviceRow d : devices) {
            AreaSpace s = spaceOf(spaces, d.spacePath());
            DeviceInfo i = info.get(d.id());
            if (s == null || i == null) {
                continue;
            }
            Instant start = i.createdAt() != null && i.createdAt().isAfter(from) ? i.createdAt() : from;
            long seconds = Math.max(0, Duration.between(start, to).toSeconds());
            Samples got = samples.getOrDefault(d.id(), new Samples(0, 0));
            bySpace.computeIfAbsent(s, k -> new ArrayList<>()).add(new DeviceSamples(seconds, i.intervalSec(), got.good(), got.all()));
        }
        List<QualityRow> out = new ArrayList<>();
        bySpace.forEach((s, list) -> {
            Quality q = CoverageChecks.quality(list, maxMissing, maxInterval);
            out.add(new QualityRow(ref(s), list.size(), percent(q.missingRatio()), q.intervalViolations(), percent(q.abnormalQualityRatio()),
                    q.belowStandard()));
        });
        return out;
    }

    /** 기기가 놓인 공간에서 가장 가까운 방·구역(없으면 놓인 공간) */
    private static AreaSpace spaceOf(List<AreaSpace> spaces, String devicePath) {
        AreaSpace best = null;
        AreaSpace exact = null;
        for (AreaSpace s : spaces) {
            if (devicePath.equals(s.path())) {
                exact = s;
            }
            if (LEAF_TYPES.contains(s.type()) && devicePath.startsWith(s.path()) && (best == null || s.path().length() > best.path().length())) {
                best = s;
            }
        }
        return best != null ? best : exact;
    }

    static String densitySignal(List<DensityRow> rows, boolean applicable) {
        if (!applicable) {
            return "NA";
        }
        if (rows.stream().anyMatch(r -> r.shortage() != null && r.shortage() > 0)) {
            return "RED";
        }
        return rows.stream().anyMatch(r -> r.undeterminedReason() != null) ? "YELLOW" : "GREEN";
    }

    static String displaySignal(PublicDisplay d) {
        return d == null ? "NA" : d.shortage() > 0 ? "YELLOW" : "GREEN";
    }

    static String calibrationSignal(List<CalibrationRow> rows) {
        if (rows.isEmpty()) {
            return "NA";
        }
        if (rows.stream().anyMatch(r -> "OVERDUE".equals(r.status()))) {
            return "RED";
        }
        return rows.stream().anyMatch(r -> !"OK".equals(r.status())) ? "YELLOW" : "GREEN";
    }

    static String qualitySignal(List<QualityRow> rows) {
        if (rows.isEmpty()) {
            return "NA";
        }
        if (rows.stream().anyMatch(QualityRow::belowStandard)) {
            return "RED";
        }
        return rows.stream().anyMatch(r -> r.intervalViolations() > 0) ? "YELLOW" : "GREEN";
    }

    /** 필수 항목: LIMIT 규칙 항목, 없으면 규칙 항목 전부 */
    static Set<String> requiredKeys(List<MetricRule> rules) {
        Set<String> limit = new LinkedHashSet<>();
        Set<String> all = new LinkedHashSet<>();
        for (MetricRule r : rules) {
            all.add(r.metricKey());
            if ("LIMIT".equals(r.requirement())) {
                limit.add(r.metricKey());
            }
        }
        return limit.isEmpty() ? all : limit;
    }

    private static Set<String> allKeys(List<DeviceRow> devices) {
        Set<String> keys = new LinkedHashSet<>();
        devices.forEach(d -> keys.addAll(d.metricKeys()));
        return keys;
    }

    private static int positive(JsonNode node, String field, int fallback) {
        JsonNode n = node == null ? null : node.get(field);
        return n != null && n.isNumber() && n.intValue() > 0 ? n.intValue() : fallback;
    }

    private static Double percent(Double ratio) {
        return ratio == null ? null : Math.round(ratio * 1000) / 10.0;
    }

    private static Ref ref(AreaSpace s) {
        return s == null ? null : new Ref(Long.toString(s.id()), s.name());
    }

    private static long id(String raw, ComplianceErrorCode notFound) {
        try {
            return Long.parseLong(raw == null ? "" : raw.strip());
        } catch (NumberFormatException ex) {
            throw new BusinessException(notFound);
        }
    }
}
