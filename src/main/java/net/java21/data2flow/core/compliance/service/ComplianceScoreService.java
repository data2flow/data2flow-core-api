package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.ComplianceScoreUpdated;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.compliance.domain.ComplianceErrorCode;
import net.java21.data2flow.core.compliance.domain.DefaultCriteria;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.Criterion;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.Source;
import net.java21.data2flow.core.compliance.domain.IeqScoreCalculator;
import net.java21.data2flow.core.compliance.domain.IeqScoreCalculator.Boundary;
import net.java21.data2flow.core.compliance.domain.IeqScoreCalculator.Grade;
import net.java21.data2flow.core.compliance.domain.IeqScoreCalculator.Input;
import net.java21.data2flow.core.compliance.domain.IeqScoreCalculator.Score;
import net.java21.data2flow.core.compliance.domain.ProfileBodyReader;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.UserRef;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.BoundaryView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.GradeBand;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.HistoryPointView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.ScoreFormula;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.ScoreHistory;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.ScoreInput;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.ScoreView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.WeightView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.WeightsRequest;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.WeightsView;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.SpaceRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository.WeightsRow;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 종합 실내 환경 점수(CMP-01.05, API-CMP-10·27, EVT-CMP-02).
 *
 * <ul>
 *   <li>점수 작업은 5분 경계마다 실제 기기가 놓인 공간의 점수를 저장하고, 새로 저장한 행만 EVT-CMP-02로 낸다(같은 시각 두 번이면 한 번)</li>
 *   <li>현재 점수 API는 지금 직전 5분 경계로 바로 계산한다(입력값을 함께 보여 줄 수 있게). 이력은 저장한 점수를 읽는다</li>
 *   <li>가중치는 기본값(BR-CMP-08) 위에 조직 값을 덮는다. 0이면 그 항목을 빼고, 변경은 감사 로그에 남긴다</li>
 * </ul>
 */
@Service
public class ComplianceScoreService {

    static final Duration STEP = Duration.ofMinutes(5);
    static final Duration MAX_HISTORY = Duration.ofDays(400);
    static final Duration MAX_HISTORY_5M = Duration.ofDays(31);
    static final BigDecimal MAX_WEIGHT = new BigDecimal("10");
    private static final Pattern METRIC_KEY = Pattern.compile("[a-z0-9_]{1,64}");
    static final String DESCRIPTION = "항목별 지수(1~5)를 가중 평균해 0~100으로 나타낸다. 입력은 직전 5분 평균이고 최근 10분 데이터가 없는 항목은 뺀다";
    static final String INDEX_RULE = "상한형: ≤50%·U → 5, ≤U → 4, 초과 ≤25% → 3, ≤50% → 2, 그 밖 → 1. "
            + "범위형: 안쪽 여유가 폭의 25% 이상 → 5, 범위 안 → 4, 밖이면 초과량÷폭 ≤25% → 3, ≤50% → 2, 그 밖 → 1. "
            + "점수 = round(100 × Σ(w·(i−1)/4) / Σw)";

