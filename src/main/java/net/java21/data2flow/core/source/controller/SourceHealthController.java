package net.java21.data2flow.core.source.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.IgnoreEntryResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.RuntimeResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.StatPoint;
import net.java21.data2flow.core.source.dto.SourceDtos.UsageResponse;
import net.java21.data2flow.core.source.service.DataSourceService;
import net.java21.data2flow.core.source.service.SourceLiveRelay;
import net.java21.data2flow.core.source.service.SourceQueryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

/** 소스 연결 상태·지표·진단(DSC-02, API-DSC-09·10·11·13·14) */
@RestController
public class SourceHealthController {

    private final SourceQueryService queries;
    private final DataSourceService service;
    private final SourceLiveRelay live;

    public SourceHealthController(SourceQueryService queries, DataSourceService service, SourceLiveRelay live) {
        this.queries = queries;
        this.service = service;
        this.live = live;
    }

    /** API-DSC-09 지표(최대 7일, bucket 1m·5m·1h) — SRC_READ, 200 */
    @GetMapping("/core/sources/{source-id}/stats")
    public ApiResponse<List<StatPoint>> stats(@PathVariable("source-id") long sourceId, @RequestParam(required = false) Instant from,
                                              @RequestParam(required = false) Instant to, @RequestParam(required = false) String bucket) {
        return ApiResponse.success(queries.stats(sourceId, from, to, bucket));
    }

    /** API-DSC-14 인스턴스별 연결 상태 — SRC_READ, 200 */
    @GetMapping("/core/sources/{source-id}/runtime")
    public ApiResponse<RuntimeResponse> runtime(@PathVariable("source-id") long sourceId) {
        return ApiResponse.success(queries.runtime(sourceId));
    }

    /** API-DSC-11 사용처(기기 수·7일 수신량) — SRC_READ, 200 */
    @GetMapping("/core/sources/{source-id}/usage")
    public ApiResponse<UsageResponse> usage(@PathVariable("source-id") long sourceId) {
        return ApiResponse.success(queries.usage(sourceId));
    }

    /** API-DSC-13 자동 등록 무시 목록 — SRC_READ, 200 */
    @GetMapping("/core/sources/{source-id}/ignore-list")
    public ListApiResponse<IgnoreEntryResponse> ignoreList(@PathVariable("source-id") long sourceId,
                                                           @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size) {
        return queries.ignoreList(sourceId, page, size);
    }

    /** API-DSC-13 무시 목록에서 빼기 — SRC_ADMIN, 204 */
    @DeleteMapping("/core/sources/{source-id}/ignore-list/{external-id}")
    public ResponseEntity<Void> removeIgnore(@PathVariable("source-id") long sourceId, @PathVariable("external-id") String externalId) {
        service.removeIgnoreEntry(sourceId, externalId);
        return ResponseEntity.noContent().build();
    }

    /**
     * API-DSC-10 실시간 원본 메시지(SSE, DSC-02.06) — SRC_ADMIN. 이벤트 message·dropped·ping. SSE는 {@code /core/stream/**}에만 둔다
     * (gateway·BFF 중계 규칙, 예전 경로 {@code /core/sources/{id}/live}는 쓰지 않는다)
     */
    @GetMapping(value = "/core/stream/sources/{source-id}/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live(@PathVariable("source-id") long sourceId, @RequestParam(required = false) String topicFilter,
                           @RequestParam(required = false) Integer maxRate) {
        return live.open(sourceId, topicFilter, maxRate);
    }
}
