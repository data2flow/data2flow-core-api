package net.java21.data2flow.core.annotation.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.annotation.domain.AnnotationModels;
import net.java21.data2flow.core.annotation.domain.AnnotationModels.AnnotationErrorCode;
import net.java21.data2flow.core.annotation.dto.AnnotationDtos.AnnotationResponse;
import net.java21.data2flow.core.annotation.dto.AnnotationDtos.CreateAnnotationRequest;
import net.java21.data2flow.core.annotation.repository.AnnotationRepository;
import net.java21.data2flow.core.annotation.repository.AnnotationRepository.AnnotationRow;
import net.java21.data2flow.core.annotation.repository.AnnotationRepository.AnnotationSearch;
import net.java21.data2flow.core.annotation.repository.AnnotationRepository.DeviceSpace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 시계열 주석 조회·수동 작성·삭제(TSD-01.04, API-TSD-06·07, BR-TSD-23).
 *
 * <ul>
 *   <li>조회 TS_READ(VIEWER+). 기기를 고르면 그 기기 주석 + 기기 공간·조상 공간 주석 + 조직 전체 주석(AT-TSD-11.2), 공간을 고르면 그 공간·조상·
 *       하위 공간 주석 + 하위 공간 기기 주석 + 조직 전체 주석. 공간 범위(IAM-04.06): 기기 주석은 기기의 현재 공간을 따르고 조직 전체 주석은 모두 본다</li>
 *   <li>수동 작성 DEV_PLACE(OPERATOR+). 대상이 권한 밖이면 404. 조직 전체 주석은 공간 범위가 전체인 사용자만(403)</li>
 *   <li>삭제: 수동 주석은 작성자와 ADMIN만(403 ANNOTATION_FORBIDDEN), 시스템 주석은 지울 수 없다(403 ANNOTATION_FORBIDDEN)</li>
 * </ul>
 * 주석 생성 이벤트 EVT-TSD-05 {@code annotation.created}는 contracts EventType에 없어 발행하지 않는다.
 */
@Service
public class AnnotationService {

    /** 목록 기본 크기(차트 한 화면에 그릴 주석). size를 주지 않으면 최대 100 */
    static final int DEFAULT_SIZE = PageParams.MAX_SIZE;

    private final AnnotationRepository annotations;
    private final RoleChecker roleChecker;
    private final Clock clock;

    public AnnotationService(AnnotationRepository annotations, RoleChecker roleChecker, Clock clock) {
        this.annotations = annotations;
        this.roleChecker = roleChecker;
        this.clock = clock;
    }

