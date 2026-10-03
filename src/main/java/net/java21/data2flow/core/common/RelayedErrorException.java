package net.java21.data2flow.core.common;

import tools.jackson.databind.JsonNode;

/**
 * 내부 API(action·simulator·flow-engine)가 돌려준 4xx 오류를 그대로 화면에 전한다({@link InternalHttp}). 본문은 공통 오류 봉투
 * {@code {header, errors?, response?}}이고 상태 코드도 그대로다. 예: 거부된 명령의 {@code response.commandId}(API-ACT-01),
 * {@code SIM_SCENARIO_INVALID}의 {@code errors[].field}(API-SIM-12).
 */
public class RelayedErrorException extends RuntimeException {

    private final int status;
    private final transient JsonNode body;

    public RelayedErrorException(int status, JsonNode body) {
        super("relayed " + status);
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public JsonNode body() {
        return body;
    }

    /** 오류 코드(header.resultCode). 없으면 null */
    public String resultCode() {
        JsonNode header = body == null ? null : body.get("header");
        JsonNode code = header == null ? null : header.get("resultCode");
        return code == null || code.isNull() ? null : code.asString();
    }
}
