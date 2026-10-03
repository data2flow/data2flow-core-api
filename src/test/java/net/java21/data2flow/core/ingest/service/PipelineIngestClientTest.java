package net.java21.data2flow.core.ingest.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.ingest.PipelineStubServer;
import net.java21.data2flow.core.ingest.PipelineStubServer.Reply;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ING-07.03 API-ING-22·23·24: pipeline 실제 응답 모양({@code ApiResponse.success} 봉투, InternalIngestController)을 읽는다.
 * 봉투를 벗기지 않으면 모든 필드가 null이 되던 결함의 회귀 시험.
 */
class PipelineIngestClientTest {

    private static final PipelineStubServer PIPELINE = new PipelineStubServer();
    private final PipelineIngestClient client = new PipelineIngestClient(
            new CoreProperties(null, null, null, null, null, null, null, null, null, null, PIPELINE.baseUrl(), null, null));

    @BeforeEach
    void reset() {
        PIPELINE.reset();
    }

    @AfterAll
    static void stop() {
        PIPELINE.reset();
    }

    @Test
    @DisplayName("[ING-07.03] API-ING-22 재처리 결과를 봉투에서 읽는다(pipeline 필드 kind·previousErrorCode는 무시) — TC-ING-086")
    void reprocessItemsEnvelope() {
        PIPELINE.respond(r -> new Reply(200, """
                {"header":{"isSuccessful":true,"resultCode":"SUCCESS","resultMessage":"SUCCESS"},
                 "response":{"results":[{"kind":"DLQ","id":11,"outcome":"FAILED","errorCode":"SCRIPT_TIMEOUT","previousErrorCode":"SCRIPT_RUNTIME_ERROR"}],
                             "ok":0,"failed":1,"skipped":0}}"""));
        var res = client.reprocessItems(new PipelineIngestClient.ReprocessItemsRequest(1, 2, List.of(new PipelineIngestClient.Item("DLQ", 11))));
        assertThat(res.results()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(11);
            assertThat(r.outcome()).isEqualTo("FAILED");
            assertThat(r.errorCode()).isEqualTo("SCRIPT_TIMEOUT");
        });
        assertThat(res.failed()).isEqualTo(1);
        assertThat(res.ok()).isZero();
    }

    @Test
    @DisplayName("[ING-07.03] API-ING-23 작업 생성(202)·취소, API-ING-24 폐기 응답을 봉투에서 읽는다 — TC-ING-086")
    void jobsAndDiscardEnvelope() {
        var created = client.createJob(new PipelineIngestClient.CreateJobRequest(1, 2, 3L, null,
                Instant.parse("2026-10-03T00:00:00Z"), Instant.parse("2026-10-04T00:00:00Z"), false, null));
        assertThat(created.jobId()).isEqualTo(4242L);
        assertThat(created.status()).isEqualTo("QUEUED");
        assertThat(created.estimatedCount()).isEqualTo(120L);
        var cancelled = client.cancelJob(77, new PipelineIngestClient.CancelJobRequest(1, 2));
        assertThat(cancelled.jobId()).isEqualTo(77L);
        assertThat(cancelled.status()).isEqualTo("CANCELLING");
        var discarded = client.discard(new PipelineIngestClient.DiscardRequest(1, 2, List.of(5L, 6L), "정리"));
        assertThat(discarded.discarded()).isEqualTo(2);
        assertThat(PIPELINE.received()).extracting(PipelineStubServer.Received::callerService).containsOnly("data2flow-core-api");
    }

    @Test
    @DisplayName("[ING-07.03] 봉투 없는 본문도 읽고, 실패 봉투·빈 응답은 503 — TC-ING-086")
    void bareAndFailure() {
        assertThat(PipelineIngestClient.unwrap(tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree("{\"discarded\":3}")).get("discarded").asInt()).isEqualTo(3);
        PIPELINE.respond(r -> new Reply(200, "{\"header\":{\"isSuccessful\":false,\"resultCode\":\"X\",\"resultMessage\":\"x\"},\"response\":null}"));
        assertThatThrownBy(() -> client.discard(new PipelineIngestClient.DiscardRequest(1, 2, List.of(5L), "정리")))
                .isInstanceOf(BusinessException.class);
        PIPELINE.respond(r -> new Reply(200, "[1]"));
        assertThatThrownBy(() -> client.discard(new PipelineIngestClient.DiscardRequest(1, 2, List.of(5L), "정리")))
                .isInstanceOf(BusinessException.class);
        assertThat(PipelineIngestClient.unwrap(null)).isNull();
    }
}
