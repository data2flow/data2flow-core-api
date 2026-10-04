package net.java21.data2flow.core.devicesearch.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.devicesearch.dto.DeviceSearchDtos.SavedSearchRequest;
import net.java21.data2flow.core.devicesearch.dto.DeviceSearchDtos.SavedSearchResponse;
import net.java21.data2flow.core.devicesearch.service.SavedSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** 저장된 기기 검색(design/api/DEV-api.md API-DEV-134, DEV-13.03). 실행은 API-DEV-133 {@code GET /core/devices?q=} */
@RestController
public class SavedSearchController {

    private final SavedSearchService service;

    public SavedSearchController(SavedSearchService service) {
        this.service = service;
    }

    @GetMapping("/core/saved-searches")
    public ListApiResponse<SavedSearchResponse> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(page, size);
    }

    @GetMapping("/core/saved-searches/{saved-search-id}")
    public ApiResponse<SavedSearchResponse> get(@PathVariable("saved-search-id") long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping("/core/saved-searches")
    public ResponseEntity<ApiResponse<SavedSearchResponse>> create(@Valid @RequestBody SavedSearchRequest request) {
        SavedSearchResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/saved-searches/" + created.id())).body(ApiResponse.success(created));
    }

    @PutMapping("/core/saved-searches/{saved-search-id}")
    public ApiResponse<SavedSearchResponse> update(@PathVariable("saved-search-id") long id, @Valid @RequestBody SavedSearchRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @DeleteMapping("/core/saved-searches/{saved-search-id}")
    public ResponseEntity<Void> delete(@PathVariable("saved-search-id") long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
