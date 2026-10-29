package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.compliance.domain.ComplianceErrorCode;
import net.java21.data2flow.core.compliance.domain.ProfileBodyReader;
import net.java21.data2flow.core.compliance.domain.ProfileModels.Band;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.domain.ProfileModels.VersionSpec;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.ApplicationRef;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.BandView;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.CloneRequest;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.CoverageView;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.MetricView;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.ProfileDetail;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.ProfileSummary;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.Streak;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.Usage;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.UserRef;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.VersionSummary;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.VersionView;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.ApplicationRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository.ProfileRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository.VersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 기준 프로필(CMP-01.01·01.02, API-CMP-01~06a).
 *
 * <ul>
 *   <li>조회 CMP_READ(VIEWER+), 생성·버전·복제·보관 CMP_ADMIN(ADMIN·INTEGRATOR). 다른 조직 프로필은 404 CMP_PROFILE_NOT_FOUND</li>
 *   <li>시스템 프로필(organization_id 0)은 모든 조직이 읽고 복제만 한다. 버전 추가·수정·보관은 409 CMP_PROFILE_READ_ONLY(BR-CMP-01, TC-CMP-014)</li>
 *   <li>버전은 적용·리포트에 쓰이면 잠긴다. 잠긴 버전 수정은 CMP_PROFILE_READ_ONLY이고 새 버전을 만든다(BR-CMP-02, TC-CMP-001).
 *       새 버전은 현재 버전 + 1이고 최신을 따르는 적용(pinned 없음)에 EVT-CMP-01 VERSION_ADVANCED를 낸다</li>
 *   <li>낙관적 잠금은 프로필 행의 {@code version}. 버전 수정(PUT)은 {@code baseVersion} 필수, 버전 추가(POST)는 주면 확인한다</li>
 * </ul>
 */
@Service
public class ComplianceProfileService {

    private final ComplianceProfileRepository profiles;
    private final ComplianceApplicationRepository applications;
    private final ComplianceEvents events;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public ComplianceProfileService(ComplianceProfileRepository profiles, ComplianceApplicationRepository applications, ComplianceEvents events,
                                    RoleChecker roleChecker, Audits audits, Clock clock) {
        this.profiles = profiles;
        this.applications = applications;
        this.events = events;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-CMP-01 목록. 시스템 프로필이 먼저, 그다음 이름순 */
    @Transactional(readOnly = true)
    public ListApiResponse<ProfileSummary> list(String kind, String keyword, Boolean applied, Integer page, Integer size) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        String k = blankToNull(kind);
        if (k != null) {
            k = k.toUpperCase(Locale.ROOT);
            if (!k.equals("SYSTEM") && !k.equals("ORGANIZATION")) {
                throw ProfileBodyReader.invalid("kind", "Enum");
            }
        }
        String kw = blankToNull(keyword);
        PageParams params = PageParams.of(page, size);
        List<ProfileSummary> rows = profiles.list(orgId, k, kw, applied, params.size(), params.offset()).stream()
                .map(ComplianceProfileService::summary).toList();
        return ListApiResponse.of(params, rows, profiles.count(orgId, k, kw, applied));
    }

