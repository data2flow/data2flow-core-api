package net.java21.data2flow.core.source.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.source.service.PayloadSchemaService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

/**
 * payload 스키마·토픽 템플릿(DSC-09.07·09.08, ADR-056): 외부 API-DSC-59 업로드, 토픽 템플릿 미리보기(ingress API-DSC-83 중계),
 * 내부 API-DSC-81(ingress가 스키마 원문을 읽는다).
 */
@RestController
public class PayloadSchemaController {

    private final PayloadSchemaService service;

    public PayloadSchemaController(PayloadSchemaService service) {
        this.service = service;
    }

    /** API-DSC-59 — multipart {@code file}(.proto·.desc·.binpb·.avsc, 1MiB 이하) + {@code messageType?} → {schemaRef, format, messageTypes[], messageType} */
    @PostMapping(value = "/core/sources/{source-id}/payload-schema", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> upload(@PathVariable("source-id") long sourceId,
                                                   @RequestPart(value = "file", required = false) MultipartFile file,
                                                   @RequestParam(value = "messageType", required = false) String messageType) throws IOException {
        return ApiResponse.success(service.upload(sourceId, file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getBytes(), messageType));
    }

    /** 토픽 템플릿 미리보기(UI-DSC-08 [페이로드]) — {template, topics[]} → API-DSC-83 응답 그대로 */
    @PostMapping("/core/sources/topic-templates/preview")
    public ApiResponse<JsonNode> preview(@RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(service.previewTopicTemplate(body));
    }

    /** API-DSC-81 — 내부(ingress) */
    @GetMapping("/internal/core/payload-schemas/{schema-ref}")
    public ApiResponse<Map<String, Object>> schema(@PathVariable("schema-ref") String schemaRef) {
        return ApiResponse.success(service.internalGet(schemaRef));
    }
}
