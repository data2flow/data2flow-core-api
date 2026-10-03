package net.java21.data2flow.core.space.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 공간·평면도·목표·시간표·시맨틱 오류 코드(spec/detail/DEV/domain-model.md §5·§8). 문구는 {@code i18n/space*.properties}.
 * {@code DEVICE_NOT_FOUND}·{@code METRIC_NOT_FOUND}는 기기·측정 항목 기능과 같은 코드(같은 뜻·같은 상태)를 이 패키지에서도 쓴다.
 * {@code SPACE_CODE_DUPLICATE}는 문서에 없던 코드다(공간 코드는 조직 안 고유, domain-model §2.1).
 */
public enum SpaceErrorCode implements ErrorCode {
    SPACE_NOT_FOUND(404),
    SPACE_DEPTH_EXCEEDED(400),
    SPACE_TYPE_INVALID(400),
    SPACE_NAME_DUPLICATE(409),
    SPACE_CODE_DUPLICATE(409),
    SPACE_NOT_EMPTY(409),
    SPACE_MOVE_CYCLE(400),
    SCHEDULE_OVERLAP(400),
    FLOORPLAN_IMAGE_INVALID(400),
    SEMANTIC_TAG_UNKNOWN(400),
    DEVICE_NOT_FOUND(404),
    METRIC_NOT_FOUND(404);

    private final int httpStatus;

    SpaceErrorCode(int httpStatus) {
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
