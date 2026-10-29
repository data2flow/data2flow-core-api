package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.compliance.domain.ComplianceErrorCode;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.AppliedRule;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.Bound;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.Criterion;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.Source;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.Target;
import net.java21.data2flow.core.compliance.domain.MeasurabilityEvaluator;
import net.java21.data2flow.core.compliance.domain.MeasurabilityEvaluator.DeviceCoverage;
import net.java21.data2flow.core.compliance.domain.MeasurabilityEvaluator.Result;
import net.java21.data2flow.core.compliance.domain.ProfileBodyReader;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.domain.ProfileModels.VersionSpec;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.ApplicationView;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.CreateApplicationRequest;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.CriterionView;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.InternalCriteria;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.InternalCriterion;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.MeasurabilityItem;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.MeasurabilitySummary;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.Named;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.SourceView;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.UserRef;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.ApplicationRow;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.DeviceRow;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.SpaceRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository.ProfileRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository.VersionRow;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 프로필 적용과 유효 기준(CMP-01.03·01.04, API-CMP-07·08·09·26).
 *
 * <ul>
 *   <li>조회 CMP_READ, 적용·해제 CMP_ADMIN. 범위 밖 공간은 404(BR-CMP-23). 용도 적용(SPACE_USAGE)은 조직 전체라 공간 범위가 제한된 사용자는 만들 수
 *       없다(403)</li>
 *   <li>적용하면 쓰는 버전(고정 또는 최신)이 잠기고(BR-CMP-02) EVT-CMP-01을 낸다. 시설 유형이 필요한 프로필(국내법)은 그 프로필이 정한 유형 중 하나를
 *       골라야 한다(CMP_FACILITY_TYPE_REQUIRED). 보관한 프로필은 새로 적용할 수 없다(CMP_PROFILE_ARCHIVED)</li>
 *   <li>측정 가능성은 적용이 덮는 말단 공간과 그 안의 ACTIVE 실제 기기로 계산한다(BR-CMP-06). 권고기준(RECOMMENDED)은 판정하지 않아 빼고 본다</li>
 *   <li>유효 기준은 공간에 걸린 모든 적용의 유지기준을 항목별로 가장 엄격하게 합친다(BR-CMP-04·05). {@code at}은 형식만 확인하고 현재 적용으로
 *       계산한다(적용 이력은 아직 남기지 않는다)</li>
 * </ul>
 */
@Service
public class ComplianceApplicationService {

    static final Set<String> TARGET_TYPES = Set.of("SPACE", "SPACE_USAGE", "SITE");

    private final ComplianceApplicationRepository applications;
    private final ComplianceProfileRepository profiles;
    private final ComplianceEvents events;
    private final RoleChecker roleChecker;
    private final DeploymentOrganization deployment;
    private final Audits audits;
    private final Clock clock;

    public ComplianceApplicationService(ComplianceApplicationRepository applications, ComplianceProfileRepository profiles,
                                        ComplianceEvents events, RoleChecker roleChecker, DeploymentOrganization deployment, Audits audits,
                                        Clock clock) {
        this.applications = applications;
        this.profiles = profiles;
        this.events = events;
        this.roleChecker = roleChecker;
        this.deployment = deployment;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-CMP-07 목록(최근 적용부터). 범위 밖 공간에 걸린 적용은 빼고 센다 */
    @Transactional(readOnly = true)
    public ListApiResponse<ApplicationView> list(String profileId, String spaceId, Integer page, Integer size) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        SpaceScope scope = roleChecker.spaceScope();
        List<ApplicationRow> rows = applications.list(orgId, id(profileId, "profileId"), id(spaceId, "spaceId")).stream()
                .filter(a -> scope.includes(a.targetSpaceId())).toList();
        PageParams params = PageParams.of(page, size);
        int from = (int) Math.min(params.offset(), rows.size());
        int to = Math.min(from + params.size(), rows.size());
        List<ApplicationView> views = rows.subList(from, to).stream().map(a -> view(orgId, a)).toList();
        return ListApiResponse.of(params, views, rows.size());
    }

