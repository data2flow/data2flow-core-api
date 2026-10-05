package net.java21.data2flow.core.apitoken.service;

import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.apitoken.domain.ApiTokenErrorCode;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.ServiceAccountRequest;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.ServiceAccountResponse;
import net.java21.data2flow.core.apitoken.repository.ApiTokenRepository;
import net.java21.data2flow.core.apitoken.repository.ServiceAccountRepository;
import net.java21.data2flow.core.apitoken.repository.ServiceAccountRepository.AccountRow;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.outbox.service.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 서비스 계정(IAM-05.01, API-IAM-45, BR-IAM-35). ADMIN만, 화면에서만(장기 토큰 요청은 403). 로그인 아이디가 없어 웹 로그인은 할 수 없고
 * ADMIN이 발급한 API 키로만 인증한다. 비활성화하면 그 계정의 키를 모두 폐기하고 gateway 캐시 삭제를 알린다(1분 이내 401, AT-IAM-18.3).
 */
@Service
public class ServiceAccountService {

    private final RoleChecker roleChecker;
    private final ServiceAccountRepository accounts;
    private final ApiTokenRepository tokens;
    private final OutboxWriter outbox;
    private final Audits audits;
    private final Clock clock;

    public ServiceAccountService(RoleChecker roleChecker, ServiceAccountRepository accounts, ApiTokenRepository tokens, OutboxWriter outbox,
                                 Audits audits, Clock clock) {
        this.roleChecker = roleChecker;
        this.accounts = accounts;
        this.tokens = tokens;
        this.outbox = outbox;
        this.audits = audits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ListApiResponse<ServiceAccountResponse> list(Integer page, Integer size) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        long orgId = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, accounts.list(orgId, params.size(), params.offset()).stream().map(ServiceAccountService::response)
                .toList(), accounts.count(orgId));
    }

    /** 만들기 — 201. 이름 1~50자, 조직 안 고유(대소문자 무시), 설명 200자 이하 */
    @Transactional
    public ServiceAccountResponse create(ServiceAccountRequest req) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String name = req == null || req.name() == null ? "" : req.name().strip();
        if (req == null || name.isEmpty() || name.length() > 50) {
            throw invalid("name", "Size", null);
        }
        String description = req.description() == null || req.description().isBlank() ? null : req.description().strip();
        if (description != null && description.length() > 200) {
            throw invalid("description", "Size", null);
        }
        if (accounts.existsName(orgId, name)) {
            throw invalid("name", "Duplicated", name);
        }
        long id = accounts.insert(orgId, name, description, user.userId(), clock.instant());
        audits.record(audits.event(orgId, "SERVICE_ACCOUNT_CREATED").actor(user).target("SERVICE_ACCOUNT", Long.toString(id))
                .detail("name", name));
        return response(accounts.find(orgId, id).orElseThrow());
    }

    /** 비활성화 — 그 계정의 키는 즉시 거부(BR-IAM-35). 이미 비활성이면 그대로 */
    @Transactional
    public ServiceAccountResponse disable(long accountId) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        AccountRow account = accounts.find(orgId, accountId)
                .orElseThrow(() -> new BusinessException(ApiTokenErrorCode.SERVICE_ACCOUNT_NOT_FOUND));
        Instant now = clock.instant();
        if (accounts.disable(orgId, account.id(), user.userId(), now) > 0) {
            List<String> ids = tokens.listLiveIdsOfServiceAccount(orgId, account.id()).stream().map(String::valueOf).toList();
            tokens.revokeAllOfServiceAccount(orgId, account.id(), now);
            outbox.authBlacklist(orgId, List.of(), List.of(), ids, "SERVICE_ACCOUNT_DISABLED");
            audits.record(audits.event(orgId, "SERVICE_ACCOUNT_DISABLED").actor(user).target("SERVICE_ACCOUNT", Long.toString(account.id()))
                    .detail("name", account.name()).detail("revokedTokens", ids.size()));
        }
        return response(accounts.find(orgId, account.id()).orElseThrow());
    }

    static ServiceAccountResponse response(AccountRow a) {
        return new ServiceAccountResponse(Long.toString(a.id()), a.name(), a.description(), a.status(), a.tokenCount(), a.version(),
                a.createdAt());
    }

    private static BusinessException invalid(String field, String code, String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, message)));
    }
}
