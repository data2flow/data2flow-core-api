package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.script.domain.ScriptErrorCode;
import net.java21.data2flow.core.script.domain.ScriptModels;
import net.java21.data2flow.core.script.domain.ScriptModels.ScriptKind;
import net.java21.data2flow.core.script.domain.ScriptModels.VersionStatus;
import net.java21.data2flow.core.script.dto.ScriptDtos.CheckRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.DeployRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.DeployResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.SaveDraftRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.SaveDraftResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.StaticCheck;
import net.java21.data2flow.core.script.domain.ScriptM5Rules;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ReprocessRequestBody;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ReprocessSuggestion;
import net.java21.data2flow.core.script.repository.ScriptBindingRepository;
import net.java21.data2flow.core.script.repository.ScriptBindingRepository.BindingRow;
import net.java21.data2flow.core.script.repository.ScriptOpsRepository;
import net.java21.data2flow.core.script.repository.ScriptRepository;
import net.java21.data2flow.core.script.repository.ScriptRepository.ScriptRow;
import net.java21.data2flow.core.script.repository.ScriptVersionRepository;
import net.java21.data2flow.core.script.repository.ScriptVersionRepository.VersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 버전 수명 주기(SCR-03.04, SCR-04.03, SCR-04.05, BR-SCR-09·10·14): DRAFT 저장 API-SCR-03, 배포·롤백 API-SCR-05, 정적 검사 API-SCR-07.
 *
 * <pre>
 * DRAFT ──(배포: 검사 통과)──▶ ACTIVE ──(다른 버전 배포·롤백)──▶ ARCHIVED ──(롤백)──▶ ACTIVE
 * DRAFT ──(편집 저장)──▶ DRAFT(같은 행)
 * </pre>
 *
 * 배포는 "이전 ACTIVE → ARCHIVED, 대상 → ACTIVE, scripts.active_version_id"를 한 트랜잭션에서 바꾸고 EVT-SCR-01을 아웃박스로 낸다.
 * pipeline은 받은 즉시 API-SCR-32로 묶음을 다시 읽어 10초 안에 적용하고 API-SCR-34로 보고한다. 배포 응답은 기다리지 않고
 * 그 시점의 적용 보고(applied)를 싣는다 — 화면은 상세(API-SCR-04 activeVersion.applied)를 다시 읽어 "2/2 적용"을 갱신한다.
 * DRAFT 배포 전에는 테스트 케이스를 모두 실행한다(SCR-03.03, 실패 시 400 SCRIPT_TEST_FAILED, ADMIN force면 통과). 응답에는 재처리 제안
 * (SCR-03.06)을 싣는다.
 */
@Service
public class ScriptVersionService {

    static final String AUDIT_VERSION_SAVED = "SCRIPT_VERSION_SAVED";
    static final String AUDIT_DEPLOYED = "SCRIPT_DEPLOYED";
    static final String AUDIT_ROLLED_BACK = "SCRIPT_ROLLED_BACK";
    static final String AUDIT_FORCE_DEPLOYED = "SCRIPT_FORCE_DEPLOYED";
    /** 배포 후 재처리 제안 기본 기간(SCR-03.06) */
    static final java.time.Duration REPROCESS_DEFAULT_PERIOD = java.time.Duration.ofDays(7);
    static final int REPROCESS_MAX_DEVICES = 1000;

    private final ScriptRepository scripts;
    private final ScriptVersionRepository versions;
    private final ScriptSupport support;
    private final PipelineScriptClient pipeline;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ScriptTestCaseService testCases;
    private final ScriptBindingRepository bindings;
    private final ScriptOpsRepository ops;

