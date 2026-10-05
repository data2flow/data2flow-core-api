package net.java21.data2flow.core.analytics.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.analytics.service.AnalysisRunService;
import net.java21.data2flow.core.analytics.service.AnalysisService;
import net.java21.data2flow.core.analytics.service.AnalyticsDataService;
import net.java21.data2flow.core.analytics.service.AnalyticsTemplateService;
import net.java21.data2flow.core.common.InternalHttp;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Map;

/**
 * 분석 외부 API(ANA-api §1, {@code /api/v1/core/analytics/**} → {@code /core/analytics/**}). core가 권한·공간 범위를 확인하고 analytics 내부 API로
 * 넘긴다. 목록 봉투({@code page·responses·totalCount})를 analytics가 만든 것은 그대로 돌려준다.
 */
@RestController
public class AnalyticsController {

    private final AnalyticsTemplateService templates;
    private final AnalysisService analyses;
    private final AnalysisRunService runs;
    private final AnalyticsDataService data;

    public AnalyticsController(AnalyticsTemplateService templates, AnalysisService analyses, AnalysisRunService runs, AnalyticsDataService data) {
        this.templates = templates;
        this.analyses = analyses;
        this.runs = runs;
        this.data = data;
    }

    // ---------------------------------------------------------------- 템플릿(API-ANA-01~05·17~19)

    /** API-ANA-01 목록 · 03 질문으로 찾기(keyword) · 04 실행 가능성(view=runnable&spaceId=) */
    @GetMapping("/core/analytics/templates")
    public ListApiResponse<JsonNode> templates(@RequestParam(required = false) String category, @RequestParam(required = false) String kind,
                                               @RequestParam(required = false, defaultValue = "false") boolean includeDisabled,
                                               @RequestParam(required = false) String keyword, @RequestParam(required = false) String view,
                                               @RequestParam(required = false) Long spaceId, @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return templates.list(category, kind, includeDisabled, keyword, view, spaceId, page, size);
    }

    /** API-ANA-02 상세(설명서) */
    @GetMapping("/core/analytics/templates/{template-key}")
    public ApiResponse<JsonNode> template(@PathVariable("template-key") String key, @RequestParam(required = false) String version) {
        return ApiResponse.success(templates.detail(key, version));
    }

    /** API-ANA-05 데이터 충분성 확인 */
    @PostMapping("/core/analytics/templates/{template-key}/check")
    public ApiResponse<JsonNode> check(@PathVariable("template-key") String key, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(templates.check(key, body));
    }

