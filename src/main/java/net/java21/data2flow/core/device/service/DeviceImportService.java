package net.java21.data2flow.core.device.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.device.domain.Csv;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.domain.DeviceFilter;
import net.java21.data2flow.core.device.domain.DeviceListRow;
import net.java21.data2flow.core.device.domain.DeviceRules;
import net.java21.data2flow.core.device.dto.DeviceDtos.ImportReport;
import net.java21.data2flow.core.device.dto.DeviceDtos.ImportRow;
import net.java21.data2flow.core.device.repository.DeviceQueryRepository;
import net.java21.data2flow.core.device.repository.DeviceReferenceRepository;
import net.java21.data2flow.core.device.service.DeviceService.Registration;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CSV 일괄 등록·내보내기(DEV-02.04, API-DEV-19·20). 머리글(가져오기 템플릿 = 내보내기, 웹 {@code CSV_TEMPLATE_HEADER}와 같음):
 * {@code sourceId,externalId,name,kind,modelCode,spaceId,expectedIntervalSec,offlineMultiplier,tags,virtual}.
 * 앞 6개는 필수, 나머지는 비워도 된다. 태그는 칸 하나에 {@code ;}(또는 {@code |}, 따옴표로 감싼 {@code ,})로 나눈다.
 * 순서는 바뀌어도 되고 모르는 머리글은 400. 첫 줄이 머리글이라 데이터는 2번 줄부터이며 결과 {@code line}은 파일의 줄 번호다.
 * 가져온 기기는 모델·공간이 있으므로 ACTIVE다(수동 등록과 같음).
 */
@Service
public class DeviceImportService {

    public static final List<String> HEADER = List.of("sourceId", "externalId", "name", "kind", "modelCode", "spaceId",
            "expectedIntervalSec", "offlineMultiplier", "tags", "virtual");
    static final Set<String> REQUIRED = Set.of("sourceId", "externalId", "name", "kind", "modelCode", "spaceId");
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_ROWS = 5000;
    static final int EXPORT_PAGE = 500;

    private final RoleChecker roleChecker;
    private final DeviceService deviceService;
    private final DeviceReferenceRepository refs;
    private final DeviceQueryRepository queries;
    private final Audits audits;
    private final MessageSource messages;

    public DeviceImportService(RoleChecker roleChecker, DeviceService deviceService, DeviceReferenceRepository refs,
                               DeviceQueryRepository queries, Audits audits, MessageSource messages) {
        this.roleChecker = roleChecker;
        this.deviceService = deviceService;
        this.refs = refs;
        this.queries = queries;
        this.audits = audits;
        this.messages = messages;
    }

