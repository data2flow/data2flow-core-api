package net.java21.data2flow.core.dataexchange.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 내보내기·가져오기·정기 내보내기 오류 코드(spec/detail/TSD/domain-model.md §5·§8, design/api/TSD-api.md §2·§3).
 * 문구는 {@code i18n/dataexchange*.properties}의 {@code error.<코드>}.
 */
public enum ExchangeErrorCode implements ErrorCode {
    EXPORT_NOT_FOUND(404),
    /** 7일 보관이 지났거나 다운로드 링크(1시간)가 지남 */
    EXPORT_EXPIRED(410),
    /** 사용자당 진행 중인 비동기 작업 3개 초과(UC-TSD-04) */
    EXPORT_LIMIT_EXCEEDED(429),
    /** S3·SFTP에 쓸 수 없음(TSD-04.03·07.02, AT-TSD-17.4) */
    EXPORT_TARGET_UNWRITABLE(502),
    /** 끝난 작업은 취소할 수 없음 */
    EXPORT_STATE_CONFLICT(409),
    IMPORT_NOT_FOUND(404),
    IMPORT_FILE_INVALID(400),
    IMPORT_LIMIT_EXCEEDED(400),
    IMPORT_SOURCE_UNREACHABLE(502),
    /** 미리 실행이 끝나지 않았거나 이미 실행한 작업 */
    IMPORT_STATE_CONFLICT(409);

    private final int httpStatus;

    ExchangeErrorCode(int httpStatus) {
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
