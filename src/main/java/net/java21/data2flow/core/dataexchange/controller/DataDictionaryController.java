package net.java21.data2flow.core.dataexchange.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.dataexchange.service.DataDictionaryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 데이터 사전(design/api/TSD-api.md API-TSD-59, TSD-07.04) — TS_READ(VIEWER 이상) */
@RestController
public class DataDictionaryController {

    private final DataDictionaryService service;

    public DataDictionaryController(DataDictionaryService service) {
        this.service = service;
    }

    /** format=json(기본, 공통 응답 봉투) 또는 html({@code text/html} 문서). version이 없으면 현재 판 */
    @GetMapping("/core/data-dictionary")
    public ResponseEntity<?> get(@RequestParam(required = false) String format, @RequestParam(required = false) Integer version) {
        String f = format == null || format.isBlank() ? "json" : format.strip().toLowerCase(Locale.ROOT);
        if ("html".equals(f)) {
            return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                    .body(service.html(version));
        }
        if (!"json".equals(f)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("format", "INVALID", "json|html")));
        }
        Map<String, Object> dictionary = service.dictionary(version);
        return ResponseEntity.ok(ApiResponse.success(dictionary));
    }
}
