package net.java21.data2flow.core.commissioning.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.BoardDeviceResponse;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.BoardResponse;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.CommissionResponse;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.CommissioningStatusResponse;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.QrLabelsRequest;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.QrResolveResponse;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.QrResponse;
import net.java21.data2flow.core.commissioning.service.CommissioningService;
import net.java21.data2flow.core.commissioning.service.CommissioningService.Photo;
import net.java21.data2flow.core.commissioning.service.QrService;
import net.java21.data2flow.core.common.ItemsResponse;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** QR 라벨·딥링크(DEV-09.04, API-DEV-24·26), 현장 설치(DEV-13.05, API-DEV-137), 설치 현황판(DEV-13.06, API-DEV-138) */
@RestController
public class CommissioningController {

    private final QrService qr;
    private final CommissioningService commissioning;

    public CommissioningController(QrService qr, CommissioningService commissioning) {
        this.qr = qr;
        this.commissioning = commissioning;
    }

    /** 기기 QR(토큰·내용 URL, 없으면 만든다) — DEV_READ */
    @GetMapping("/core/devices/{device-id}/qr")
    public ApiResponse<QrResponse> qr(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(qr.get(deviceId));
    }

    /** API-DEV-24 재발급 — DEV_PLACE. 이전 QR은 404 */
    @PostMapping("/core/devices/{device-id}/reissue-qr")
    public ApiResponse<QrResponse> reissue(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(qr.reissue(deviceId));
    }

    /** API-DEV-24 라벨 PDF(≤200대, A4_3x8) — DEV_PLACE */
    @PostMapping("/core/devices/qr-labels")
    public ResponseEntity<byte[]> labels(@Valid @RequestBody QrLabelsRequest request) {
        byte[] pdf = qr.labels(request.deviceIds(), request.layout());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"qr-labels.pdf\"").body(pdf);
    }

    /** API-DEV-26 QR 토큰 해석(BFF /d/{qrToken} → 302 /devices/{id}) — DEV_READ, 없거나 권한 밖 404 DEVICE_NOT_FOUND */
    @GetMapping("/core/qr/{qr-token}")
    public ApiResponse<QrResolveResponse> resolve(@PathVariable("qr-token") String token) {
        return ApiResponse.success(qr.resolve(token));
    }

    /** API-DEV-137 현장 설치(multipart: clientOpId·spaceId·x·y·installedAt + photos 0~5장) — DEV_PLACE */
    @PostMapping(value = "/core/devices/{device-id}/commission", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CommissionResponse> commission(@PathVariable("device-id") long deviceId, @RequestParam String clientOpId,
                                                      @RequestParam String spaceId, @RequestParam(required = false) BigDecimal x,
                                                      @RequestParam(required = false) BigDecimal y,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant installedAt,
                                                      @RequestPart(value = "photos", required = false) List<MultipartFile> photos) throws IOException {
        List<Photo> list = new ArrayList<>();
        if (photos != null) {
            if (photos.size() > 5) {
                throw new net.java21.data2flow.contracts.error.BusinessException(net.java21.data2flow.contracts.error.CommonErrorCode.INVALID_REQUEST,
                        List.of(new net.java21.data2flow.contracts.error.FieldErrorDetail("photos", "Size", "0~5")));
            }
            for (MultipartFile f : photos) {
                list.add(new Photo(f.getOriginalFilename(), f.getBytes()));
            }
        }
        return ApiResponse.success(commissioning.commission(deviceId, clientOpId, spaceId, x, y, installedAt, list));
    }

    /** 설치 상태(대기 화면: 첫 수신이면 최근값, 문제면 체크리스트) — DEV_READ */
    @GetMapping("/core/devices/{device-id}/commission")
    public ApiResponse<CommissioningStatusResponse> status(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(commissioning.status(deviceId));
    }

    @GetMapping("/core/devices/{device-id}/commission/photos/{photo-id}")
    public ResponseEntity<byte[]> photo(@PathVariable("device-id") long deviceId, @PathVariable("photo-id") long photoId) {
        Blob blob = commissioning.photo(deviceId, photoId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(blob.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600").body(blob.data());
    }

    /** API-DEV-138 층별 설치 현황 — DEV_READ */
    @GetMapping("/core/installation-board")
    public ApiResponse<BoardResponse> board(@RequestParam(required = false) String siteId) {
        return ApiResponse.success(commissioning.board(siteId));
    }

    /** API-DEV-138 칸의 기기 목록과 체크리스트 — DEV_READ */
    @GetMapping("/core/installation-board/devices")
    public ItemsResponse<BoardDeviceResponse> boardDevices(@RequestParam String spaceId, @RequestParam(required = false) String status) {
        return ItemsResponse.of(commissioning.boardDevices(spaceId, status));
    }
}
