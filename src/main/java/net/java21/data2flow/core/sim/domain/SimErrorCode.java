package net.java21.data2flow.core.sim.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 가상 환경(SIM) 오류 코드(spec/detail/SIM/domain-model.md 오류 표). 문구는 {@code i18n/sim*.properties}. 실행·시나리오·장애의 판정
 * 오류(SIM_RUN_STATE_CONFLICT 등)는 simulator가 내고 core는 그대로 전한다. 여기 둔 것은 core가 판정하는 것(권한 범위·기준 정보·한도)이다.
 */
public enum SimErrorCode implements ErrorCode {
    SIM_NOT_FOUND(404),
    SIM_SANDBOX_VIOLATION(403),
    SIM_DEVICE_QUOTA_EXCEEDED(409),
    SIM_TARGET_NOT_VIRTUAL(400),
    SIM_SPACE_BUSY(409),
    SIM_RUN_STATE_CONFLICT(409),
    SIM_CONCURRENT_RUN_LIMIT(409),
    SIM_ACCELERATION_LIMIT(409),
    SIM_PROPERTY_OUT_OF_RANGE(400),
    SIM_SCENARIO_INVALID(400),
    SIM_IMPORT_INVALID(400),
    SIM_PROFILE_IN_USE(409),
    SIM_TYPE_IN_USE(409),
    SIM_PLATFORM_BROKER_UNAVAILABLE(409),
    SIM_WEATHER_DATA_MISSING(409);

    private final int httpStatus;

    SimErrorCode(int httpStatus) {
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