    /** API-CMP-02 상세: 현재 버전 전체와 이 조직의 적용 */
    @Transactional(readOnly = true)
    public ProfileDetail get(long profileId) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        return detail(orgId, visible(orgId, profileId));
    }

    /** API-CMP-03 버전 목록(최신부터) */
    @Transactional(readOnly = true)
    public List<VersionSummary> versions(long profileId) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        visible(orgId, profileId);
        // 리포트(CMP-02)는 아직 없어 리포트 사용 수는 0이다
        return profiles.versions(orgId, profileId).stream()
                .map(v -> new VersionSummary(v.versionNo(), v.changeNote(), user(v), v.createdAt(), v.locked(), new Usage(v.applicationCount(), 0)))
                .toList();
    }

    /** API-CMP-03 버전 하나 */
    @Transactional(readOnly = true)
    public VersionView version(long profileId, int versionNo) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        visible(orgId, profileId);
        VersionRow v = profiles.version(orgId, profileId, versionNo)
                .orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_PROFILE_NOT_FOUND));
        return versionView(v, profiles.spec(v));
    }

    /** API-CMP-04 조직 프로필 생성(버전 1 포함) */
    @Transactional
    public ProfileDetail create(JsonNode body) {
        roleChecker.require(Permission.CMP_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String name = name(body == null ? null : body.get("name"));
        VersionSpec spec = ProfileBodyReader.read(body == null ? null : body.get("version"), applications.metricUnits(orgId));
        requireNewName(orgId, name);
        Instant now = clock.instant();
        long id = profiles.insertProfile(orgId, name, user.userId(), now);
        profiles.insertVersion(orgId, id, 1, spec, user.userId(), now);
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_PROFILE_CREATED).actor(user).target("COMPLIANCE_PROFILE", Long.toString(id))
                .detail("name", name).detail("metrics", spec.metrics().size()));
        return detail(orgId, visible(orgId, id));
    }

    /** API-CMP-05 새 버전(POST). {@code changeNote} 필수 */
    @Transactional
    public VersionView addVersion(long profileId, JsonNode body) {
        roleChecker.require(Permission.CMP_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ProfileRow p = writable(orgId, profileId);
        if (body != null && body.has(VersionCheck.BASE_VERSION)) {
            VersionCheck.require(VersionCheck.baseVersion(body), p.version(), ComplianceErrorCode.CMP_PROFILE_VERSION_CONFLICT);
        }
        VersionSpec spec = ProfileBodyReader.read(body, applications.metricUnits(orgId));
        if (spec.changeNote() == null) {
            throw ProfileBodyReader.invalid("changeNote", "NotBlank");
        }
        int no = p.currentVersion() + 1;
        Instant now = clock.instant();
        profiles.insertVersion(orgId, profileId, no, spec, user.userId(), now);
        VersionCheck.requireUpdated(profiles.advance(profileId, p.version(), no, user.userId(), now),
                ComplianceErrorCode.CMP_PROFILE_VERSION_CONFLICT);
        for (ApplicationRow a : applications.byProfile(orgId, profileId)) {
            if (a.pinnedVersionNo() == null) {
                // 최신을 따르는 적용은 새 버전을 바로 쓰므로 그 버전도 잠긴다(BR-CMP-02)
                profiles.lockVersion(profileId, no);
                events.applicationChanged(orgId, a, no, ComplianceEvents.VERSION_ADVANCED);
            }
        }
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_PROFILE_VERSION_CREATED).actor(user)
                .target("COMPLIANCE_PROFILE", Long.toString(profileId)).detail("versionNo", no).detail("changeNote", spec.changeNote()));
        VersionRow v = profiles.version(orgId, profileId, no).orElseThrow();
        return versionView(v, profiles.spec(v));
    }

    /** API-CMP-05 잠기지 않은 버전 수정(PUT). {@code baseVersion} 필수 */
    @Transactional
    public VersionView updateVersion(long profileId, int versionNo, JsonNode body) {
        roleChecker.require(Permission.CMP_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ProfileRow p = writable(orgId, profileId);
        VersionCheck.require(VersionCheck.baseVersion(body), p.version(), ComplianceErrorCode.CMP_PROFILE_VERSION_CONFLICT);
        VersionRow v = profiles.version(orgId, profileId, versionNo)
                .orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_PROFILE_NOT_FOUND));
        if (v.locked()) {
            throw new BusinessException(ComplianceErrorCode.CMP_PROFILE_READ_ONLY);
        }
        VersionSpec spec = ProfileBodyReader.read(body, applications.metricUnits(orgId));
        if (versionNo > 1 && spec.changeNote() == null) {
            spec = spec.withChangeNote(v.changeNote());
        }
        Instant now = clock.instant();
        profiles.replaceVersion(orgId, v.id(), spec, now);
        VersionCheck.requireUpdated(profiles.touch(profileId, p.version(), user.userId(), now), ComplianceErrorCode.CMP_PROFILE_VERSION_CONFLICT);
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_PROFILE_VERSION_UPDATED).actor(user)
                .target("COMPLIANCE_PROFILE", Long.toString(profileId)).detail("versionNo", versionNo));
        VersionRow saved = profiles.version(orgId, profileId, versionNo).orElseThrow();
        return versionView(saved, profiles.spec(saved));
    }

    /** API-CMP-06 복제: 시스템·조직 프로필의 한 버전을 새 조직 프로필 v1로 */
    @Transactional
    public ProfileDetail cloneProfile(long profileId, CloneRequest request) {
        roleChecker.require(Permission.CMP_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ProfileRow source = visible(orgId, profileId);
        String name = request == null || request.name() == null ? null : request.name().strip();
        if (name == null || name.isEmpty()) {
            throw ProfileBodyReader.invalid("name", "NotBlank");
        }
        if (name.length() > 100) {
            throw ProfileBodyReader.invalid("name", "Size");
        }
        int versionNo = request.versionNo() == null ? source.currentVersion() : request.versionNo();
        VersionRow v = profiles.version(orgId, profileId, versionNo).orElseThrow(() -> ProfileBodyReader.invalid("versionNo", "Range"));
        requireNewName(orgId, name);
        VersionSpec spec = profiles.spec(v).withChangeNote(null);
        Instant now = clock.instant();
        long id = profiles.insertProfile(orgId, name, user.userId(), now);
        profiles.insertVersion(orgId, id, 1, spec, user.userId(), now);
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_PROFILE_CLONED).actor(user).target("COMPLIANCE_PROFILE", Long.toString(id))
                .detail("name", name).detail("sourceProfileId", Long.toString(profileId)).detail("sourceVersionNo", versionNo));
        return detail(orgId, visible(orgId, id));
    }

    /** API-CMP-06a 보관·해제. 보관한 프로필은 새로 적용할 수 없고 기존 적용은 그대로 둔다 */
    @Transactional
    public ProfileDetail archive(long profileId, boolean archived) {
        roleChecker.require(Permission.CMP_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ProfileRow p = writable(orgId, profileId);
        if ((p.archivedAt() != null) != archived) {
            Instant now = clock.instant();
            profiles.setArchived(profileId, archived ? now : null, user.userId(), now);
            audits.record(audits.event(orgId, archived ? ComplianceAuditCodes.CMP_PROFILE_ARCHIVED : ComplianceAuditCodes.CMP_PROFILE_UNARCHIVED)
                    .actor(user).target("COMPLIANCE_PROFILE", Long.toString(profileId)));
        }
        ProfileRow saved = visible(orgId, profileId);
        return new ProfileDetail(Long.toString(saved.id()), saved.kind(), saved.code(), saved.name(), saved.archivedAt() != null,
                saved.currentVersion(), null, null, saved.version(), saved.updatedAt());
    }

    ProfileRow visible(long orgId, long profileId) {
        return profiles.findVisible(orgId, profileId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_PROFILE_NOT_FOUND));
    }

    /** 이 조직이 고칠 수 있는 프로필. 시스템 프로필은 READ_ONLY. 행을 잠가 버전 번호 경쟁을 막는다 */
    ProfileRow writable(long orgId, long profileId) {
        ProfileRow p = visible(orgId, profileId);
        if (p.system()) {
            throw new BusinessException(ComplianceErrorCode.CMP_PROFILE_READ_ONLY);
        }
        profiles.lock(orgId, profileId);
        return visible(orgId, profileId);
    }

    private void requireNewName(long orgId, String name) {
        if (profiles.nameExists(orgId, name)) {
            throw new BusinessException(ComplianceErrorCode.CMP_PROFILE_NAME_DUPLICATE);
        }
    }

    private ProfileDetail detail(long orgId, ProfileRow p) {
        VersionRow v = profiles.version(orgId, p.id(), p.currentVersion()).orElseThrow();
        List<ApplicationRef> refs = applications.byProfile(orgId, p.id()).stream()
                .map(a -> new ApplicationRef(Long.toString(a.id()), a.targetType(), str(a.targetSpaceId()), a.targetUsage())).toList();
        return new ProfileDetail(Long.toString(p.id()), p.kind(), p.code(), p.name(), p.archivedAt() != null, p.currentVersion(),
                versionView(v, profiles.spec(v)), refs, p.version(), p.updatedAt());
    }

    static ProfileSummary summary(ProfileRow p) {
        return new ProfileSummary(Long.toString(p.id()), p.kind(), p.code(), p.name(), p.archivedAt() != null, p.currentVersion(), p.sourceTitle(),
                p.effectiveDate(), p.applicationCount(), p.updatedAt());
    }

    static VersionView versionView(VersionRow v, VersionSpec spec) {
        Streak streak = spec.streakDays() == null && spec.streakDailyRatio() == null ? null : new Streak(spec.streakDays(), spec.streakDailyRatio());
        List<MetricView> metrics = spec.metrics().stream().map(ComplianceProfileService::metricView).toList();
        List<CoverageView> coverage = spec.coverage().stream().map(c -> new CoverageView(c.kind(), c.params())).toList();
        return new VersionView(v.versionNo(), spec.sourceTitle(), spec.sourceUrl(), spec.effectiveDate(), spec.description(), streak,
                spec.facilityTypeRequired(), spec.facilityTypes(), spec.changeNote(), v.locked(), metrics, coverage, user(v), v.createdAt());
    }

    static MetricView metricView(MetricRule r) {
        List<BandView> bands = r.bands().stream().map(ComplianceProfileService::bandView).toList();
        return new MetricView(r.metricKey(), r.facilityType(), r.evalWindow(), r.lower(), r.upper(), r.inclusive(), r.unit(), r.requirement(),
                r.note(), bands);
    }

    static BandView bandView(Band b) {
        return new BandView(b.name(), b.lower(), b.upper(), b.base());
    }

    private static UserRef user(VersionRow v) {
        // 시스템 프로필 시드는 사용자 0으로 넣었다
        return v.createdByName() == null ? null : new UserRef(Long.toString(v.createdBy()), v.createdByName());
    }

    static String name(JsonNode node) {
        String name = node == null || node.isNull() ? null : node.asString("").strip();
        if (name == null || name.isEmpty()) {
            throw ProfileBodyReader.invalid("name", "NotBlank");
        }
        if (name.length() > 100) {
            throw ProfileBodyReader.invalid("name", "Size");
        }
        return name;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String str(Long v) {
        return v == null ? null : Long.toString(v);
    }
}
