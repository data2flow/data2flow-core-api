package net.java21.data2flow.core.flow.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 플로우 상태·버전 상태 규칙(FLW domain-model "flows.status"·"flow_versions.state", BR-FLW-16).
 *
 * <pre>
 * flows.status: DRAFT → ACTIVE(적용) · ACTIVE ↔ PAUSED · ACTIVE → DEGRADED(엔진) → ACTIVE · ACTIVE·PAUSED·DEGRADED → DISABLED → ACTIVE(새 적용)
 *               DRAFT·DISABLED → DELETED
 * flow_versions.state: DRAFT → (PENDING_APPROVAL) → ACTIVE | REJECTED · ACTIVE → ARCHIVED · ARCHIVED → ACTIVE(롤백)
 * </pre>
 */
public final class FlowModels {

    /** 조직당 활성 플로우 한도(BR-FLW-16) */
    public static final int MAX_ACTIVE_FLOWS = 500;
    /** 버전 보관 한도(ACTIVE와 직전 버전은 지우지 않는다) */
    public static final int MAX_VERSIONS = 100;
    /** 상태 전이(동작 → 허용하는 현재 상태 → 다음 상태) */
    public static final Map<String, Set<String>> FROM = Map.of(
            "pause", Set.of("ACTIVE", "DEGRADED"),
            "resume", Set.of("PAUSED"),
            "disable", Set.of("ACTIVE", "PAUSED", "DEGRADED"),
            "delete", Set.of("DRAFT", "DISABLED"));
    public static final Map<String, String> TO = Map.of("pause", "PAUSED", "resume", "ACTIVE", "disable", "DISABLED", "delete", "DELETED");
    /** 엔진이 실행하는 상태(활성 한도에 세는 상태) */
    public static final Set<String> RUNNING = Set.of("ACTIVE", "PAUSED", "DEGRADED");
    public static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "PAUSED", "DEGRADED", "DISABLED");
    public static final Set<String> KINDS = Set.of("FLOW", "RULE", "CATCH");
    public static final Set<String> ENVIRONMENTS = Set.of("PROD", "TEST");

    private FlowModels() {
    }

    public static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