    public ScriptVersionService(ScriptRepository scripts, ScriptVersionRepository versions, ScriptSupport support,
                                PipelineScriptClient pipeline, RoleChecker roleChecker, Audits audits, TransactionTemplate tx,
                                Clock clock, ScriptTestCaseService testCases, ScriptBindingRepository bindings, ScriptOpsRepository ops) {
        this.testCases = testCases;
        this.bindings = bindings;
        this.ops = ops;
        this.scripts = scripts;
        this.versions = versions;
        this.support = support;
        this.pipeline = pipeline;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * API-SCR-03 DRAFT 저장. 코드 64KB(UTF-8) 초과 400 SCRIPT_CODE_TOO_LARGE, baseVersionNo가 마지막 버전 번호와 다르면 409
     * SCRIPT_VERSION_CONFLICT. DRAFT가 있으면 그 행을 고치고, 없으면 마지막 번호 + 1로 새 DRAFT를 만든다. 정적 검사(pipeline API-SCR-30)
     * 결과를 함께 저장하고 검사 실패여도 저장한다(배포만 막힘). 50개를 넘으면 가장 오래된 ARCHIVED를 지운다(SCR-04.03).
     */
    public SaveDraftResponse saveDraft(long scriptId, SaveDraftRequest request) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ScriptRow script = support.require(orgId, scriptId);
        String code = request.code();
        if (code == null && request.copyFromVersionId() != null) {
            long fromId = ScriptSupport.parseId(request.copyFromVersionId(), "copyFromVersionId");
            code = versions.findById(orgId, scriptId, fromId).map(VersionRow::code)
                    .orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_NOT_FOUND));
        }
        String checkedCode = ScriptModels.requireCodeSize(code);
        StaticCheck check = support.checkOrUnavailable(orgId, script.kind(), checkedCode);
        return tx.execute(status -> {
            support.lock(orgId, scriptId);
            int latest = versions.maxVersionNo(orgId, scriptId);
            if (request.baseVersionNo() != latest) {
                throw new BusinessException(ScriptErrorCode.SCRIPT_VERSION_CONFLICT);
            }
            VersionRow draft = versions.findDraft(orgId, scriptId).orElse(null);
            long versionId;
            int versionNo;
            if (draft != null) {
                versions.updateDraft(orgId, draft.id(), checkedCode, ScriptModels.sha256(checkedCode), support.write(check),
                        user.userId(), clock.instant());
                versionId = draft.id();
                versionNo = draft.versionNo();
            } else {
                versionNo = latest + 1;
                versionId = versions.insertDraft(orgId, scriptId, versionNo, checkedCode, ScriptModels.sha256(checkedCode),
                        support.write(check), user.userId(), clock.instant());
                versions.deleteOldArchived(orgId, scriptId, ScriptModels.KEEP_VERSIONS);
            }
            // 가져오는 공유 모듈(SCR-04.01): 코드의 'module:이름@버전'을 버전에 기록한다(사용처·실행 묶음 moduleRefs)
            ops.updateModuleRefs(orgId, versionId, support.write(ScriptM5Rules.moduleRefs(checkedCode)));
            audits.record(audits.event(orgId, AUDIT_VERSION_SAVED).actor(user).target("SCRIPT", Long.toString(scriptId))
                    .detail("versionId", Long.toString(versionId)).detail("versionNo", versionNo).detail("staticCheckOk", check.ok())
                    .detail("bytes", checkedCode.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
            return new SaveDraftResponse(Long.toString(versionId), versionNo, check);
        });
    }

    /** API-SCR-07 정적 검사(편집 중 호출, 저장 없음). pipeline이 없으면 503 */
    public StaticCheck check(CheckRequest request) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        long orgId = roleChecker.currentUser().organizationId();
        ScriptKind kind = ScriptModels.kind(request.kind());
        return pipeline.check(orgId, kind.name(), ScriptModels.requireCodeSize(request.code()), request.moduleRefs());
    }

    /**
     * API-SCR-05 배포·롤백. 사람만(장기 토큰 403, BR-IAM-19), SCRIPT_WRITE, force는 ADMIN(SCRIPT_FORCE_DEPLOY 권한 이름은 contracts
     * Permission에 없어 ADMIN 판정으로 대신한다). baseActiveVersionId가 현재 ACTIVE와 다르면 409 SCRIPT_VERSION_CONFLICT.
     * DRAFT 배포는 정적 검사 통과가 필요하다(400 SCRIPT_STATIC_CHECK_FAILED, force로도 넘을 수 없음 — BR-SCR-05).
     * ARCHIVED 버전을 고르면 롤백(새 버전을 만들지 않고 그 행을 다시 ACTIVE로).
     */
    public DeployResponse deploy(long scriptId, DeployRequest request) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        roleChecker.requireInteractive();
        boolean force = Boolean.TRUE.equals(request.force());
        if (force) {
            roleChecker.requireAdmin();
            if (request.forceReason() == null || request.forceReason().strip().length() < 2) {
                throw ScriptModels.invalid("forceReason", "NotBlank");
            }
        }
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        long versionId = ScriptSupport.parseId(request.versionId(), "versionId");
        Long baseActive = request.baseActiveVersionId() == null || request.baseActiveVersionId().isBlank() ? null
                : ScriptSupport.parseId(request.baseActiveVersionId(), "baseActiveVersionId");
        String memo = request.memo().strip();
        if (memo.length() < 2) {
            throw ScriptModels.invalid("memo", "Size");
        }
        // 배포 전 확인(API-SCR-05 처리 순서): 정적 검사 → 테스트 케이스 전체 실행(SCR-03.03). 롤백(ARCHIVED)은 검증된 버전이라 건너뛴다
        ScriptRow current = support.require(orgId, scriptId);
        VersionRow candidate = versions.findById(orgId, scriptId, versionId)
                .orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_NOT_FOUND));
        Map<String, Object> checked;
        if (VersionStatus.DRAFT.name().equals(candidate.status())) {
            if (!support.staticCheck(candidate.staticCheck()).ok()) {
                throw new BusinessException(ScriptErrorCode.SCRIPT_STATIC_CHECK_FAILED);
            }
            checked = testCases.guardDeploy(orgId, scriptId, current.kind(), candidate.code(), force);
        } else {
            checked = new LinkedHashMap<>();
            checked.put("passed", 0);
            checked.put("failed", 0);
        }
        Map<String, Object> testResult = checked;
        DeployResponse response = tx.execute(status -> {
            ScriptRow script = support.lock(orgId, scriptId);
            if (!Objects.equals(baseActive, script.activeVersionId())) {
                throw new BusinessException(ScriptErrorCode.SCRIPT_VERSION_CONFLICT);
            }
            VersionRow target = versions.findById(orgId, scriptId, versionId)
                    .orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_NOT_FOUND));
            VersionStatus from = VersionStatus.valueOf(target.status());
            if (from == VersionStatus.ACTIVE) {
                throw new BusinessException(ScriptErrorCode.SCRIPT_VERSION_CONFLICT);
            }
            if (from == VersionStatus.DRAFT && !support.staticCheck(target.staticCheck()).ok()) {
                throw new BusinessException(ScriptErrorCode.SCRIPT_STATIC_CHECK_FAILED);
            }
            if (target.code() != null && !target.code().equals(candidate.code())) {
                // 테스트한 뒤 다른 사용자가 DRAFT를 고쳤다
                throw new BusinessException(ScriptErrorCode.SCRIPT_VERSION_CONFLICT);
            }
            VersionRow previous = script.activeVersionId() == null ? null
                    : versions.findById(orgId, scriptId, script.activeVersionId()).orElse(null);
            versions.archiveActive(orgId, scriptId, clock.instant());
            versions.activate(orgId, versionId, memo, force, force ? request.forceReason().strip() : null, support.write(testResult),
                    user.userId(), clock.instant());
            int version = scripts.updateActiveVersion(orgId, scriptId, versionId, user.userId(), clock.instant());
            versions.deleteOldArchived(orgId, scriptId, ScriptModels.KEEP_VERSIONS);
            support.runtimeChanged(orgId, scriptId, version, false);
            boolean rollback = from == VersionStatus.ARCHIVED;
            String action = force ? AUDIT_FORCE_DEPLOYED : rollback ? AUDIT_ROLLED_BACK : AUDIT_DEPLOYED;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fromVersionNo", previous == null ? null : previous.versionNo());
            detail.put("toVersionNo", target.versionNo());
            detail.put("versionId", Long.toString(versionId));
            detail.put("memo", memo);
            if (force) {
                detail.put("forceReason", request.forceReason().strip());
                detail.put("rollback", rollback);
            }
            audits.record(audits.event(orgId, action).actor(user).target("SCRIPT", Long.toString(scriptId)).detail(detail));
            return new DeployResponse(Long.toString(versionId), target.versionNo(), support.applied(orgId, versionId), testResult,
                    null);
        });
        return new DeployResponse(response.activeVersionId(), response.versionNo(), response.applied(), response.testResult(),
                reprocessSuggestion(orgId, scriptId, current.name(), response.versionNo()));
    }

    /**
     * 배포 후 재처리 제안(SCR-03.06, TC-SCR-061): 연결 대상(소스·모델의 기기·기기)과 지난 7일. 그대로 재처리 작업 생성(API-ING-10)
     * 본문으로 쓸 수 있다. 화면은 배포 완료 토스트의 [지난 데이터 재처리]에서 미리 채운다. 연결이 없으면 빈 목록
     */
    ReprocessSuggestion reprocessSuggestion(long orgId, long scriptId, String scriptName, int versionNo) {
        java.time.Instant to = clock.instant();
        java.time.Instant from = to.minus(REPROCESS_DEFAULT_PERIOD);
        String fullMemo = "스크립트 " + scriptName + " v" + versionNo + " 배포 후 재처리";
        String memo = fullMemo.length() > 200 ? fullMemo.substring(0, 200) : fullMemo;
        List<ReprocessRequestBody> requests = new ArrayList<>();
        java.util.Set<Long> sources = new java.util.LinkedHashSet<>();
        java.util.Set<Long> deviceIds = new java.util.LinkedHashSet<>();
        for (BindingRow b : bindings.findByScript(orgId, scriptId)) {
            if (!b.enabled()) {
                continue;
            }
            switch (b.targetType()) {
                case "SOURCE" -> {
                    if (b.targetId().matches("\\d{1,18}")) {
                        sources.add(Long.valueOf(b.targetId()));
                    }
                }
                case "DEVICE" -> {
                    if (b.targetId().matches("\\d{1,18}")) {
                        deviceIds.add(Long.valueOf(b.targetId()));
                    }
                }
                case "MODEL" -> deviceIds.addAll(ops.findDeviceIdsByModelCode(orgId, b.targetId(), REPROCESS_MAX_DEVICES));
                default -> {
                }
            }
        }
        sources.forEach(source -> requests.add(new ReprocessRequestBody(source, null, from, to, memo)));
        // 재처리는 소스 단위(API-ING-10 sourceId 필수): 기기 연결은 소스마다 묶는다. 소스 전체를 재처리하면 그 소스 기기는 뺀다
        java.util.Map<Long, List<Long>> bySource = new java.util.LinkedHashMap<>();
        ops.findDeviceSources(orgId, deviceIds).forEach((device, source) -> {
            if (!sources.contains(source)) {
                bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(device);
            }
        });
        bySource.forEach((source, devices) -> requests.add(new ReprocessRequestBody(source,
                devices.stream().limit(REPROCESS_MAX_DEVICES).toList(), from, to, memo)));
        return new ReprocessSuggestion(from, to, requests);
    }
}
