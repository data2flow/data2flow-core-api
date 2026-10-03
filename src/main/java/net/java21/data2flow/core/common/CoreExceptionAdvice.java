package net.java21.data2flow.core.common;

import net.java21.data2flow.contracts.web.ApiHeader;
import net.java21.data2flow.contracts.web.ErrorMessages;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * core-api 전용 오류 응답(공통 처리기보다 먼저): 하위 서비스 오류 그대로 전달({@link RelayedErrorException}),
 * 실패 + 결과({@link FailureWithResponse}). 그 밖의 예외는 contracts {@code GlobalExceptionHandler}가 맡는다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CoreExceptionAdvice {

    private final ErrorMessages messages;

    public CoreExceptionAdvice(ErrorMessages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(RelayedErrorException.class)
    public ResponseEntity<Object> relayed(RelayedErrorException ex) {
        return ResponseEntity.status(ex.status()).contentType(MediaType.APPLICATION_JSON).body(ex.body());
    }

    @ExceptionHandler(FailureWithResponse.class)
    public ResponseEntity<Object> withResponse(FailureWithResponse ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("header", ApiHeader.failure(ex.getErrorCode().code(), messages.resolve(ex.getErrorCode(), ex.getArgs())));
        body.put("response", ex.response());
        body.put("errors", ex.getErrors());
        return ResponseEntity.status(ex.getErrorCode().httpStatus()).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
