package net.java21.data2flow.core.sink.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.sink.service.SinkConnectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Map;

/** Sink 연결(FLW-04.01, API-FLW-50·51)과 action 내부 조회(API-FLW-85) */
@RestController
public class SinkConnectionController {

    private final SinkConnectionService service;

    public SinkConnectionController(SinkConnectionService service) {
        this.service = service;
    }

    @GetMapping("/core/sink-connections")
    public ListApiResponse<Map<String, Object>> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(page, size);
    }

    @PostMapping("/core/sink-connections")
    @Idempotent
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody JsonNode body) {
        Map<String, Object> c = service.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/sink-connections/" + c.get("sinkConnectionId"))).body(ApiResponse.success(c));
    }

    @PostMapping("/core/sink-connections/test")
    public ApiResponse<JsonNode> testDraft(@RequestBody JsonNode body) {
        return ApiResponse.success(service.testDraft(body));
    }

    @GetMapping("/core/sink-connections/{sink-connection-id:\\d+}")
    public ApiResponse<Map<String, Object>> get(@PathVariable("sink-connection-id") long id) {
        return ApiResponse.success(service.get(id));
    }

    @PatchMapping("/core/sink-connections/{sink-connection-id:\\d+}")
    public ApiResponse<Map<String, Object>> update(@PathVariable("sink-connection-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(service.update(id, body));
    }

    @DeleteMapping("/core/sink-connections/{sink-connection-id:\\d+}")
    public ResponseEntity<Void> delete(@PathVariable("sink-connection-id") long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/core/sink-connections/{sink-connection-id:\\d+}/test")
    public ApiResponse<JsonNode> test(@PathVariable("sink-connection-id") long id) {
        return ApiResponse.success(service.test(id));
    }

    @GetMapping("/core/sink-connections/{sink-connection-id:\\d+}/schema")
    public ApiResponse<JsonNode> schema(@PathVariable("sink-connection-id") long id, @RequestParam(required = false) String target,
                                        @RequestParam(required = false) String columns) {
        return ApiResponse.success(service.schema(id, target, columns));
    }

    @PostMapping("/core/sink-connections/{sink-connection-id:\\d+}/schema")
    public ApiResponse<JsonNode> createSchema(@PathVariable("sink-connection-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(service.createSchema(id, body));
    }

    @GetMapping("/core/sink-connections/{sink-connection-id:\\d+}/dead-letters")
    public JsonNode deadLetters(@PathVariable("sink-connection-id") long id, @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) Integer size) {
        return service.deadLetters(id, cursor, size);
    }

    @PostMapping("/core/sink-connections/{sink-connection-id:\\d+}/dead-letters/resend")
    public ApiResponse<JsonNode> resend(@PathVariable("sink-connection-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(service.resend(id, body));
    }

    /** API-FLW-85 */
    @GetMapping("/internal/core/sink-connections/{sink-connection-id}")
    public ApiResponse<Map<String, Object>> internal(@PathVariable("sink-connection-id") long id) {
        return ApiResponse.success(service.internal(id));
    }
}
