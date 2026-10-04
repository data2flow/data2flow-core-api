package net.java21.data2flow.core.retention.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.retention.domain.DataClass;
import net.java21.data2flow.core.retention.domain.RetentionErrorCode;
import net.java21.data2flow.core.retention.dto.RetentionDtos.ArchiveFileRequest;
import net.java21.data2flow.core.retention.dto.RetentionDtos.ArchiveFileResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.IdResponse;
import net.java21.data2flow.core.retention.repository.ArchiveFileRepository;
import net.java21.data2flow.core.retention.repository.ArchiveFileRepository.ArchiveRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 콜드 보관 파일(TSD-05.02, BR-TSD-18): pipeline 등록 API-TSD-61(업로드·체크섬 확인 뒤, 등록이 끝나야 원본을 지운다)과 관리자 목록
 * API-TSD-33. 복원(API-TSD-33 restore)은 가져오기 작업(TSD-04.02 import_jobs)으로 하므로 가져오기 기능과 함께 붙인다.
 */
@Service
public class ArchiveFileService {

    private final RoleChecker roleChecker;
    private final ArchiveFileRepository archives;
    private final InternalOrganizations organizations;
    private final Clock clock;

    public ArchiveFileService(RoleChecker roleChecker, ArchiveFileRepository archives, InternalOrganizations organizations, Clock clock) {
        this.roleChecker = roleChecker;
        this.archives = archives;
        this.organizations = organizations;
        this.clock = clock;
    }

    /** API-TSD-33 파일 목록(기간이 겹치는 파일, 최신 구간부터) */
    @Transactional(readOnly = true)
    public ListApiResponse<ArchiveFileResponse> list(String dataClass, Instant from, Instant to, Integer page, Integer size) {
        roleChecker.require(Permission.TS_POLICY);
        long org = roleChecker.currentUser().organizationId();
        String dc = null;
        if (dataClass != null && !dataClass.isBlank()) {
            dc = DataClass.parse(dataClass).map(Enum::name).orElseThrow(() -> invalid("dataClass"));
        }
        if (from != null && to != null && !to.isAfter(from)) {
            throw invalid("to");
        }
        PageParams params = PageParams.of(page, size);
        List<ArchiveFileResponse> items = archives.list(org, dc, from, to, params.size(), params.offset()).stream()
                .map(ArchiveFileService::toResponse).toList();
        return ListApiResponse.of(params, items, archives.count(org, dc, from, to));
    }

    /** API-TSD-33 파일 하나. 없으면(다른 조직 포함) 404 ARCHIVE_NOT_FOUND */
    @Transactional(readOnly = true)
    public ArchiveFileResponse get(long archiveId) {
        roleChecker.require(Permission.TS_POLICY);
        long org = roleChecker.currentUser().organizationId();
        return archives.findById(org, archiveId).map(ArchiveFileService::toResponse)
                .orElseThrow(() -> new BusinessException(RetentionErrorCode.ARCHIVE_NOT_FOUND));
    }

    /**
     * API-TSD-61 등록(내부, pipeline). 배포 조직(ACTIVE) 밖이면 404. 같은 객체 키를 다시 등록하면 같은 ID(재시도 멱등).
     * 검증: 종류, 구간(from &lt; to), 객체 키(≤300자), 형식 PARQUET, 행 수·크기 0 이상, 체크섬 SHA-256 16진 64자
     */
    @Transactional
    public IdResponse register(ArchiveFileRequest r) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        Long org = parseLong(r.organizationId());
        if (org == null) {
            errors.add(new FieldErrorDetail("organizationId", "INVALID", null));
        }
        DataClass dc = DataClass.parse(r.dataClass()).orElse(null);
        if (dc == null) {
            errors.add(new FieldErrorDetail("dataClass", "INVALID", null));
        }
        if (r.rangeFrom() == null || r.rangeTo() == null || !r.rangeTo().isAfter(r.rangeFrom())) {
            errors.add(new FieldErrorDetail("rangeTo", "INVALID", null));
        }
        if (r.objectKey() == null || r.objectKey().isBlank() || r.objectKey().length() > 300) {
            errors.add(new FieldErrorDetail("objectKey", "INVALID", null));
        }
        String format = r.format() == null ? "PARQUET" : r.format().strip().toUpperCase(Locale.ROOT);
        if (!"PARQUET".equals(format)) {
            errors.add(new FieldErrorDetail("format", "INVALID", null));
        }
        if (r.rowsCount() == null || r.rowsCount() < 0) {
            errors.add(new FieldErrorDetail("rowsCount", "INVALID", null));
        }
        if (r.bytes() == null || r.bytes() < 0) {
            errors.add(new FieldErrorDetail("bytes", "INVALID", null));
        }
        if (r.checksum() == null || !r.checksum().strip().toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")) {
            errors.add(new FieldErrorDetail("checksum", "INVALID", null));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        if (!organizations.deploymentOrganizations().contains(org)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        long id = archives.insertOrGet(org, dc.name(), r.rangeFrom(), r.rangeTo(), r.objectKey().strip(), format, r.rowsCount(),
                r.bytes(), r.checksum().strip().toLowerCase(Locale.ROOT), clock.instant());
        return new IdResponse(Long.toString(id));
    }

    static ArchiveFileResponse toResponse(ArchiveRow a) {
        return new ArchiveFileResponse(Long.toString(a.id()), a.dataClass(), a.rangeFrom(), a.rangeTo(), a.objectKey(), a.format(),
                a.rowsCount(), a.bytes(), a.checksum(), a.restoredJobId() == null ? null : Long.toString(a.restoredJobId()),
                a.createdAt());
    }

    private static Long parseLong(String raw) {
        return raw == null || !raw.strip().matches("\\d{1,18}") ? null : Long.valueOf(raw.strip());
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
