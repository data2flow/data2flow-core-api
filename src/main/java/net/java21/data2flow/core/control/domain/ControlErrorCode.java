package net.java21.data2flow.core.control.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 제어(ACT) 오류 코드(spec/detail/ACT/domain-model.md §5, 00-error-codes.md). 문구는 {@code i18n/control*.properties}.
 * 명령 검증 결과 코드(COMMAND_*·CAPABILITY_NOT_SUPPORTED 등)는 action 제어 창구가 내고 core는 그대로 전한다. 여기 둔 것은 core가
 * 직접 판정하는 경우(기능 카탈로그·드라이버·조직 한계 설정)와 화면 문구용이다.
 */
public enum ControlErrorCode implements ErrorCode {
    DEVICE_NOT_CONTROLLABLE(409),
    CAPABILITY_NOT_SUPPORTED(400),
    CAPABILITY_NAME_RESERVED(409),
    CAPABILITY_NOT_FOUND(404),
    COMMAND_ARGS_INVALID(400),
    COMMAND_ARG_OUT_OF_RANGE(400),
    COMMAND_ABSOLUTE_LIMIT(400),
    COMMAND_BLOCKED(409),
    COMMAND_NOT_FOUND(404),
    COMMAND_NOT_CANCELLABLE(409),
    ACT_SANDBOX_FORBIDDEN(403),
    LIMIT_WIDER_THAN_MODEL(400),
    DRIVER_NOT_FOUND(404),
    DRIVER_IN_USE(409),
    DRIVER_CONTRACT_FAILED(502),
    DRIVER_HEALTHCHECK_FAILED(502),
    DRIVER_CAPABILITY_MISMATCH(400),
    // M4: 일괄·장면·예약·인터락·비상 정지
    COMMAND_BULK_LIMIT_EXCEEDED(400),
    SCENE_NOT_FOUND(404),
    SCENE_ITEM_LIMIT_EXCEEDED(400),
    SCHEDULE_INVALID(400),
    INTERLOCK_INVALID(400),
    EMERGENCY_STOP_ACTIVE(409);

    private final int httpStatus;

    ControlErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
