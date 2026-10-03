package net.java21.data2flow.core.ingest;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.telemetry.PipelineRows;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 수집 관리 통합 시험 기반: pipeline 내부 API 대역 주소를 넣는다. WP-E의 수집 IT는 모두 이 클래스를 상속해 Spring 문맥 하나를 함께 쓴다.
 */
abstract class IngestITSupport extends IntegrationTestSupport {

    static final PipelineStubServer PIPELINE = new PipelineStubServer();

    @DynamicPropertySource
    static void pipeline(DynamicPropertyRegistry registry) {
        registry.add("data2flow.core.pipeline-base-url", PIPELINE::baseUrl);
    }

    PipelineRows rows;

    @BeforeEach
    void resetPipeline() {
        PIPELINE.reset();
        rows = new PipelineRows(jdbc);
    }
}
