package net.java21.data2flow.core.telemetry.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.message.CanonicalTelemetry;
import net.java21.data2flow.contracts.web.ApiHeader;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.telemetry.service.TelemetryHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** API-FLW-87 과거 텔레메트리(내부: flow-engine 과거 재생 원천, ADR-051). 기간 ≤ 7일, 쪽 크기 ≤ 1,000, 측정 시각 순서 */
@RestController
public class InternalTelemetryHistoryController {

    private final TelemetryHistoryService history;
    private final InternalOrganizations organizations;

    public InternalTelemetryHistoryController(TelemetryHistoryService history, InternalOrganizations organizations) {
        this.history = history;
        this.organizations = organizations;
    }

    /** 목록 {page, size, totalPages, responses:[CanonicalTelemetry], totalCount(첫 쪽만, 다음 쪽은 -1), nextCursor} */
    public record HistoryPage(ApiHeader header, int page, int size, int totalPages, List<CanonicalTelemetry> responses, long totalCount,
                              String nextCursor) {
    }

    @GetMapping("/internal/core/telemetry/history")
    public HistoryPage history(@RequestParam(required = false) Long organizationId, @RequestParam String from, @RequestParam String to,
                               @RequestParam(required = false) List<String> deviceIds, @RequestParam(required = false) String cursor,
                               @RequestParam(required = false) Integer size) {
        long org = organizations.resolve(organizationId);
        Instant f = instant(from, "from");
        Instant t = instant(to, "to");
        if (!t.isAfter(f) || Duration.between(f, t).compareTo(TelemetryHistoryService.MAX_RANGE) > 0) {
            throw invalid("to");
        }
        int n = size == null ? TelemetryHistoryService.MAX_SIZE : Math.max(1, Math.min(size, TelemetryHistoryService.MAX_SIZE));
        List<Long> ids = new ArrayList<>();
        if (deviceIds != null) {
            for (String raw : deviceIds) {
                for (String one : raw.split(",")) {
                    if (!one.isBlank()) {
                        try {
                            ids.add(Long.parseLong(one.strip()));
                        } catch (NumberFormatException ex) {
                            throw invalid("deviceIds");
                        }
                    }
                }
            }
        }
        TelemetryHistoryService.Page p;
        try {
            p = history.page(org, f, t, ids, cursor, n);
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ex) {
            throw invalid("cursor");
        }
        int pages = p.totalCount() < 0 ? -1 : (int) Math.ceil(p.totalCount() / (double) n);
        return new HistoryPage(ApiHeader.success(), 1, n, pages, p.items(), p.totalCount(), p.nextCursor());
    }

    static Instant instant(String raw, String field) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ex) {
            throw invalid(field);
        }
    }

    static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
