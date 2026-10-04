package net.java21.data2flow.core.devicesearch.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.FailureWithResponse;
import net.java21.data2flow.core.devicesearch.domain.DeviceQuery;
import net.java21.data2flow.core.devicesearch.domain.DeviceQueryException;
import net.java21.data2flow.core.devicesearch.domain.DeviceQueryParser;
import net.java21.data2flow.core.devicesearch.domain.DeviceQuerySql;
import net.java21.data2flow.core.devicesearch.dto.DeviceSearchDtos.SavedSearchRequest;
import net.java21.data2flow.core.devicesearch.dto.DeviceSearchDtos.SavedSearchResponse;
import net.java21.data2flow.core.devicesearch.repository.SavedSearchRepository;
import net.java21.data2flow.core.devicesearch.repository.SavedSearchRepository.SavedSearch;
import net.java21.data2flow.core.workorder.domain.WorkOrderErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장된 기기 검색(DEV-13.03, API-DEV-134). 저장할 때 구문을 검사하고 AST를 남기며, 실행은 매번 다시 해석한다(실행 시점의 결과, AT-DEV-25.3).
 * 조회 DEV_READ(본인 것 + 공유), 저장 DEV_PLACE, 수정·삭제는 만든 사람 또는 ADMIN.
 */
@Service
public class SavedSearchService {

    static final String AUDIT_SAVED = "SAVED_SEARCH_CHANGED";

    private final RoleChecker roleChecker;
    private final SavedSearchRepository searches;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public SavedSearchService(RoleChecker roleChecker, SavedSearchRepository searches, Audits audits, JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.searches = searches;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ListApiResponse<SavedSearchResponse> list(Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        CurrentUser user = roleChecker.currentUser();
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, searches.listVisible(user.organizationId(), user.userId(), params.size(), params.offset()).stream()
                .map(SavedSearchService::toResponse).toList(), searches.countVisible(user.organizationId(), user.userId()));
    }

    @Transactional(readOnly = true)
    public SavedSearchResponse get(long id) {
        roleChecker.require(Permission.DEV_READ);
        return toResponse(visible(id));
    }

    @Transactional
    public SavedSearchResponse create(SavedSearchRequest req) {
        roleChecker.require(Permission.DEV_PLACE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        String name = req.name().strip();
        String ast = compile(req.query());
        if (searches.existsName(org, user.userId(), name, null)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicate", null)));
        }
        long id = searches.insert(org, user.userId(), name, req.query().strip(), ast, Boolean.TRUE.equals(req.shared()), clock.instant());
        audits.record(audits.event(org, AUDIT_SAVED).actor(user).target("SAVED_SEARCH", Long.toString(id)).detail("op", "CREATE"));
        return toResponse(searches.find(org, id).orElseThrow());
    }

    @Transactional
    public SavedSearchResponse update(long id, SavedSearchRequest req) {
        roleChecker.require(Permission.DEV_PLACE);
        CurrentUser user = roleChecker.currentUser();
        SavedSearch s = owned(id);
        String name = req.name().strip();
        String ast = compile(req.query());
        if (searches.existsName(s.organizationId(), s.ownerId(), name, id)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicate", null)));
        }
        searches.update(s.organizationId(), id, name, req.query().strip(), ast, Boolean.TRUE.equals(req.shared()), clock.instant());
        audits.record(audits.event(s.organizationId(), AUDIT_SAVED).actor(user).target("SAVED_SEARCH", Long.toString(id)).detail("op", "UPDATE"));
        return toResponse(searches.find(s.organizationId(), id).orElseThrow());
    }

    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.DEV_PLACE);
        CurrentUser user = roleChecker.currentUser();
        SavedSearch s = owned(id);
        searches.delete(s.organizationId(), id);
        audits.record(audits.event(s.organizationId(), AUDIT_SAVED).actor(user).target("SAVED_SEARCH", Long.toString(id)).detail("op", "DELETE"));
    }

    /** 구문 검사(SQL로 바꿔 필드·값 종류까지) 후 AST JSON */
    private String compile(String query) {
        try {
            DeviceQuery ast = DeviceQueryParser.parse(query);
            DeviceQuerySql.toSql(ast, clock.instant());
            return json.writeValueAsString(ast);
        } catch (DeviceQueryException ex) {
            Map<String, Object> where = new LinkedHashMap<>();
            where.put("column", ex.column());
            where.put("message", ex.getMessage());
            throw new FailureWithResponse(WorkOrderErrorCode.DEVICE_QUERY_INVALID,
                    List.of(new FieldErrorDetail("query", "SYNTAX", ex.getMessage())), where, ex.column());
        }
    }

    private SavedSearch visible(long id) {
        CurrentUser user = roleChecker.currentUser();
        SavedSearch s = searches.find(user.organizationId(), id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!s.shared() && s.ownerId() != user.userId()) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return s;
    }

    private SavedSearch owned(long id) {
        CurrentUser user = roleChecker.currentUser();
        SavedSearch s = visible(id);
        if (s.ownerId() != user.userId() && !roleChecker.has(Permission.IAM_MANAGE)) {
            throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
        }
        return s;
    }

    static SavedSearchResponse toResponse(SavedSearch s) {
        return new SavedSearchResponse(Long.toString(s.id()), s.name(), s.query(), s.shared(), Long.toString(s.ownerId()), s.ownerName(),
                s.updatedAt());
    }
}