    private final ComplianceApplicationService applicationService;
    private final ComplianceApplicationRepository applications;
    private final ComplianceProfileRepository profiles;
    private final ComplianceStatusRepository status;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final DeploymentOrganization deployment;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public ComplianceScoreService(ComplianceApplicationService applicationService, ComplianceApplicationRepository applications,
                                  ComplianceProfileRepository profiles, ComplianceStatusRepository status, CoreEventPublisher publisher,
                                  RoleChecker roleChecker, DeploymentOrganization deployment, Audits audits, JsonMapper json, Clock clock) {
        this.applicationService = applicationService;
        this.applications = applications;
        this.profiles = profiles;
        this.status = status;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.deployment = deployment;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** 한 번 계산한 결과와 근거 */
    record Computed(Instant time, Score score, DefaultCriteria.Basis basis, List<Input> inputs, List<Long> versionIds) {
    }

    /** 점수 작업: 조직의 점수를 저장하고 새 행마다 EVT-CMP-02. 저장한 공간 수 */
    public int scoreAll(long orgId, Instant now) {
        Instant time = boundary(now);
        Map<String, BigDecimal> weights = weights(orgId);
        int n = 0;
        for (SpaceRow space : status.scoredSpaces(orgId)) {
            Computed c = compute(orgId, space, time, weights);
            Score s = c.score();
            boolean inserted = status.insertScore(orgId, space.id(), time, s.score(), json.writeValueAsString(s.indices()), s.topPenaltyMetric(),
                    c.basis().name(), s.excludedMetrics(), c.versionIds(), now);
            if (inserted) {
                Map<String, Integer> indices = new LinkedHashMap<>();
                s.indices().forEach((k, v) -> {
                    if (v != null) {
                        indices.put(k, v);
                    }
                });
                publisher.event(EventType.COMPLIANCE_SCORE_UPDATED, orgId, new ComplianceScoreUpdated(space.id(), time, s.score(),
                        s.grade() == null ? null : ComplianceScoreUpdated.Grade.valueOf(s.grade().name()), indices, s.topPenaltyMetric(),
                        ComplianceScoreUpdated.Basis.valueOf(c.basis().name())));
                n++;
            }
        }
        return n;
    }

    /** API-CMP-10 현재 점수 */
    @Transactional(readOnly = true)
    public ScoreView score(long spaceId, boolean includeInputs) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        SpaceRow space = applications.space(orgId, spaceId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
        roleChecker.requireSpace(spaceId, ComplianceErrorCode.SPACE_NOT_FOUND);
        return view(orgId, space, includeInputs);
    }

    /** API-CMP-27 내부 현재 점수(입력값 포함). 조직은 공간 행에서 정하고 배포 조직 밖은 404 */
    @Transactional(readOnly = true)
    public ScoreView internalScore(long spaceId) {
        OptionalLong restriction = deployment.restriction();
        long orgId = applications.findSpaceOrganization(restriction.isPresent() ? restriction.getAsLong() : null, spaceId)
                .orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
        SpaceRow space = applications.space(orgId, spaceId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
        return view(orgId, space, true);
    }

    private ScoreView view(long orgId, SpaceRow space, boolean includeInputs) {
        Computed c = compute(orgId, space, boundary(clock.instant()), weights(orgId));
        Score s = c.score();
        List<ScoreInput> inputs = null;
        if (includeInputs) {
            Map<String, String> units = applications.metricUnits(orgId);
            inputs = c.inputs().stream().map(i -> new ScoreInput(i.metricKey(), i.avg5m(), blankToNull(units.get(i.metricKey())), i.weight(),
                    new BoundaryView(i.boundary().lower(), i.boundary().upper()))).toList();
        }
        return new ScoreView(Long.toString(space.id()), c.time(), s.score(), s.grade() == null ? null : s.grade().name(), s.indices(),
                s.topPenaltyMetric(), s.topPenaltyValue(), c.basis().name(), s.excludedMetrics(), s.partial(),
                c.versionIds().stream().map(id -> Long.toString(id)).toList(), IeqScoreCalculator.FORMULA_VERSION, inputs);
    }

    /** API-CMP-10 점수 이력 */
    @Transactional(readOnly = true)
    public ScoreHistory history(long spaceId, String from, String to, String step) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        applications.space(orgId, spaceId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
        roleChecker.requireSpace(spaceId, ComplianceErrorCode.SPACE_NOT_FOUND);
        String s = step == null || step.isBlank() ? "5m" : step.strip();
        if (!Set.of("5m", "1h", "1d").contains(s)) {
            throw ProfileBodyReader.invalid("step", "Enum");
        }
        Instant end = to == null || to.isBlank() ? clock.instant() : instant(to, "to");
        Instant start = from == null || from.isBlank() ? end.minus(Duration.ofDays(1)) : instant(from, "from");
        if (!start.isBefore(end) || Duration.between(start, end).compareTo("5m".equals(s) ? MAX_HISTORY_5M : MAX_HISTORY) > 0) {
            throw new BusinessException(ComplianceErrorCode.CMP_REPORT_PERIOD_INVALID);
        }
        List<HistoryPointView> points = status.history(orgId, spaceId, start, end, s, status.timezone(orgId)).stream()
                .map(p -> {
                    Integer rounded = p.score() == null ? null : (int) Math.round(p.score());
                    Grade g = Grade.of(rounded);
                    return new HistoryPointView(p.time(), p.score() == null ? null : Math.round(p.score() * 10) / 10.0,
                            g == null ? null : g.name(), p.topPenaltyMetric());
                }).toList();
        return new ScoreHistory(Long.toString(spaceId), s, points);
    }

    /** API-CMP-10 공개 계산식과 조직 가중치 */
    @Transactional(readOnly = true)
    public ScoreFormula formula() {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        WeightsRow row = status.weights(orgId).orElse(null);
        Map<String, BoundaryView> defaults = new LinkedHashMap<>();
        DefaultCriteria.SYSTEM.forEach((k, b) -> defaults.put(k, new BoundaryView(b.lower(), b.upper())));
        List<GradeBand> bands = new ArrayList<>();
        for (Grade g : Grade.values()) {
            bands.add(new GradeBand(g.name(), g.min, g.max));
        }
        return new ScoreFormula(IeqScoreCalculator.FORMULA_VERSION, DESCRIPTION, INDEX_RULE, weightViews(weights(orgId)), bands, defaults,
                row == null || row.updatedByName() == null ? null : new UserRef(Long.toString(row.updatedBy()), row.updatedByName()),
                row == null ? null : row.updatedAt(), row == null ? 0 : row.version());
    }

    /** API-CMP-10 가중치 바꾸기 — CMP_ADMIN. 보낸 항목만 바꾸고 나머지는 그대로 둔다 */
    @Transactional
    public WeightsView saveWeights(WeightsRequest request) {
        roleChecker.require(Permission.CMP_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (request == null || request.weights() == null || request.weights().isEmpty()) {
            throw ProfileBodyReader.invalid("weights", "NotEmpty");
        }
        if (request.baseVersion() == null) {
            throw ProfileBodyReader.invalid("baseVersion", "NotNull");
        }
        Map<String, BigDecimal> stored = stored(orgId);
        Map<String, BigDecimal> before = new LinkedHashMap<>(stored);
        for (Map.Entry<String, Object> e : request.weights().entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().strip();
            if (!METRIC_KEY.matcher(key).matches()) {
                throw ProfileBodyReader.invalid("weights." + key, "Pattern");
            }
            BigDecimal w;
            try {
                w = e.getValue() instanceof Number num ? new BigDecimal(num.toString()) : null;
            } catch (NumberFormatException ex) {
                w = null;
            }
            if (w == null || w.signum() < 0 || w.compareTo(MAX_WEIGHT) > 0) {
                throw ProfileBodyReader.invalid("weights." + key, "Range");
            }
            stored.put(key, w.stripTrailingZeros());
        }
        Instant now = clock.instant();
        if (status.saveWeights(orgId, json.writeValueAsString(stored), request.baseVersion(), user.userId(), now) == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_SCORE_WEIGHTS_CHANGED).actor(user)
                .target("COMPLIANCE_SCORE_WEIGHTS", Long.toString(orgId)).detail("before", before.toString()).detail("after", stored.toString()));
        WeightsRow row = status.weights(orgId).orElseThrow();
        return new WeightsView(IeqScoreCalculator.FORMULA_VERSION, weightViews(weights(orgId)), row.version(), row.updatedAt());
    }

    Computed compute(long orgId, SpaceRow space, Instant time, Map<String, BigDecimal> weights) {
        List<Criterion> criteria = applicationService.criteria(orgId, space);
        DefaultCriteria.Resolved resolved = DefaultCriteria.resolve(criteria, weights.keySet());
        Map<String, Double> averages = status.recentAverages(orgId, space.id(), resolved.boundaries().keySet(), time);
        List<Input> inputs = new ArrayList<>();
        for (Map.Entry<String, Boundary> e : resolved.boundaries().entrySet()) {
            double w = weights.get(e.getKey()).doubleValue();
            if (w > 0) {
                inputs.add(new Input(e.getKey(), averages.get(e.getKey()), w, e.getValue()));
            }
        }
        Set<Long> versionIds = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Criterion c : criteria) {
            for (Source src : c.sources()) {
                if (seen.add(src.profileId() + ":" + src.versionNo())) {
                    profiles.version(orgId, src.profileId(), src.versionNo()).ifPresent(v -> versionIds.add(v.id()));
                }
            }
        }
        return new Computed(time, IeqScoreCalculator.calculate(inputs), resolved.basis(), inputs, List.copyOf(versionIds));
    }

    /** 기본 가중치 위에 조직 값을 덮은 가중치(기본 항목 순서, 조직이 더한 항목은 뒤) */
    Map<String, BigDecimal> weights(long orgId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>(IeqScoreCalculator.DEFAULT_WEIGHTS);
        out.putAll(stored(orgId));
        return out;
    }

    private Map<String, BigDecimal> stored(long orgId) {
        return status.weights(orgId).map(r -> json.readValue(r.weights(), new TypeReference<LinkedHashMap<String, BigDecimal>>() {
        })).map(m -> (Map<String, BigDecimal>) new LinkedHashMap<>(m)).orElseGet(LinkedHashMap::new);
    }

    private static List<WeightView> weightViews(Map<String, BigDecimal> weights) {
        return weights.entrySet().stream().map(e -> new WeightView(e.getKey(), e.getValue(), IeqScoreCalculator.DEFAULT_WEIGHTS.get(e.getKey())))
                .toList();
    }

    static Instant boundary(Instant now) {
        long step = STEP.getSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), step) * step);
    }

    private static Instant instant(String raw, String field) {
        try {
            return Instant.parse(raw.strip());
        } catch (DateTimeParseException ex) {
            throw ProfileBodyReader.invalid(field, "Pattern");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