    /** API-ANA-19 역할 후보 */
    @GetMapping("/core/analytics/templates/{template-key}/roles/{role}/candidates")
    public ListApiResponse<Map<String, Object>> candidates(@PathVariable("template-key") String key, @PathVariable("role") String role,
                                                           @RequestParam(required = false) Long spaceId, @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) String kind, @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size) {
        return templates.candidates(key, role, spaceId, keyword, kind, page, size);
    }

    /** API-ANA-17 템플릿 설정 목록(AD) */
    @GetMapping("/core/analytics/template-settings")
    public JsonNode templateSettings(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return templates.settings(page, size);
    }

    /** API-ANA-18 템플릿 켜기·끄기(AD) */
    @PutMapping("/core/analytics/template-settings/{template-key}")
    public ApiResponse<JsonNode> updateTemplateSetting(@PathVariable("template-key") String key, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(templates.updateSetting(key, body));
    }

    // ---------------------------------------------------------------- 분석 정의(API-ANA-06·07·11·22)

    @PostMapping("/core/analytics/analyses")
    @Idempotent
    public ResponseEntity<ApiResponse<JsonNode>> create(@RequestBody(required = false) JsonNode body) {
        JsonNode created = analyses.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/analytics/analyses/" + created.path("analysisId").asString("")))
                .body(ApiResponse.success(created));
    }

    @GetMapping("/core/analytics/analyses")
    public ListApiResponse<Map<String, Object>> list(@RequestParam(required = false) String templateKey, @RequestParam(required = false) String owner,
                                                     @RequestParam(required = false) String scheduleState,
                                                     @RequestParam(required = false) Boolean realtime, @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return analyses.list(templateKey, owner, scheduleState, realtime, keyword, page, size);
    }

    @GetMapping("/core/analytics/analyses/{analysis-id}")
    public ApiResponse<JsonNode> get(@PathVariable("analysis-id") long id) {
        return ApiResponse.success(analyses.get(id));
    }

    @PutMapping("/core/analytics/analyses/{analysis-id}")
    @Idempotent
    public ApiResponse<JsonNode> update(@PathVariable("analysis-id") long id, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(analyses.update(id, body));
    }

    @DeleteMapping("/core/analytics/analyses/{analysis-id}")
    public ResponseEntity<Void> delete(@PathVariable("analysis-id") long id) {
        analyses.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/core/analytics/analyses/{analysis-id}/realtime")
    public ApiResponse<JsonNode> realtime(@PathVariable("analysis-id") long id, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(analyses.realtime(id, body));
    }

    @GetMapping("/core/analytics/analyses/{analysis-id}/realtime-events")
    public JsonNode realtimeEvents(@PathVariable("analysis-id") long id, @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        return analyses.realtimeEvents(id, from, to, page, size);
    }

    // ---------------------------------------------------------------- 실행·결과(API-ANA-08~10·12~14)

    /** API-ANA-08 실행 요청 — 202 */
    @PostMapping("/core/analytics/analyses/{analysis-id}/runs")
    @Idempotent
    public ResponseEntity<ApiResponse<Map<String, Object>>> run(@PathVariable("analysis-id") long id, @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.accepted().body(ApiResponse.success(runs.run(id, body)));
    }

    @GetMapping("/core/analytics/analyses/{analysis-id}/runs")
    public JsonNode runs(@PathVariable("analysis-id") long id, @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size) {
        return runs.runs(id, page, size);
    }

    /** API-ANA-13 실행 비교(리터럴 경로가 ID 자리와 겹치지 않도록 숫자 ID 경로보다 먼저 정의) */
    @GetMapping("/core/analytics/analyses/{analysis-id}/runs/compare")
    public ApiResponse<JsonNode> compare(@PathVariable("analysis-id") long id, @RequestParam String base, @RequestParam String target) {
        return ApiResponse.success(runs.compare(id, base, target));
    }

    @GetMapping("/core/analytics/analyses/{analysis-id}/runs/{run-id:[0-9]+}")
    public ApiResponse<JsonNode> runResult(@PathVariable("analysis-id") long id, @PathVariable("run-id") long runId) {
        return ApiResponse.success(runs.get(id, runId));
    }

    @PostMapping("/core/analytics/analyses/{analysis-id}/runs/{run-id}/cancel")
    public ApiResponse<JsonNode> cancel(@PathVariable("analysis-id") long id, @PathVariable("run-id") long runId) {
        return ApiResponse.success(runs.cancel(id, runId));
    }

    /** API-ANA-12 내보내기 — CSV·PNG 200, PDF 202 */
    @PostMapping("/core/analytics/analyses/{analysis-id}/runs/{run-id}/export")
    public ResponseEntity<ApiResponse<JsonNode>> export(@PathVariable("analysis-id") long id, @PathVariable("run-id") long runId,
                                                        @RequestBody(required = false) JsonNode body) {
        InternalHttp.Result r = runs.export(id, runId, body);
        return ResponseEntity.status(r.status() == 202 ? 202 : 200).body(ApiResponse.success(r.response()));
    }

    /** API-ANA-14 재학습 — 202 */
    @PostMapping("/core/analytics/analyses/{analysis-id}/models/train")
    public ResponseEntity<ApiResponse<JsonNode>> train(@PathVariable("analysis-id") long id, @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.accepted().body(ApiResponse.success(runs.train(id, body)));
    }

    /** API-ANA-23 모델 목록 */
    @GetMapping("/core/analytics/models")
    public JsonNode models(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return runs.models(page, size);
    }

    /** API-ANA-24 모델 적용 */
    @PostMapping("/core/analytics/models/{model-id}/activate")
    public ApiResponse<JsonNode> activate(@PathVariable("model-id") long modelId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(runs.activate(modelId, body));
    }

    /** API-ANA-15 이상 피드백 — 201 */
    @PostMapping("/core/analytics/feedback")
    public ResponseEntity<ApiResponse<JsonNode>> feedback(@RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(201).body(ApiResponse.success(runs.feedback(body)));
    }

    // ---------------------------------------------------------------- 데이터셋·KPI·설정(API-ANA-20·21·25)

    @GetMapping("/core/analytics/datasets")
    public JsonNode datasets(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return data.datasets(page, size);
    }

    @PostMapping("/core/analytics/datasets")
    @Idempotent
    public ResponseEntity<ApiResponse<JsonNode>> createDataset(@RequestBody(required = false) JsonNode body) {
        JsonNode created = data.createDataset(body);
        return ResponseEntity.created(URI.create("/api/v1/core/analytics/datasets/" + (created == null ? "" : created.path("datasetId").asString(""))))
                .body(ApiResponse.success(created));
    }

    @GetMapping("/core/analytics/datasets/{dataset-id}")
    public ApiResponse<JsonNode> dataset(@PathVariable("dataset-id") long id) {
        return ApiResponse.success(data.dataset(id));
    }

    @PutMapping("/core/analytics/datasets/{dataset-id}")
    public ApiResponse<JsonNode> updateDataset(@PathVariable("dataset-id") long id, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(data.updateDataset(id, body));
    }

    @DeleteMapping("/core/analytics/datasets/{dataset-id}")
    public ResponseEntity<Void> deleteDataset(@PathVariable("dataset-id") long id) {
        data.deleteDataset(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/core/analytics/datasets/{dataset-id}/versions")
    public JsonNode datasetVersions(@PathVariable("dataset-id") long id, @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return data.datasetVersions(id, page, size);
    }

    /** API-ANA-20 데이터셋 갱신(부록 A 경로) */
    @PostMapping("/core/analytics/datasets/{dataset-id}/analyses/{analysis-id}/upgrade-dataset")
    public ApiResponse<JsonNode> upgradeDataset(@PathVariable("dataset-id") long datasetId, @PathVariable("analysis-id") long analysisId) {
        return ApiResponse.success(data.upgradeDataset(datasetId, analysisId));
    }

    /** API-ANA-20 데이터셋 갱신(§1 경로 {@code …/analyses/{analysis-id}/upgrade-dataset}, 본문 {datasetId}) */
    @PostMapping("/core/analytics/analyses/{analysis-id}/upgrade-dataset")
    public ApiResponse<JsonNode> upgradeDatasetOfAnalysis(@PathVariable("analysis-id") long analysisId, @RequestBody JsonNode body) {
        String datasetId = body == null ? "" : body.path("datasetId").asString("");
        if (!datasetId.matches("[1-9][0-9]{0,18}")) {
            throw new net.java21.data2flow.contracts.error.BusinessException(
                    net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode.DATASET_NOT_FOUND);
        }
        return ApiResponse.success(data.upgradeDataset(Long.parseLong(datasetId), analysisId));
    }

    @GetMapping("/core/analytics/kpis")
    public ListApiResponse<JsonNode> kpis(@RequestParam(required = false) Long spaceId, @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to, @RequestParam(required = false) String keys) {
        return data.kpis(spaceId, from, to, keys);
    }

    @GetMapping("/core/analytics/settings")
    public ApiResponse<Map<String, Object>> settings() {
        return ApiResponse.success(data.settings());
    }

    @PutMapping("/core/analytics/settings")
    public ApiResponse<Map<String, Object>> updateSettings(@RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(data.updateSettings(body));
    }
}