    /** API-CMP-07 적용 */
    @Transactional
    public ApplicationView create(CreateApplicationRequest request) {
        roleChecker.require(Permission.CMP_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (request == null) {
            throw ProfileBodyReader.invalid("profileId", "NotNull");
        }
        Long profileId = id(request.profileId(), "profileId");
        if (profileId == null) {
            throw ProfileBodyReader.invalid("profileId", "NotNull");
        }
        String targetType = request.targetType() == null ? null : request.targetType().strip().toUpperCase(Locale.ROOT);
        if (targetType == null || !TARGET_TYPES.contains(targetType)) {
            throw ProfileBodyReader.invalid("targetType", targetType == null ? "NotNull" : "Enum");
        }
        ProfileRow profile = profiles.findVisible(orgId, profileId)
                .orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_PROFILE_NOT_FOUND));
        profiles.lock(orgId, profileId);
        if (profile.archivedAt() != null) {
            throw new BusinessException(ComplianceErrorCode.CMP_PROFILE_ARCHIVED);
        }
        Integer pinned = request.pinnedVersionNo();
        int versionNo = pinned == null ? profile.currentVersion() : pinned;
        VersionRow version = profiles.version(orgId, profileId, versionNo)
                .orElseThrow(() -> ProfileBodyReader.invalid("pinnedVersionNo", "Range"));
        Long spaceId = null;
        String usage = null;
        boolean descendants = request.includeDescendants() == null || request.includeDescendants();
        if ("SPACE_USAGE".equals(targetType)) {
            usage = request.targetUsage() == null || request.targetUsage().isBlank() ? null : request.targetUsage().strip().toUpperCase(Locale.ROOT);
            if (usage == null) {
                throw ProfileBodyReader.invalid("targetUsage", "NotBlank");
            }
            if (!roleChecker.spaceScope().unrestricted()) {
                // 용도 적용은 조직의 모든 같은 용도 공간에 걸린다
                throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
            }
        } else {
            spaceId = id(request.targetSpaceId(), "targetSpaceId");
            if (spaceId == null) {
                throw ProfileBodyReader.invalid("targetSpaceId", "NotNull");
            }
            SpaceRow space = applications.space(orgId, spaceId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
            roleChecker.require(Permission.CMP_ADMIN, space.id(), ComplianceErrorCode.SPACE_NOT_FOUND);
            if ("SITE".equals(targetType) && !"SITE".equals(space.type())) {
                throw ProfileBodyReader.invalid("targetSpaceId", "SiteRequired");
            }
            if ("SITE".equals(targetType)) {
                descendants = true;
            }
        }
        VersionSpec spec = profiles.spec(version);
        String facility = facilityType(spec, request.facilityType());
        if (applications.exists(orgId, profileId, targetType, spaceId, usage)) {
            throw new BusinessException(ComplianceErrorCode.CMP_APPLICATION_DUPLICATE);
        }
        long id = applications.insert(orgId, profileId, pinned, targetType, spaceId, usage, descendants, facility, user.userId(), clock.instant());
        profiles.lockVersion(profileId, versionNo);
        ApplicationRow row = applications.find(orgId, id).orElseThrow();
        events.applicationChanged(orgId, row, versionNo, ComplianceEvents.CREATED);
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_PROFILE_APPLIED).actor(user).target("COMPLIANCE_APPLICATION", Long.toString(id))
                .detail("profileId", Long.toString(profileId)).detail("versionNo", versionNo).detail("targetType", targetType)
                .detail("targetSpaceId", spaceId == null ? null : Long.toString(spaceId)).detail("targetUsage", usage)
                .detail("facilityType", facility));
        return view(orgId, row);
    }

    /**
     * API-CMP-07 해제. {@code deactivateRules}는 필수다. 적용으로 만든 규칙(API-CMP-11)은 아직 없어서 연결은 적용과 함께 지워지기만 하고, 값은 감사에
     * 남긴다
     */
    @Transactional
    public void delete(long applicationId, Boolean deactivateRules) {
        roleChecker.require(Permission.CMP_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (deactivateRules == null) {
            throw ProfileBodyReader.invalid("deactivateRules", "NotNull");
        }
        ApplicationRow a = visibleApplication(orgId, applicationId);
        roleChecker.require(Permission.CMP_ADMIN, a.targetSpaceId(), ComplianceErrorCode.CMP_APPLICATION_NOT_FOUND);
        List<SpaceRow> covered = applications.coveredSpaces(orgId, a);
        applications.delete(orgId, applicationId);
        events.applicationChanged(orgId, a, a.effectiveVersionNo(), ComplianceEvents.DELETED, covered);
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_PROFILE_UNAPPLIED).actor(user)
                .target("COMPLIANCE_APPLICATION", Long.toString(applicationId)).detail("profileId", Long.toString(a.profileId()))
                .detail("deactivateRules", deactivateRules));
    }

    /** API-CMP-08 항목별 측정 가능성 */
    @Transactional(readOnly = true)
    public List<MeasurabilityItem> measurability(long applicationId) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        ApplicationRow a = visibleApplication(orgId, applicationId);
        return evaluate(orgId, a).stream().map(r -> new MeasurabilityItem(r.metricKey(), r.status().name(), r.reason(),
                r.spacesWithout().stream().map(ComplianceApplicationService::named).toList(),
                r.devices().stream().map(ComplianceApplicationService::named).toList())).toList();
    }

    /** API-CMP-09 공간의 유효 기준 */
    @Transactional(readOnly = true)
    public List<CriterionView> effectiveCriteria(long spaceId, String at) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        if (at != null && !at.isBlank()) {
            try {
                Instant.parse(at.strip());
            } catch (DateTimeParseException ex) {
                throw ProfileBodyReader.invalid("at", "Pattern");
            }
        }
        SpaceRow space = applications.space(orgId, spaceId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
        roleChecker.requireSpace(spaceId, ComplianceErrorCode.SPACE_NOT_FOUND);
        return criteria(orgId, space).stream().map(ComplianceApplicationService::criterionView).toList();
    }

    /** API-CMP-26 내부 유효 기준(flow-engine·analytics). 조직은 공간 행에서 정하고 배포 조직 밖은 404 */
    @Transactional(readOnly = true)
    public InternalCriteria internalCriteria(long spaceId) {
        OptionalLong restriction = deployment.restriction();
        long orgId = applications.findSpaceOrganization(restriction.isPresent() ? restriction.getAsLong() : null, spaceId)
                .orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
        SpaceRow space = applications.space(orgId, spaceId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
        List<Criterion> criteria = criteria(orgId, space);
        boolean fromProfile = criteria.stream().anyMatch(c -> !c.fromTarget());
        List<InternalCriterion> out = criteria.stream().map(c -> new InternalCriterion(c.metricKey(), c.unit(), c.evalWindow(),
                value(c.lower()), inclusive(c.lower()), value(c.upper()), inclusive(c.upper()),
                c.strictestSource() == null ? null : Long.toString(c.strictestSource().profileId()),
                c.strictestSource() == null ? null : c.strictestSource().versionNo())).toList();
        return new InternalCriteria(Long.toString(spaceId), fromProfile ? "PROFILE" : "DEFAULT", out, clock.instant());
    }

    List<Criterion> criteria(long orgId, SpaceRow space) {
        List<AppliedRule> rules = new ArrayList<>();
        Map<String, List<MetricRule>> versionRules = new HashMap<>();
        for (ApplicationRow a : applications.forSpace(orgId, space)) {
            int no = a.effectiveVersionNo();
            List<MetricRule> all = versionRules.computeIfAbsent(a.profileId() + ":" + no,
                    k -> profiles.version(orgId, a.profileId(), no).map(v -> profiles.rules(v.id())).orElse(List.of()));
            for (MetricRule r : all) {
                if (r.appliesTo(a.facilityType())) {
                    rules.add(new AppliedRule(a.profileId(), a.profileName(), no, r));
                }
            }
        }
        List<Target> targets = applications.targets(orgId, space).stream().map(t -> new Target(t.metricKey(), t.min(), t.max())).toList();
        return EffectiveCriteriaResolver.resolve(rules, targets);
    }

    List<Result> evaluate(long orgId, ApplicationRow a) {
        int no = a.effectiveVersionNo();
        List<String> keys = profiles.version(orgId, a.profileId(), no).map(v -> profiles.rules(v.id())).orElse(List.of()).stream()
                .filter(r -> r.appliesTo(a.facilityType()) && !"RECOMMENDED".equals(r.requirement())).map(MetricRule::metricKey).distinct().toList();
        List<SpaceRow> covered = applications.coveredSpaces(orgId, a);
        List<SpaceRow> leaves = leaves(covered);
        List<DeviceCoverage> devices = new ArrayList<>();
        for (DeviceRow d : applications.activeRealDevices(orgId, covered.stream().map(SpaceRow::id).toList())) {
            Set<Long> leafIds = new LinkedHashSet<>();
            for (SpaceRow leaf : leaves) {
                if (leaf.path().startsWith(d.spacePath())) {
                    leafIds.add(leaf.id());
                }
            }
            devices.add(new DeviceCoverage(d.id(), d.name(), leafIds, d.metricKeys()));
        }
        return MeasurabilityEvaluator.evaluate(keys, leaves.stream().map(s -> new MeasurabilityEvaluator.Named(s.id(), s.name())).toList(), devices);
    }

    /** 덮는 공간 중 다른 덮는 공간의 조상이 아닌 공간 */
    static List<SpaceRow> leaves(List<SpaceRow> covered) {
        return covered.stream().filter(s -> covered.stream().noneMatch(o -> o.id() != s.id() && o.path().startsWith(s.path()))).toList();
    }

    /** 시설 유형: 필요한 프로필은 정한 유형 중 하나여야 하고, 유형이 없는 프로필에 주면 400 */
    static String facilityType(VersionSpec spec, String raw) {
        String facility = raw == null || raw.isBlank() ? null : raw.strip().toUpperCase(Locale.ROOT);
        List<String> types = spec.facilityTypes();
        if (facility == null) {
            if (spec.facilityTypeRequired()) {
                throw new BusinessException(ComplianceErrorCode.CMP_FACILITY_TYPE_REQUIRED);
            }
            return null;
        }
        if (!types.contains(facility)) {
            throw ProfileBodyReader.invalid("facilityType", "Enum");
        }
        return facility;
    }

    private ApplicationRow visibleApplication(long orgId, long applicationId) {
        ApplicationRow a = applications.find(orgId, applicationId)
                .orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_APPLICATION_NOT_FOUND));
        roleChecker.requireSpace(a.targetSpaceId(), ComplianceErrorCode.CMP_APPLICATION_NOT_FOUND);
        return a;
    }

    private ApplicationView view(long orgId, ApplicationRow a) {
        int measurable = 0;
        int partial = 0;
        int unmeasurable = 0;
        for (Result r : evaluate(orgId, a)) {
            switch (r.status()) {
                case MEASURABLE -> measurable++;
                case PARTIAL -> partial++;
                case NOT_MEASURABLE -> unmeasurable++;
            }
        }
        return new ApplicationView(Long.toString(a.id()), Long.toString(a.profileId()), a.profileName(), a.pinnedVersionNo(),
                a.effectiveVersionNo(), a.targetType(), a.targetSpaceId() == null ? null : Long.toString(a.targetSpaceId()), a.targetName(),
                a.targetUsage(), a.includeDescendants(), a.facilityType(),
                a.appliedByName() == null ? null : new UserRef(Long.toString(a.appliedBy()), a.appliedByName()), a.appliedAt(),
                new MeasurabilitySummary(measurable, partial, unmeasurable));
    }

    static CriterionView criterionView(Criterion c) {
        List<SourceView> sources = c.sources().stream().map(ComplianceApplicationService::sourceView).toList();
        return new CriterionView(c.metricKey(), value(c.lower()), inclusive(c.lower()), value(c.upper()), inclusive(c.upper()), c.unit(),
                c.evalWindow(), sources, c.strictestSource() == null ? null : sourceView(c.strictestSource()), c.overridesTarget());
    }

    private static SourceView sourceView(Source s) {
        return new SourceView(Long.toString(s.profileId()), s.profileName(), s.versionNo(), s.band());
    }

    private static java.math.BigDecimal value(Bound b) {
        return b == null ? null : b.value();
    }

    private static Boolean inclusive(Bound b) {
        return b == null ? null : b.inclusive();
    }

    private static Named named(MeasurabilityEvaluator.Named n) {
        return new Named(Long.toString(n.id()), n.name());
    }

    private static Long id(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException ex) {
            throw ProfileBodyReader.invalid(field, "Pattern");
        }
    }
}
