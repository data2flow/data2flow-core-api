package net.java21.data2flow.core.live.controller;

import jakarta.servlet.http.HttpServletResponse;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.contracts.web.ErrorMessages;
import net.java21.data2flow.contracts.web.ErrorResponse;
import net.java21.data2flow.core.live.service.LiveStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * API-DSH-20 실시간 구독(SSE). gateway가 {@code /api/v1/core/stream/live}를 {@code /core/stream/live}로 넘기고, BFF가 브라우저 연결을 중계한다.
 *
 * <p>연결을 열기 전의 실패(형식 400·권한 403·범위 404)는 공통 JSON 오류 본문으로 답한다. BFF가 {@code Accept: text/event-stream}으로 부르므로
 * 내용 협상에 맡기지 않고 여기서 {@code application/json}으로 직접 쓴다(BFF는 이 본문의 resultCode를 브라우저에 그대로 전한다).
 */
@RestController
public class LiveStreamController {

    private final LiveStreamService service;
    private final ErrorMessages messages;
    private final JsonMapper json;

    public LiveStreamController(LiveStreamService service, ErrorMessages messages, JsonMapper json) {
        this.service = service;
        this.messages = messages;
        this.json = json;
    }

    @GetMapping("/core/stream/live")
    public SseEmitter live(@RequestParam(value = "topics", required = false) String topics,
                           @RequestHeader(value = DataflowHeaders.SESSION_ID, required = false) String sessionId,
                           @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                           HttpServletResponse response) throws IOException {
        try {
            SseEmitter emitter = service.open(topics, sessionId, lastEventId);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");
            return emitter;
        } catch (BusinessException ex) {
            response.setStatus(ex.getErrorCode().httpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(json.writeValueAsString(ErrorResponse.of(ex.getErrorCode().code(),
                    messages.resolve(ex.getErrorCode(), ex.getArgs()), ex.getErrors())));
            return null;
        }
    }

    /** API-RUL-14 알람 실시간 스트림(BFF {@code /bff/stream/alarms}) */
    @GetMapping("/core/stream/alarms")
    public SseEmitter alarms(@RequestHeader(value = DataflowHeaders.SESSION_ID, required = false) String sessionId,
                             HttpServletResponse response) throws IOException {
        try {
            SseEmitter emitter = service.openAlarms(sessionId);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");
            return emitter;
        } catch (BusinessException ex) {
            response.setStatus(ex.getErrorCode().httpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(json.writeValueAsString(ErrorResponse.of(ex.getErrorCode().code(),
                    messages.resolve(ex.getErrorCode(), ex.getArgs()), ex.getErrors())));
            return null;
        }
    }
}
