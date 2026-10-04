package net.java21.data2flow.core.common;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.ErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;

import java.util.List;

/**
 * 실패 응답에 {@code response}를 함께 싣는 업무 예외(api-rules §3.2 "실패에도 결과 일부가 필요한 경우"). 예: FLOW_VALIDATION_FAILED는
 * {@code errors[{field, code, message}]}와 함께 {@code response{errors[], warnings[]}}(노드·연결선 단위 검증 결과)를 준다(API-FLW-07).
 */
public class FailureWithResponse extends BusinessException {

    private final transient Object response;

    public FailureWithResponse(ErrorCode code, List<FieldErrorDetail> errors, Object response) {
        super(code, errors);
        this.response = response;
    }

    /** 문구 인자가 있는 경우(예: COMMISSION_CONFLICT "{0}이(가) {1}에 설치") */
    public FailureWithResponse(ErrorCode code, List<FieldErrorDetail> errors, Object response, Object... args) {
        super(code, errors, args);
        this.response = response;
    }

    public Object response() {
        return response;
    }
}