    /**
     * API-DEV-19. dryRun(기본 true)이면 검증만 한다. SKIP_ERRORS는 맞는 행만 등록하고, ALL_OR_NOTHING은 한 행이라도 틀리면
     * 아무것도 등록하지 않는다(AT-DEV-04.2·04.3).
     */
    @Transactional
    public ImportReport importCsv(byte[] content, boolean dryRun, String mode) {
        roleChecker.require(Permission.DEV_ADMIN);
        boolean allOrNothing = "ALL_OR_NOTHING".equalsIgnoreCase(mode);
        if (mode != null && !allOrNothing && !"SKIP_ERRORS".equalsIgnoreCase(mode)) {
            throw invalid("mode", "INVALID");
        }
        if (content == null || content.length == 0) {
            throw invalid("file", "REQUIRED");
        }
        if (content.length > MAX_BYTES) {
            throw invalid("file", "TOO_LARGE");
        }
        List<Csv.Line> lines = Csv.parse(new String(content, StandardCharsets.UTF_8));
        if (lines.isEmpty()) {
            throw invalid("file", "REQUIRED");
        }
        Map<String, Integer> columns = header(lines.get(0).cells());
        List<Csv.Line> data = lines.subList(1, lines.size());
        if (data.size() > MAX_ROWS) {
            throw invalid("file", "TOO_MANY_ROWS");
        }
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        List<ImportRow> rows = new ArrayList<>();
        List<Registration> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Csv.Line line : data) {
            try {
                Registration r = registration(org, line, columns);
                String ext = deviceService.validate(r, "tags");
                if (!seen.add(r.sourceId() + "/" + ext)) {
                    throw new BusinessException(DeviceErrorCode.DEVICE_DUPLICATE);
                }
                valid.add(r);
                rows.add(new ImportRow(line.number(), true, null, null));
            } catch (BusinessException ex) {
                rows.add(new ImportRow(line.number(), false, ex.getErrorCode().code(), message(ex)));
            }
        }
        int failed = data.size() - valid.size();
        boolean write = !dryRun && !(allOrNothing && failed > 0);
        if (write) {
            for (Registration r : valid) {
                deviceService.register(r, "tags");
            }
            audits.record(audits.event(org, DeviceAudits.DEVICE_IMPORTED).actor(user).target("DEVICE", "import")
                    .detail("total", data.size()).detail("succeeded", valid.size()).detail("failed", failed).detail("mode",
                            allOrNothing ? "ALL_OR_NOTHING" : "SKIP_ERRORS"));
        }
        int succeeded = dryRun || write ? valid.size() : 0;
        return new ImportReport(data.size(), succeeded, data.size() - succeeded, rows);
    }

    /** API-DEV-20: 목록 조건(공간 범위 포함)에 맞는 기기를 템플릿 머리글로 내보낸다(AT-DEV-04.4) */
    @Transactional(readOnly = true)
    public void exportCsv(DeviceService.ListQuery query, Writer out) throws IOException {
        roleChecker.require(Permission.DEV_READ);
        DeviceFilter filter = deviceService.filter(query);
        out.write('﻿');
        out.write(Csv.row(HEADER));
        long offset = 0;
        while (true) {
            List<DeviceListRow> page = queries.list(filter, "d.name ASC", EXPORT_PAGE, offset);
            for (DeviceListRow r : page) {
                var d = r.device();
                out.write(Csv.row(Arrays.asList(Long.toString(d.sourceId()), d.externalId(), d.name(), d.kind(), r.modelCode(),
                        d.spaceId() == null ? "" : Long.toString(d.spaceId()),
                        d.expectedIntervalSec() == null ? "" : Integer.toString(d.expectedIntervalSec()),
                        d.offlineMultiplier() == null ? "" : d.offlineMultiplier().toPlainString(), String.join(";", r.tags()),
                        Boolean.toString(d.virtual()))));
            }
            if (page.size() < EXPORT_PAGE) {
                break;
            }
            offset += EXPORT_PAGE;
        }
        out.flush();
    }

    private Map<String, Integer> header(List<String> cells) {
        Map<String, Integer> columns = new HashMap<>();
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            String name = cells.get(i).strip();
            if (!HEADER.contains(name) || columns.containsKey(name)) {
                errors.add(new FieldErrorDetail("header", "UNKNOWN_COLUMN", name));
            } else {
                columns.put(name, i);
            }
        }
        for (String required : REQUIRED) {
            if (!columns.containsKey(required)) {
                errors.add(new FieldErrorDetail("header", "MISSING_COLUMN", required));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        return columns;
    }

    private Registration registration(long org, Csv.Line line, Map<String, Integer> columns) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        String source = cell(line, columns, "sourceId");
        String space = cell(line, columns, "spaceId");
        String interval = cell(line, columns, "expectedIntervalSec");
        String multiplier = cell(line, columns, "offlineMultiplier");
        String virtual = cell(line, columns, "virtual");
        Long sourceId = number(source, "sourceId", errors);
        Long spaceId = number(space, "spaceId", errors);
        Integer intervalSec = null;
        if (!interval.isEmpty()) {
            Long v = number(interval, "expectedIntervalSec", errors);
            intervalSec = v == null ? null : (int) Math.min(Integer.MAX_VALUE, v);
        }
        BigDecimal mult = null;
        if (!multiplier.isEmpty()) {
            try {
                mult = new BigDecimal(multiplier);
            } catch (NumberFormatException ex) {
                errors.add(new FieldErrorDetail("offlineMultiplier", "INVALID", null));
            }
        }
        if (!virtual.isEmpty() && !Set.of("true", "false").contains(virtual.toLowerCase(Locale.ROOT))) {
            errors.add(new FieldErrorDetail("virtual", "INVALID", null));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        String modelCode = cell(line, columns, "modelCode");
        long modelId = refs.findModelByCode(org, modelCode).map(m -> m.id())
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.MODEL_NOT_FOUND));
        List<String> tags = Arrays.stream(cell(line, columns, "tags").split("[;|,]")).map(String::strip).filter(t -> !t.isEmpty()).toList();
        return new Registration(sourceId, cell(line, columns, "externalId"), cell(line, columns, "name"),
                cell(line, columns, "kind").toUpperCase(Locale.ROOT), modelId, spaceId, intervalSec, mult, tags,
                "true".equalsIgnoreCase(virtual));
    }

    private static String cell(Csv.Line line, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null || index >= line.cells().size()) {
            return "";
        }
        String v = line.cells().get(index).strip();
        // 내보내기가 수식 주입을 막으려 붙인 ' 를 되돌린다
        if (v.length() > 1 && v.charAt(0) == '\'' && "=+-@".indexOf(v.charAt(1)) >= 0) {
            v = v.substring(1);
        }
        return v;
    }

    private static Long number(String raw, String field, List<FieldErrorDetail> errors) {
        if (raw == null || !raw.matches("\\d{1,18}")) {
            errors.add(new FieldErrorDetail(field, raw == null || raw.isEmpty() ? "REQUIRED" : "INVALID", null));
            return null;
        }
        return Long.valueOf(raw);
    }

    private String message(BusinessException ex) {
        Locale locale = LocaleContextHolder.getLocale();
        String text = messages.getMessage(ex.getErrorCode().messageKey(), ex.getArgs(), ex.getErrorCode().code(), locale);
        if (!ex.getErrors().isEmpty()) {
            String fields = ex.getErrors().stream().map(FieldErrorDetail::field).distinct().collect(Collectors.joining(", "));
            text = text + " (" + messages.getMessage("device.import.field", new Object[] {fields}, fields, locale) + ")";
        }
        return text;
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
