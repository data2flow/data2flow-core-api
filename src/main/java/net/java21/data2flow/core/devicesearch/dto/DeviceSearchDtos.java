package net.java21.data2flow.core.devicesearch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 저장된 검색 API(design/api/DEV-api.md §11-2 API-DEV-134) */
public final class DeviceSearchDtos {

    private DeviceSearchDtos() {
    }

    public record SavedSearchRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 2000) String query, Boolean shared) {
    }

    public record SavedSearchResponse(String id, String name, String query, boolean shared, String ownerId, String ownerName,
                                      Instant updatedAt) {
    }
}
