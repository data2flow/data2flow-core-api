package net.java21.data2flow.core.energy.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.BillRequest;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.BillView;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.EmissionFactorRequest;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.EmissionFactorView;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.SourceCandidate;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.SourceRequest;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.SourceView;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.TariffRequest;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.TariffView;
import net.java21.data2flow.core.energy.service.EmissionFactorService;
import net.java21.data2flow.core.energy.service.EnergyBillService;
import net.java21.data2flow.core.energy.service.EnergySourceService;
import net.java21.data2flow.core.energy.service.TariffService;
import org.springframework.http.HttpStatus;
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
import java.util.List;

/** 에너지 설정(ENE-01.01·01.02): API-ENE-01 단가, API-ENE-02 배출계수, API-ENE-04 원천, API-ENE-05 고지서 */
@RestController
public class EnergySettingsController {

    private static final String BASE = "/api/v1/core/energy/";

    private final TariffService tariffs;
    private final EmissionFactorService factors;
    private final EnergySourceService sources;
    private final EnergyBillService bills;

    public EnergySettingsController(TariffService tariffs, EmissionFactorService factors, EnergySourceService sources, EnergyBillService bills) {
        this.tariffs = tariffs;
        this.factors = factors;
        this.sources = sources;
        this.bills = bills;
    }

    /** API-ENE-01 목록 — ENE_READ */
    @GetMapping("/core/energy/tariffs")
    public ApiResponse<List<TariffView>> tariffs() {
        return ApiResponse.success(tariffs.list());
    }

    /** API-ENE-01 추가 — ENE_ADMIN, 201 + Location */
    @PostMapping("/core/energy/tariffs")
    @Idempotent
    public ResponseEntity<ApiResponse<TariffView>> createTariff(@RequestBody(required = false) TariffRequest request) {
        TariffView view = tariffs.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create(BASE + "tariffs/" + view.tariffId())).body(ApiResponse.success(view));
    }

    /** API-ENE-01 수정 — ENE_ADMIN */
    @PutMapping("/core/energy/tariffs/{tariff-id}")
    public ApiResponse<TariffView> updateTariff(@PathVariable("tariff-id") long id, @RequestBody(required = false) TariffRequest request) {
        return ApiResponse.success(tariffs.update(id, request));
    }

    /** API-ENE-01 삭제 — ENE_ADMIN */
    @DeleteMapping("/core/energy/tariffs/{tariff-id}")
    public ResponseEntity<Void> deleteTariff(@PathVariable("tariff-id") long id) {
        tariffs.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** API-ENE-02 목록(시스템 기본값 포함) — ENE_READ */
    @GetMapping("/core/energy/emission-factors")
    public ApiResponse<List<EmissionFactorView>> factors() {
        return ApiResponse.success(factors.list());
    }

    /** API-ENE-02 추가 — ENE_ADMIN, 201 + Location */
    @PostMapping("/core/energy/emission-factors")
    @Idempotent
    public ResponseEntity<ApiResponse<EmissionFactorView>> createFactor(@RequestBody(required = false) EmissionFactorRequest request) {
        EmissionFactorView view = factors.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create(BASE + "emission-factors/" + view.emissionFactorId()))
                .body(ApiResponse.success(view));
    }

    /** API-ENE-02 수정 — ENE_ADMIN(시스템 기본값은 409) */
    @PutMapping("/core/energy/emission-factors/{emission-factor-id}")
    public ApiResponse<EmissionFactorView> updateFactor(@PathVariable("emission-factor-id") long id,
                                                        @RequestBody(required = false) EmissionFactorRequest request) {
        return ApiResponse.success(factors.update(id, request));
    }

    /** API-ENE-02 삭제 — ENE_ADMIN(시스템 기본값은 409) */
    @DeleteMapping("/core/energy/emission-factors/{emission-factor-id}")
    public ResponseEntity<Void> deleteFactor(@PathVariable("emission-factor-id") long id) {
        factors.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** API-ENE-04 목록 — ENE_READ */
    @GetMapping("/core/energy/sources")
    public ApiResponse<List<SourceView>> sources(@RequestParam(required = false) String spaceId) {
        return ApiResponse.success(sources.list(spaceId));
    }

    /** API-ENE-04 측정 후보 기기 — ENE_READ */
    @GetMapping("/core/energy/sources/candidates")
    public ApiResponse<List<SourceCandidate>> candidates(@RequestParam(required = false) String spaceId) {
        return ApiResponse.success(sources.candidates(spaceId));
    }

    /** API-ENE-04 연결 — ENE_ADMIN, 201 + Location */
    @PostMapping("/core/energy/sources")
    @Idempotent
    public ResponseEntity<ApiResponse<SourceView>> createSource(@RequestBody(required = false) SourceRequest request) {
        SourceView view = sources.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create(BASE + "sources/" + view.sourceId())).body(ApiResponse.success(view));
    }

    /** API-ENE-04 수정 — ENE_ADMIN */
    @PutMapping("/core/energy/sources/{source-id}")
    public ApiResponse<SourceView> updateSource(@PathVariable("source-id") long id, @RequestBody(required = false) SourceRequest request) {
        return ApiResponse.success(sources.update(id, request));
    }

    /** API-ENE-04 삭제 — ENE_ADMIN */
    @DeleteMapping("/core/energy/sources/{source-id}")
    public ResponseEntity<Void> deleteSource(@PathVariable("source-id") long id) {
        sources.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** API-ENE-05 목록 — ENE_READ */
    @GetMapping("/core/energy/bills")
    public ListApiResponse<BillView> bills(@RequestParam(required = false) String spaceId, @RequestParam(required = false) Integer year,
                                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return bills.list(spaceId, year, page, size);
    }

    /** API-ENE-05 입력·덮어쓰기 — ENE_ADMIN */
    @PutMapping("/core/energy/bills/{space-id}/{month}")
    public ApiResponse<BillView> putBill(@PathVariable("space-id") long spaceId, @PathVariable String month,
                                         @RequestBody(required = false) BillRequest request) {
        return ApiResponse.success(bills.put(spaceId, month, request));
    }

    /** API-ENE-05 삭제 — ENE_ADMIN */
    @DeleteMapping("/core/energy/bills/{space-id}/{month}")
    public ResponseEntity<Void> deleteBill(@PathVariable("space-id") long spaceId, @PathVariable String month) {
        bills.delete(spaceId, month);
        return ResponseEntity.noContent().build();
    }
}
