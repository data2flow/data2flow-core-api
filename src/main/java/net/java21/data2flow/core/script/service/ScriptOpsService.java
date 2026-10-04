package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.script.domain.ScriptErrorCode;
import net.java21.data2flow.core.script.domain.ScriptM5Rules;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ConfigRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ConfigResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.DeployMark;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ErrorSnapshot;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.LogCaptureRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.LogCaptureResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.LogLine;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.StatsResponse;
import net.java21.data2flow.core.script.repository.ScriptOpsRepository;
import net.java21.data2flow.core.script.repository.ScriptOpsRepository.ConfigResult;
import net.java21.data2flow.core.script.repository.ScriptRepository.ScriptRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 스크립트 운영(SCR-03.05·04.02·05.01·05.02): 지표 API-SCR-12(pipeline API-SCR-36 + 배포 표시), 오류 스냅샷 API-SCR-13, 운영 로그 수집
 * API-SCR-14(30분, BR-SCR-17), 설정값 API-SCR-22(새 버전 없이 즉시 반영, 설정 판 +1, BR-SCR-12). 지표·오류·로그는 pipeline이 쓰는 표를
 * 읽기만 한다(conventions §6).
 */
@Service
public class ScriptOpsService {

    static final String AUDIT_CONFIG = "SCRIPT_CONFIG_CHANGED";
    static final String AUDIT_LOG_CAPTURE = "SCRIPT_LOG_CAPTURE_CHANGED";
    /** API-SCR-36 기간 한도 */
    static final Duration MAX_STATS_RANGE = Duration.ofDays(90);

    private final RoleChecker roleChecker;
    private final ScriptSupport support;
    private final ScriptOpsRepository ops;
    private final PipelineScriptOpsClient pipeline;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public ScriptOpsService(RoleChecker roleChecker, ScriptSupport support, ScriptOpsRepository ops, PipelineScriptOpsClient pipeline,
                            Audits audits, JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.support = support;
        this.ops = ops;
        this.pipeline = pipeline;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-SCR-12 지표(기간 기본 최근 24시간, 최대 90일, step 1m|1h) */
    public StatsResponse stats(long scriptId, Instant from, Instant to, String step) {
        roleChecker.require(Permission.SCRIPT_READ);
        long org = roleChecker.currentUser().organizationId();
        support.require(org, scriptId);
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(24)) : from;
        String s = step == null || step.isBlank() ? "1m" : step.strip();
        if (!s.equals("1m") && !s.equals("1h")) {
            throw invalid("step", "Enum");
        }
        if (!end.isAfter(start) || Duration.between(start, end).compareTo(MAX_STATS_RANGE) > 0) {
            throw invalid("from", "Range");
        }
        JsonNode r = pipeline.stats(org, scriptId, start, end, s);
        List<DeployMark> marks = ops.findDeployMarks(org, scriptId, start, end).stream()
                .map(m -> new DeployMark(m.versionNo(), m.at())).toList();
        return new StatsResponse(r.path("points").isArray() ? r.get("points") : json.createArrayNode(),
                r.path("warnings").isArray() ? r.get("warnings") : json.createArrayNode(), marks);
    }

    /** API-SCR-13 오류 스냅샷(최근부터). 입력 원문(inputSnapshot)은 SCRIPT_WRITE만 */
    @Transactional(readOnly = true)
    public ListApiResponse<ErrorSnapshot> errors(long scriptId, Integer page, Integer size) {
        roleChecker.require(Permission.SCRIPT_READ);
        long org = roleChecker.currentUser().organizationId();
        support.require(org, scriptId);
        boolean withInput = roleChecker.has(Permission.SCRIPT_WRITE);
        PageParams params = PageParams.of(page, size);
        List<ErrorSnapshot> items = ops.findErrors(org, scriptId, params.size(), params.offset()).stream()
                .map(e -> new ErrorSnapshot(Long.toString(e.id()), e.occurredAt(), e.versionNo(), e.errorCode(), e.message(), e.line(),
                        e.col(), e.deviceId() == null ? null : Long.toString(e.deviceId()),
                        withInput && e.inputSnapshot() != null ? json.readTree(e.inputSnapshot()) : null))
                .toList();
        return ListApiResponse.of(params, items, ops.countErrors(org, scriptId));
    }

    /** API-SCR-14 운영 로그 목록 */
    @Transactional(readOnly = true)
    public ListApiResponse<LogLine> logs(long scriptId, Instant from, Integer page, Integer size) {
        roleChecker.require(Permission.SCRIPT_READ);
        long org = roleChecker.currentUser().organizationId();
        support.require(org, scriptId);
        PageParams params = PageParams.of(page, size);
        List<LogLine> items = ops.findLogs(org, scriptId, from, params.size(), params.offset()).stream()
                .map(l -> new LogLine(l.at(), l.versionNo(), l.deviceId() == null ? null : Long.toString(l.deviceId()), l.message()))
                .toList();
        return ListApiResponse.of(params, items, ops.countLogs(org, scriptId, from));
    }

    /**
     * API-SCR-14 로그 수집 켜기·끄기. 켜면 지금부터 30분(켜져 있으면 연장), 끄면 바로 끝. 실행 묶음의 logCaptureUntil이 바뀌므로
     * EVT-SCR-01을 낸다(pipeline은 묶음을 다시 읽어 그 시각까지 초당 10건을 모은다)
     */
    @Transactional
    public LogCaptureResponse logCapture(long scriptId, LogCaptureRequest request) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        if (request == null || request.enabled() == null) {
            throw invalid("enabled", "NotNull");
        }
        ScriptRow script = support.lock(org, scriptId);
        Instant now = clock.instant();
        Instant until = request.enabled() ? now.plus(ScriptM5Rules.LOG_CAPTURE) : null;
        ops.updateLogCapture(org, scriptId, until);
        support.runtimeChanged(org, scriptId, script.version(), false);
        audits.record(audits.event(org, AUDIT_LOG_CAPTURE).actor(user).target("SCRIPT", Long.toString(scriptId))
                .detail("enabled", request.enabled()).detail("until", until == null ? null : until.toString()));
        return new LogCaptureResponse(request.enabled(), until);
    }

    /**
     * API-SCR-22 설정값 변경(SCR-04.02, AT-SCR-08.2·08.3): 새 버전을 만들지 않고 저장, 설정 판 +1, EVT-SCR-01. 비밀값처럼 보이는 이름은
     * 400 SCRIPT_CONFIG_SECRET_FORBIDDEN, 기준 버전이 다르면 409 SCRIPT_VERSION_CONFLICT
     */
    @Transactional
    public ConfigResponse updateConfig(long scriptId, ConfigRequest request) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        if (request == null || request.baseVersion() == null) {
            throw invalid("baseVersion", "NotNull");
        }
        ScriptM5Rules.validateConfig(request.config());
        ScriptRow before = support.lock(org, scriptId);
        ConfigResult result = ops.updateConfig(org, scriptId, request.baseVersion(), json.writeValueAsString(request.config()),
                        user.userId(), clock.instant())
                .orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_VERSION_CONFLICT));
        support.runtimeChanged(org, scriptId, result.version(), false);
        audits.record(audits.event(org, AUDIT_CONFIG).actor(user).target("SCRIPT", Long.toString(scriptId))
                .detail("before", support.map(before.config())).detail("after", support.map(json.writeValueAsString(request.config())))
                .detail("configRevision", result.configRevision()));
        return new ConfigResponse(Long.toString(scriptId), support.map(json.writeValueAsString(request.config())), result.version(),
                result.configRevision());
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
