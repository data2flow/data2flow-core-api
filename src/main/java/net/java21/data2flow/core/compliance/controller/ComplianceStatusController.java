package net.java21.data2flow.core.compliance.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.ScoreFormula;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.ScoreHistory;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.ScoreView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.SpaceStatusView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.StatusView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.WeightsRequest;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.WeightsView;
import net.java21.data2flow.core.compliance.service.ComplianceScoreService;
import net.java21.data2flow.core.compliance.service.ComplianceStatusService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** 종합 점수와 준수 현황(CMP-01.05·02.01): API-CMP-10·13·14·15 */
@RestController
public class ComplianceStatusController {

    private final ComplianceScoreService scores;
    private final ComplianceStatusService status;

    public ComplianceStatusController(ComplianceScoreService scores, ComplianceStatusService status) {
        this.scores = scores;
        this.status = status;
    }

    /** API-CMP-10 현재 점수 — CMP_READ. include=inputs면 입력값·가중치·경계를 싣는다 */
    @GetMapping("/core/compliance/spaces/{space-id}/score")
    public ApiResponse<ScoreView> score(@PathVariable("space-id") long spaceId, @RequestParam(required = false) String include) {
        return ApiResponse.success(scores.score(spaceId, include != null && include.contains("inputs")));
    }

    /** API-CMP-10 점수 이력 — CMP_READ. step 5m(31일 이하)·1h·1d(400일 이하) */
    @GetMapping("/core/compliance/spaces/{space-id}/score/history")
    public ApiResponse<ScoreHistory> history(@PathVariable("space-id") long spaceId, @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to, @RequestParam(required = false) String step) {
        return ApiResponse.success(scores.history(spaceId, from, to, step));
    }

    /** API-CMP-10 공개 계산식과 조직 가중치 — CMP_READ */
    @GetMapping("/core/compliance/score-formula")
    public ApiResponse<ScoreFormula> formula() {
        return ApiResponse.success(scores.formula());
    }

    /** API-CMP-10 가중치 바꾸기 — CMP_ADMIN, baseVersion 필수 */
    @PutMapping("/core/compliance/score-weights")
    public ApiResponse<WeightsView> weights(@RequestBody(required = false) WeightsRequest request) {
        return ApiResponse.success(scores.saveWeights(request));
    }

    /** API-CMP-13 현황판 — CMP_READ. from·to는 YYYY-MM-DD(조직 시간대 날, 400일 이하) */
    @GetMapping("/core/compliance/status")
    public ApiResponse<StatusView> status(@RequestParam(required = false) String siteId, @RequestParam(required = false) String spaceId,
                                          @RequestParam(required = false) String profileId, @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to, @RequestParam(required = false) String band) {
        return ApiResponse.success(status.status(siteId, spaceId, profileId, from, to));
    }

    /** API-CMP-15 현황판 CSV — CMP_READ, UTF-8 BOM */
    @GetMapping("/core/compliance/status.csv")
    public ResponseEntity<byte[]> csv(@RequestParam(required = false) String siteId, @RequestParam(required = false) String spaceId,
                                      @RequestParam(required = false) String profileId, @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to, @RequestParam(required = false) String band) {
        byte[] body = status.csv(siteId, spaceId, profileId, from, to).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"compliance-status.csv\"")
                .body(body);
    }

    /** API-CMP-14 공간 일별 값·이탈 구간 — CMP_READ */
    @GetMapping("/core/compliance/status/spaces/{space-id}")
    public ApiResponse<SpaceStatusView> space(@PathVariable("space-id") long spaceId, @RequestParam(required = false) String profileId,
                                              @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return ApiResponse.success(status.space(spaceId, profileId, from, to));
    }
}
