package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.script.domain.ScriptErrorCode;
import net.java21.data2flow.core.script.dto.ScriptDtos.Applied;
import net.java21.data2flow.core.script.dto.ScriptDtos.AppliedInstance;
import net.java21.data2flow.core.script.dto.ScriptDtos.Problem;
import net.java21.data2flow.core.script.dto.ScriptDtos.StaticCheck;
import net.java21.data2flow.core.script.repository.ScriptRepository;
import net.java21.data2flow.core.script.repository.ScriptRepository.ScriptRow;
import net.java21.data2flow.core.script.repository.ScriptRuntimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 스크립트 서비스들이 함께 쓰는 도우미: 조회·JSON 변환·설정 변경 발행(EVT-SCR-01)·적용 상태·정적 검사 대체값 */
@Component
public class ScriptSupport {

    /** 적용 대상 인스턴스 수를 셀 때 보는 기간(이 안에 한 번이라도 적용 보고한 pipeline 인스턴스) */
    static final Duration INSTANCE_WINDOW = Duration.ofHours(24);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() { };
    private static final Logger log = LoggerFactory.getLogger(ScriptSupport.class);

    private final ScriptRepository scripts;
    private final ScriptRuntimeRepository runtime;
    private final PipelineScriptClient pipeline;
    private final CoreEventPublisher publisher;
    private final ConfigVersions configVersions;
    private final JsonMapper json;
    private final Clock clock;

    public ScriptSupport(ScriptRepository scripts, ScriptRuntimeRepository runtime, PipelineScriptClient pipeline,
                         CoreEventPublisher publisher, ConfigVersions configVersions, JsonMapper json, Clock clock) {
        this.scripts = scripts;
        this.runtime = runtime;
        this.pipeline = pipeline;
        this.publisher = publisher;
        this.configVersions = configVersions;
        this.json = json;
        this.clock = clock;
    }

    /** 조직의 스크립트. 없으면(다른 조직 포함) 404 SCRIPT_NOT_FOUND */
    public ScriptRow require(long organizationId, long scriptId) {
        return scripts.findById(organizationId, scriptId).orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_NOT_FOUND));
    }

    /** 같은 트랜잭션 안에서 행 잠금 */
    public ScriptRow lock(long organizationId, long scriptId) {
        return scripts.lockById(organizationId, scriptId).orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_NOT_FOUND));
    }

    /**
     * 실행 묶음이 바뀌는 변경(배포·롤백·연결·활성 전환·삭제): EVT-SCR-01 {@code ConfigChangedMessage(entityType=SCRIPT)}을 아웃박스에 쓰고
     * {@code SCRIPTS} 설정 버전을 올린다(API-SCR-32 sinceVersion). 받은 pipeline은 묶음을 다시 읽어 10초 안에 적용한다(SCR-03.04).
     */
    public void runtimeChanged(long organizationId, long scriptId, long version, boolean deleted) {
        if (deleted) {
            publisher.configDeleted(EntityType.SCRIPT, scriptId, version, organizationId);
        } else {
            publisher.configChanged(EntityType.SCRIPT, scriptId, version, organizationId);
        }
        configVersions.bump(organizationId, ConfigVersions.SCRIPTS);
    }

    /**
     * pipeline 정적 검사(API-SCR-30). pipeline이 응답하지 않으면 저장은 하되 "검사 못 함" 오류 하나로 기록해 배포를 막는다
     * (UC-SCR-01 5a: 검사 실패여도 저장은 됨, BR-SCR-05: 오류가 있으면 배포 불가). 다시 저장하면 다시 검사한다.
     */
    public StaticCheck checkOrUnavailable(long organizationId, String kind, String code) {
        try {
            return pipeline.check(organizationId, kind, code, null);
        } catch (BusinessException ex) {
            log.warn("정적 검사를 하지 못해 검사 실패로 저장합니다(org={})", organizationId);
            return new StaticCheck(false, List.of(new Problem(1, 1, "ERROR", "SCRIPT_CHECK_UNAVAILABLE",
                    "정적 검사를 하지 못했습니다. 잠시 뒤 다시 저장하세요")));
        }
    }

    /** 버전의 적용 보고 모음 */
    public Applied applied(long organizationId, long versionId) {
        List<AppliedInstance> instances = runtime.listAcks(organizationId, versionId).stream()
                .map(a -> new AppliedInstance(a.instance(), a.appliedAt())).toList();
        long known = runtime.countInstancesSince(organizationId, clock.instant().minus(INSTANCE_WINDOW));
        return new Applied(instances.size(), Math.max(known, instances.size()), instances);
    }

    public StaticCheck staticCheck(String raw) {
        if (raw == null) {
            return new StaticCheck(false, List.of());
        }
        return json.readValue(raw, StaticCheck.class);
    }

    public String write(Object value) {
        return json.writeValueAsString(value);
    }

    public Map<String, Object> map(String raw) {
        return raw == null ? new LinkedHashMap<>() : json.readValue(raw, MAP);
    }

    public List<Map<String, Object>> list(String raw) {
        return raw == null ? List.of() : json.readValue(raw, LIST);
    }

    public JsonMapper json() {
        return json;
    }

    public static String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    /** 문자열 ID → long. 숫자가 아니면 400 INVALID_REQUEST(errors[field]) */
    public static long parseId(String raw, String field) {
        try {
            long value = Long.parseLong(raw == null ? "" : raw.strip());
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException ex) {
            throw net.java21.data2flow.core.script.domain.ScriptModels.invalid(field, "Pattern");
        }
    }
}
