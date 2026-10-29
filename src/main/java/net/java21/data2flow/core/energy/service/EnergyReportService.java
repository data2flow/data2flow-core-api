package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.dto.EnergyReportDtos.Accepted;
import net.java21.data2flow.core.energy.dto.EnergyReportDtos.CreateReportRequest;
import net.java21.data2flow.core.energy.dto.EnergyReportDtos.ReportView;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.UserRef;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository;
import net.java21.data2flow.core.energy.repository.EnergyReportRepository;
import net.java21.data2flow.core.energy.repository.EnergyReportRepository.ReportRow;
import net.java21.data2flow.core.energy.service.EnergyMetricsService.Prepared;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
import net.java21.data2flow.core.filestore.service.StoredFiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.java21.data2flow.core.energy.service.EnergyAccess.invalid;

/**
 * 월간 에너지 리포트(ENE-01.05, API-ENE-13, UC-ENE-05). 생성(ENE_ANALYZE)은 GENERATING 행만 넣고 202, {@link EnergyReportGenerator}가 만든다.
 * 조회·파일(ENE_READ)은 요청자 공간 범위 안의 리포트만(포트폴리오 리포트는 범위 제한이 없는 사용자만) 보인다
 */
@Service
public class EnergyReportService {

    static final String PURPOSE = "ENE_REPORT";

    private final EnergyReportRepository reports;
    private final EnergyMetricsService metrics;
    private final EnergyConfigRepository config;
    private final RoleChecker roleChecker;
    private final StoredFiles files;
    private final JsonMapper json;
    private final Clock clock;

    public EnergyReportService(EnergyReportRepository reports, EnergyMetricsService metrics, EnergyConfigRepository config, RoleChecker roleChecker,
                               StoredFiles files, JsonMapper json, Clock clock) {
        this.reports = reports;
        this.metrics = metrics;
        this.config = config;
        this.roleChecker = roleChecker;
        this.files = files;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Accepted create(CreateReportRequest r) {
        roleChecker.require(Permission.ENE_ANALYZE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (r == null || r.month() == null || r.month().isBlank()) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "month", "NotNull");
        }
        YearMonth month;
        try {
            month = YearMonth.parse(r.month().strip());
        } catch (DateTimeParseException ex) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "month", "YearMonth");
        }
        if (month.isAfter(YearMonth.now(clock.withZone(ZoneId.of(config.timezone(orgId)))))) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "month", "Future");
        }
        String format = r.format() == null || r.format().isBlank() ? "PDF" : r.format().strip().toUpperCase(Locale.ROOT);
        if (!format.equals("PDF") && !format.equals("JSON")) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "format", "PDF, JSON");
        }
        Prepared p = metrics.prepare(r.scopeSpaceId(), month.atDay(1).toString(), month.atEndOfMonth().toString(), "DAY", "BASELINE");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("body", p.body());
        request.put("baselineVersionId", p.baselineVersionId());
        request.put("scopeName", p.scopeName());
        Long scope = (Long) p.body().get("scopeSpaceId");
        long id = reports.insert(orgId, scope, month.atDay(1), format, json.writeValueAsString(request), user.userId(), clock.instant());
        return new Accepted(Long.toString(id), Long.toString(id));
    }

    @Transactional(readOnly = true)
    public ListApiResponse<ReportView> list(String scopeSpaceId, Integer page, Integer size) {
        roleChecker.require(Permission.ENE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Long scope = EnergyAccess.id(scopeSpaceId, EnergyErrorCode.SPACE_NOT_FOUND);
        SpaceScope allowed = roleChecker.spaceScope();
        List<ReportRow> rows = reports.list(orgId, scope).stream().filter(row -> visible(allowed, row)).toList();
        PageParams p = PageParams.of(page, size);
        int start = (int) Math.min(p.offset(), rows.size());
        return ListApiResponse.of(p, rows.subList(start, Math.min(start + p.size(), rows.size())).stream().map(row -> view(row, false)).toList(),
                rows.size());
    }

    @Transactional(readOnly = true)
    public ReportView get(String reportId) {
        return view(find(reportId), true);
    }

    /** PDF 내려받기. 아직 없거나 JSON 리포트면 409 */
    @Transactional(readOnly = true)
    public Blob file(String reportId) {
        ReportRow row = find(reportId);
        if (row.fileObjectKey() == null) {
            throw new BusinessException(EnergyErrorCode.ENE_REPORT_NOT_READY);
        }
        return files.load(row.organizationId(), row.fileObjectKey()).orElseThrow(() -> new BusinessException(EnergyErrorCode.ENE_REPORT_NOT_FOUND));
    }

    private ReportRow find(String reportId) {
        roleChecker.require(Permission.ENE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        long id = EnergyAccess.id(reportId, EnergyErrorCode.ENE_REPORT_NOT_FOUND);
        ReportRow row = reports.find(orgId, id).orElseThrow(() -> new BusinessException(EnergyErrorCode.ENE_REPORT_NOT_FOUND));
        if (!visible(roleChecker.spaceScope(), row)) {
            throw new BusinessException(EnergyErrorCode.ENE_REPORT_NOT_FOUND);
        }
        return row;
    }

    private static boolean visible(SpaceScope allowed, ReportRow row) {
        return row.scopeSpaceId() == null ? allowed.unrestricted() : allowed.includes(row.scopeSpaceId());
    }

    private ReportView view(ReportRow r, boolean withModel) {
        return new ReportView(Long.toString(r.id()), r.scopeSpaceId() == null ? null : Long.toString(r.scopeSpaceId()), r.scopeSpaceName(),
                YearMonth.from(r.month()).toString(), r.format(), r.status(), r.progress(), r.sha256(), r.sizeBytes(),
                r.fileObjectKey() == null ? null : "/api/v1/core/energy/reports/" + r.id() + "/file", r.failureReason(),
                r.createdBy() == 0 ? null : new UserRef(Long.toString(r.createdBy()), r.createdByName()), r.createdAt(),
                withModel && r.modelJson() != null ? json.readTree(r.modelJson()) : null);
    }
}
