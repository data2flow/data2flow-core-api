package net.java21.data2flow.core.ingest.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.ingest.domain.IngestErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * data2flow-pipeline 내부 API 호출(재처리·폐기). dlq_items·reprocess_jobs는 pipeline 소유라 core는 쓰지 않고 이 API로 부탁한다
 * (ING-api.md "노출 방식"). 내부망 HTTP, 토큰 없이 {@code X-CALLER-SERVICE}만 붙인다(ADR-021).
 * <ul>
 *   <li>API-ING-22 {@code POST /internal/pipeline/reprocess-items} — 동기, 5,000건까지 30초 안</li>
 *   <li>API-ING-23 {@code POST /internal/pipeline/reprocess-jobs}, {@code POST /internal/pipeline/reprocess-jobs/{job-id}/cancel}</li>
 *   <li>{@code POST /internal/pipeline/dlq-items/discard} — 문서에 아직 없는 경로(API-ING-11 처리용, 문서 추가 필요)</li>
 * </ul>
 * pipeline은 공통 봉투 {@code {header, response}}(ApiResponse)로 답한다. 봉투를 벗겨 {@code response}를 읽고, 봉투 없이 온 본문도 받는다.
 * pipeline이 응답하지 않거나 5xx면 503 SERVICE_UNAVAILABLE로 알린다.
 */
@Component
public class PipelineIngestClient {

    static final String CALLER = "data2flow-core-api";
    private static final Logger log = LoggerFactory.getLogger(PipelineIngestClient.class);

    private final RestClient client;
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public PipelineIngestClient(CoreProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(35));
        this.client = RestClient.builder().baseUrl(properties.pipelineBaseUrl()).requestFactory(factory).build();
    }

    /** API-ING-22 요청 항목 */
    public record Item(String kind, long id) {
    }

    /** API-ING-22 요청 */
    public record ReprocessItemsRequest(long organizationId, long requestedBy, List<Item> items) {
    }

    /** API-ING-22 응답 항목(outcome OK·FAILED·SKIPPED) */
    public record ItemResult(long id, String outcome, String errorCode) {
    }

    /** API-ING-22 응답 */
    public record ReprocessItemsResponse(List<ItemResult> results, Integer ok, Integer failed, Integer skipped) {
    }

    /** API-ING-23 생성 요청 */
    public record CreateJobRequest(long organizationId, long requestedBy, Long sourceId, List<Long> deviceIds, Instant from, Instant to,
                                   boolean onlyFailed, String memo) {
    }

    /** API-ING-23 생성 응답(202) */
    public record CreateJobResponse(Long jobId, String status, Long estimatedCount) {
    }

    /** API-ING-23 취소 요청·응답 */
    public record CancelJobRequest(long organizationId, long requestedBy) {
    }

    public record CancelJobResponse(Long jobId, String status) {
    }

    /** 폐기 요청·응답 */
    public record DiscardRequest(long organizationId, long requestedBy, List<Long> dlqItemIds, String reason) {
    }

    public record DiscardResponse(Integer discarded) {
    }

    public ReprocessItemsResponse reprocessItems(ReprocessItemsRequest request) {
        return post("/internal/pipeline/reprocess-items", request, ReprocessItemsResponse.class, null);
    }

    public CreateJobResponse createJob(CreateJobRequest request) {
        return post("/internal/pipeline/reprocess-jobs", request, CreateJobResponse.class, IngestErrorCode.ING_REPROCESS_ALREADY_RUNNING);
    }

    public CancelJobResponse cancelJob(long jobId, CancelJobRequest request) {
        return post("/internal/pipeline/reprocess-jobs/" + jobId + "/cancel", request, CancelJobResponse.class,
                IngestErrorCode.ING_REPROCESS_NOT_CANCELLABLE);
    }

    public DiscardResponse discard(DiscardRequest request) {
        return post("/internal/pipeline/dlq-items/discard", request, DiscardResponse.class, null);
    }

    private <T> T post(String path, Object body, Class<T> type, IngestErrorCode conflict) {
        try {
            JsonNode reply = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(DataflowHeaders.CALLER_SERVICE, CALLER)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode payload = unwrap(reply);
            if (payload == null) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            return mapper.treeToValue(payload, type);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409 && conflict != null) {
                throw new BusinessException(conflict);
            }
            log.warn("pipeline {} 호출 실패: HTTP {}", path, ex.getStatusCode().value());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        } catch (tools.jackson.core.JacksonException ex) {
            log.warn("pipeline {} 응답을 읽을 수 없습니다: {}", path, ex.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        } catch (RestClientException ex) {
            log.warn("pipeline {} 호출 실패: {}", path, ex.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** 공통 봉투면 {@code response}, 아니면 본문 그대로. 비었거나 실패 봉투면 null */
    static JsonNode unwrap(JsonNode reply) {
        if (reply == null || reply.isNull() || reply.isMissingNode() || !reply.isObject()) {
            return null;
        }
        if (reply.has("header") && reply.get("header").isObject()) {
            if (!reply.get("header").path("isSuccessful").asBoolean(true)) {
                return null;
            }
            JsonNode response = reply.get("response");
            return response == null || response.isNull() || !response.isObject() ? null : response;
        }
        return reply;
    }
}
