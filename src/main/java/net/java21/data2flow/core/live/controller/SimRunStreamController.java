package net.java21.data2flow.core.live.controller;

import jakarta.servlet.http.HttpServletResponse;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.web.ErrorMessages;
import net.java21.data2flow.contracts.web.ErrorResponse;
import net.java21.data2flow.core.common.RelayedErrorException;
import net.java21.data2flow.core.live.service.SimRunStreams;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * API-SIM-31 가상 환경 실행 실시간 스트림(SSE). gateway가 {@code /api/v1/core/stream/sim/runs/{run-id}}를 넘기고 BFF가 중계한다.
 * 연결 전 실패(권한 403, 실행 없음 404 SIM_NOT_FOUND)는 JSON 오류 본문으로 답한다(BFF가 resultCode를 그대로 전한다).
 */
@RestController
public class SimRunStreamController {

    private final SimRunStreams streams;
    private final ErrorMessages messages;
    private final JsonMapper json;

    public SimRunStreamController(SimRunStreams streams, ErrorMessages messages, JsonMapper json) {
        this.streams = streams;
        this.messages = messages;
        this.json = json;
    }

    @GetMapping("/core/stream/sim/runs/{run-id}")
    public SseEmitter stream(@PathVariable("run-id") String runId, HttpServletResponse response) throws IOException {
        try {
            SseEmitter emitter = streams.open(runId);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");
            return emitter;
        } catch (BusinessException ex) {
            write(response, ex.getErrorCode().httpStatus(), json.writeValueAsString(ErrorResponse.of(ex.getErrorCode().code(),
                    messages.resolve(ex.getErrorCode(), ex.getArgs()), ex.getErrors())));
            return null;
        } catch (RelayedErrorException ex) {
            write(response, ex.status(), json.writeValueAsString(ex.body()));
            return null;
        }
    }

    private static void write(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
