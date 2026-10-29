package net.java21.data2flow.core.compliance.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.ApplicationView;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.CloneRequest;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.CreateApplicationRequest;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.CriterionView;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.MeasurabilityItem;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.ProfileDetail;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.ProfileSummary;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.VersionSummary;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.VersionView;
import net.java21.data2flow.core.compliance.service.ComplianceApplicationService;
import net.java21.data2flow.core.compliance.service.ComplianceProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;

/** 환경 기준 프로필·적용(CMP-01): API-CMP-01~09 */
@RestController
public class ComplianceController {

    private static final String BASE = "/api/v1/core/compliance";

    private final ComplianceProfileService profiles;
    private final ComplianceApplicationService applications;

    public ComplianceController(ComplianceProfileService profiles, ComplianceApplicationService applications) {
        this.profiles = profiles;
        this.applications = applications;
    }

    /** API-CMP-01 목록 — CMP_READ */
    @GetMapping("/core/compliance/profiles")
    public ListApiResponse<ProfileSummary> list(@RequestParam(required = false) String kind,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Boolean applied,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return profiles.list(kind, keyword, applied, page, size);
    }

    /** API-CMP-02 상세 — CMP_READ. 현재 버전 전체와 적용을 늘 싣는다(include는 받기만 한다) */
    @GetMapping("/core/compliance/profiles/{profile-id}")
    public ApiResponse<ProfileDetail> get(@PathVariable("profile-id") long profileId) {
        return ApiResponse.success(profiles.get(profileId));
    }

    /** API-CMP-04 생성 — CMP_ADMIN, 201 */
    @PostMapping("/core/compliance/profiles")
    @Idempotent
    public ResponseEntity<ApiResponse<ProfileDetail>> create(@RequestBody JsonNode body) {
        ProfileDetail created = profiles.create(body);
        return ResponseEntity.created(URI.create(BASE + "/profiles/" + created.profileId())).body(ApiResponse.success(created));
    }

    /** API-CMP-03 버전 목록 — CMP_READ */
    @GetMapping("/core/compliance/profiles/{profile-id}/versions")
    public ApiResponse<List<VersionSummary>> versions(@PathVariable("profile-id") long profileId) {
        return ApiResponse.success(profiles.versions(profileId));
    }

    /** API-CMP-03 버전 하나 — CMP_READ */
    @GetMapping("/core/compliance/profiles/{profile-id}/versions/{version-no}")
    public ApiResponse<VersionView> version(@PathVariable("profile-id") long profileId, @PathVariable("version-no") int versionNo) {
        return ApiResponse.success(profiles.version(profileId, versionNo));
    }

    /** API-CMP-05 새 버전 — CMP_ADMIN, 201 */
    @PostMapping("/core/compliance/profiles/{profile-id}/versions")
    @Idempotent
    public ResponseEntity<ApiResponse<VersionView>> addVersion(@PathVariable("profile-id") long profileId, @RequestBody JsonNode body) {
        VersionView created = profiles.addVersion(profileId, body);
        return ResponseEntity.created(URI.create(BASE + "/profiles/" + profileId + "/versions/" + created.versionNo()))
                .body(ApiResponse.success(created));
    }

    /** API-CMP-05 잠기지 않은 버전 수정 — CMP_ADMIN, baseVersion 필수 */
    @PutMapping("/core/compliance/profiles/{profile-id}/versions/{version-no}")
    public ApiResponse<VersionView> updateVersion(@PathVariable("profile-id") long profileId, @PathVariable("version-no") int versionNo,
                                                  @RequestBody JsonNode body) {
        return ApiResponse.success(profiles.updateVersion(profileId, versionNo, body));
    }

    /** API-CMP-06 복제 — CMP_ADMIN, 201 */
    @PostMapping("/core/compliance/profiles/{profile-id}/clone")
    @Idempotent
    public ResponseEntity<ApiResponse<ProfileDetail>> cloneProfile(@PathVariable("profile-id") long profileId, @RequestBody CloneRequest request) {
        ProfileDetail created = profiles.cloneProfile(profileId, request);
        return ResponseEntity.created(URI.create(BASE + "/profiles/" + created.profileId())).body(ApiResponse.success(created));
    }

    /** API-CMP-06a 보관 — CMP_ADMIN */
    @PostMapping("/core/compliance/profiles/{profile-id}/archive")
    public ApiResponse<ProfileDetail> archive(@PathVariable("profile-id") long profileId) {
        return ApiResponse.success(profiles.archive(profileId, true));
    }

    /** API-CMP-06a 보관 해제 — CMP_ADMIN */
    @PostMapping("/core/compliance/profiles/{profile-id}/unarchive")
    public ApiResponse<ProfileDetail> unarchive(@PathVariable("profile-id") long profileId) {
        return ApiResponse.success(profiles.archive(profileId, false));
    }

    /** API-CMP-07 적용 목록 — CMP_READ */
    @GetMapping("/core/compliance/applications")
    public ListApiResponse<ApplicationView> applications(@RequestParam(required = false) String profileId,
                                                         @RequestParam(required = false) String spaceId,
                                                         @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        return applications.list(profileId, spaceId, page, size);
    }

    /** API-CMP-07 적용 — CMP_ADMIN, 201 */
    @PostMapping("/core/compliance/applications")
    @Idempotent
    public ResponseEntity<ApiResponse<ApplicationView>> apply(@RequestBody CreateApplicationRequest request) {
        ApplicationView created = applications.create(request);
        return ResponseEntity.created(URI.create(BASE + "/applications/" + created.applicationId())).body(ApiResponse.success(created));
    }

    /** API-CMP-07 해제 — CMP_ADMIN, 204. deactivateRules 필수 */
    @DeleteMapping("/core/compliance/applications/{application-id}")
    public ResponseEntity<Void> unapply(@PathVariable("application-id") long applicationId,
                                        @RequestParam(required = false) Boolean deactivateRules) {
        applications.delete(applicationId, deactivateRules);
        return ResponseEntity.noContent().build();
    }

    /** API-CMP-08 측정 가능성 — CMP_READ */
    @GetMapping("/core/compliance/applications/{application-id}/measurability")
    public ApiResponse<List<MeasurabilityItem>> measurability(@PathVariable("application-id") long applicationId) {
        return ApiResponse.success(applications.measurability(applicationId));
    }

    /** API-CMP-09 공간 유효 기준 — CMP_READ */
    @GetMapping("/core/compliance/spaces/{space-id}/effective-criteria")
    public ApiResponse<List<CriterionView>> effectiveCriteria(@PathVariable("space-id") long spaceId,
                                                              @RequestParam(required = false) String at) {
        return ApiResponse.success(applications.effectiveCriteria(spaceId, at));
    }
}
