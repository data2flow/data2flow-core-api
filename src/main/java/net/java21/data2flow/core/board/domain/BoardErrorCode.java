package net.java21.data2flow.core.board.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 사용자 정의 대시보드·공유 링크·브랜딩·IFC 모델 오류 코드(spec/detail/DSH/test-plan.md 오류 표). 문구는 {@code i18n/board*.properties}의
 * {@code error.<코드>}. MODEL_FILE_INVALID는 크기 초과면 413, 형식 오류면 400이라 상수 둘이 같은 코드를 쓴다.
 */
public enum BoardErrorCode implements ErrorCode {
    DASHBOARD_NOT_FOUND(404),
    DASHBOARD_VERSION_CONFLICT(409),
    DASHBOARD_LAYOUT_INVALID(400),
    WIDGET_TYPE_UNSUPPORTED(400),
    WIDGET_QUERY_INVALID(400),
    WIDGET_DATA_FORBIDDEN(403),
    SHARE_LINK_INVALID(404),
    BRANDING_ASSET_INVALID(400),
    MODEL_FILE_INVALID(400),
    MODEL_FILE_TOO_LARGE(413) {
        @Override
        public String code() {
            return "MODEL_FILE_INVALID";
        }
    },
    SPACE_NOT_FOUND(404);

    private final int httpStatus;

    BoardErrorCode(int httpStatus) {
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