    /** API-TSD-06 주석 목록 */
    @Transactional(readOnly = true)
    public ListApiResponse<AnnotationResponse> list(String deviceId, String spaceId, String from, String to, List<String> types,
                                                    String metric, Integer page, Integer size) {
        roleChecker.require(Permission.TS_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Instant fromAt = instant(from, "from");
        Instant toAt = instant(to, "to");
        if (!fromAt.isBefore(toAt)) {
            throw invalid("to", "Range");
        }
        List<String> storedTypes = types(types);
        String mode = "ALL";
        Long device = null;
        List<Long> ancestors = List.of();
        String subtree = null;
        if (deviceId != null && !deviceId.isBlank()) {
            DeviceSpace d = annotations.findDevice(orgId, id(deviceId, "deviceId"))
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.requireSpace(d.spaceId(), CommonErrorCode.RESOURCE_NOT_FOUND);
            mode = "DEVICE";
            device = d.id();
            ancestors = pathIds(d.path());
        } else if (spaceId != null && !spaceId.isBlank()) {
            long space = id(spaceId, "spaceId");
            String path = annotations.findSpacePath(orgId, space)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.requireSpace(space, CommonErrorCode.RESOURCE_NOT_FOUND);
            mode = "SPACE";
            ancestors = pathIds(path);
            subtree = path;
        }
        SpaceScope scope = roleChecker.spaceScope();
        AnnotationSearch search = new AnnotationSearch(orgId, fromAt, toAt, mode, device, ancestors, subtree, storedTypes,
                metric == null || metric.isBlank() ? null : metric.strip(), scope.unrestricted(), scope.allowedSpaceIds());
        PageParams params = PageParams.of(page, size == null ? DEFAULT_SIZE : size);
        List<AnnotationResponse> rows = annotations.search(search, params.size(), params.offset()).stream()
                .map(AnnotationService::toResponse).toList();
        return ListApiResponse.of(params, rows, annotations.countSearch(search));
    }

    /** API-TSD-07 수동 주석 작성 */
    @Transactional
    public AnnotationResponse create(CreateAnnotationRequest request) {
        roleChecker.require(Permission.DEV_PLACE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (request.timeTo() != null && request.timeTo().isBefore(request.timeFrom())) {
            throw invalid("timeTo", "Range");
        }
        String title = request.title().strip();
        Long deviceId = null;
        Long spaceId = null;
        if (request.deviceId() != null && !request.deviceId().isBlank()) {
            DeviceSpace d = annotations.findDevice(orgId, id(request.deviceId(), "deviceId"))
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.require(Permission.DEV_PLACE, d.spaceId(), CommonErrorCode.RESOURCE_NOT_FOUND);
            deviceId = d.id();
        }
        if (request.spaceId() != null && !request.spaceId().isBlank()) {
            long space = id(request.spaceId(), "spaceId");
            annotations.findSpacePath(orgId, space).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.require(Permission.DEV_PLACE, space, CommonErrorCode.RESOURCE_NOT_FOUND);
            spaceId = space;
        }
        if (deviceId == null && spaceId == null && !roleChecker.spaceScope().unrestricted()) {
            // 조직 전체 주석은 모든 사용자에게 보이므로 공간 범위가 제한된 사용자는 만들 수 없다
            throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
        }
        long id = annotations.insert(orgId, request.timeFrom(), request.timeTo(), deviceId, spaceId, request.metricKey(),
                AnnotationModels.MANUAL, title, null, user.userId(), clock.instant());
        return toResponse(annotations.findById(orgId, id).orElseThrow());
    }

    /** API-TSD-07 삭제 */
    @Transactional
    public void delete(long annotationId) {
        roleChecker.require(Permission.DEV_PLACE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        AnnotationRow row = annotations.findById(orgId, annotationId)
                .orElseThrow(() -> new BusinessException(AnnotationErrorCode.ANNOTATION_NOT_FOUND));
        roleChecker.requireSpace(row.effectiveSpaceId(), AnnotationErrorCode.ANNOTATION_NOT_FOUND);
        boolean manual = AnnotationModels.MANUAL.equals(row.type());
        boolean author = row.createdBy() != null && row.createdBy() == user.userId();
        if (!manual || !(author || roleChecker.has(Permission.IAM_MANAGE))) {
            throw new BusinessException(AnnotationErrorCode.ANNOTATION_FORBIDDEN);
        }
        annotations.delete(orgId, annotationId);
    }

    static AnnotationResponse toResponse(AnnotationRow r) {
        return new AnnotationResponse(Long.toString(r.id()), r.timeFrom(), r.timeTo(), str(r.deviceId()), str(r.spaceId()), r.metricKey(),
                AnnotationModels.apiType(r.type()), r.title(), r.ref(), str(r.createdBy()), r.createdAt());
    }

    /** types=ALARM,OFFLINE 또는 types=ALARM&types=OFFLINE. 비면 전체, 모르는 값은 400 */
    static List<String> types(List<String> raw) {
        if (raw == null) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String item : raw) {
            for (String part : item.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                out.add(AnnotationModels.storedType(part).orElseThrow(() -> invalid("types", "Enum")));
            }
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
    }

    /** 공간 경로 "/1/4/9/" → [1, 4, 9] */
    static List<Long> pathIds(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        return Arrays.stream(path.split("/")).filter(s -> !s.isBlank()).map(Long::parseLong).toList();
    }

    private static Instant instant(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalid(field, "NotNull");
        }
        try {
            return Instant.parse(raw.strip());
        } catch (DateTimeParseException ex) {
            throw invalid(field, "Pattern");
        }
    }

    private static long id(String raw, String field) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException ex) {
            throw invalid(field, "Pattern");
        }
    }

    private static String str(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
