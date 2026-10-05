package net.java21.data2flow.core.asset.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 자산 정보 API(design/api/DEV-api.md §9 API-DEV-96, DEV-08.01) */
public final class AssetDtos {

    private AssetDtos() {
    }

    /** API-DEV-96 요청(전체 교체: 빠진 필드는 비운다) */
    public record AssetInfoRequest(@Size(max = 100) String serialNo, LocalDate purchasedOn, LocalDate installedOn, LocalDate warrantyUntil,
                                   @Size(max = 100) String supplier, @Size(max = 100) String installer) {
    }

    /** API-DEV-96 응답. photoUrls는 권한을 다시 확인하는 내려받기 경로 */
    public record AssetInfoResponse(String deviceId, String serialNo, LocalDate purchasedOn, LocalDate installedOn, LocalDate warrantyUntil,
                                    String supplier, String installer, List<String> photoUrls, Instant updatedAt) {
    }
}
