package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository.AnalysisRef;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 분석 API의 권한 표기(ANA-api 머리말: V=VIEWER, A=ANALYST, O=OPERATOR, I=INTEGRATOR, AD=ADMIN, "A 이상")를 권한 판정으로 옮긴다.
 * <ul>
 *   <li>V 이상 = ANALYTICS_READ, A 이상 = ANALYTICS_RUN</li>
 *   <li>O·I 이상은 ANALYTICS_RUN에 역할 순위를 더 본다. 사용자 정의 역할은 그 순위를 대표하는 권한으로 판정한다
 *       (O = ALARM_HANDLE, I = SCRIPT_WRITE). 서비스 계정 토큰은 A로 본다</li>
 *   <li>AD = IAM_MANAGE(ADMIN)</li>
 * </ul>
 * 분석은 바인딩 공간을 모두 볼 수 있을 때만 보인다(BR-ANA-03). 안 보이거나 지운 분석은 404 ANALYSIS_NOT_FOUND.
 */
@Component
public class AnalyticsAccess {

    /** 역할 순위 */
    public enum Rank {
        VIEWER, ANALYST, OPERATOR, INTEGRATOR, ADMIN
    }

    private static final Map<String, Rank> BUILTIN = Map.of(BuiltinRole.ADMIN.name(), Rank.ADMIN, BuiltinRole.INTEGRATOR.name(), Rank.INTEGRATOR,
            BuiltinRole.OPERATOR.name(), Rank.OPERATOR, BuiltinRole.ANALYST.name(), Rank.ANALYST, BuiltinRole.VIEWER.name(), Rank.VIEWER);

    private final RoleChecker roleChecker;
    private final AnalysisRefRepository refs;

    public AnalyticsAccess(RoleChecker roleChecker, AnalysisRefRepository refs) {
        this.roleChecker = roleChecker;
        this.refs = refs;
    }

    public CurrentUser user() {
        return roleChecker.currentUser();
    }

    /** V 이상 */
    public AccessGrant view() {
        return roleChecker.require(Permission.ANALYTICS_READ);
    }

    /** A 이상 */
    public AccessGrant run() {
        return roleChecker.require(Permission.ANALYTICS_RUN);
    }

    /** O·I 이상(ANALYTICS_RUN + 순위) */
    public AccessGrant atLeast(Rank rank) {
        AccessGrant grant = run();
        if (rank(grant).ordinal() < rank.ordinal()) {
            throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
        }
        return grant;
    }

    /** AD */
    public AccessGrant admin() {
        return roleChecker.requireAdmin();
    }

    public boolean has(Rank rank) {
        return rank(roleChecker.grant()).ordinal() >= rank.ordinal();
    }

    static Rank rank(AccessGrant grant) {
        Rank builtin = BUILTIN.get(grant.role());
        if (builtin != null) {
            return builtin;
        }
        if (!AccessGrant.CUSTOM_ROLE.equals(grant.role())) {
            return grant.has(Permission.ANALYTICS_RUN) ? Rank.ANALYST : Rank.VIEWER;
        }
        if (grant.has(Permission.IAM_MANAGE)) {
            return Rank.ADMIN;
        }
        if (grant.has(Permission.SCRIPT_WRITE)) {
            return Rank.INTEGRATOR;
        }
        if (grant.has(Permission.ALARM_HANDLE)) {
            return Rank.OPERATOR;
        }
        return grant.has(Permission.ANALYTICS_RUN) ? Rank.ANALYST : Rank.VIEWER;
    }

    /** 보이는(ACTIVE이고 공간 범위 안) 분석 색인. 아니면 404 ANALYSIS_NOT_FOUND */
    public AnalysisRef visible(long analysisId, AccessGrant grant) {
        AnalysisRef ref = refs.find(user().organizationId(), analysisId)
                .filter(AnalysisRef::active)
                .orElseThrow(() -> new BusinessException(AnalyticsErrorCode.ANALYSIS_NOT_FOUND));
        if (!covers(grant.spaceScope(), ref)) {
            throw new BusinessException(AnalyticsErrorCode.ANALYSIS_NOT_FOUND);
        }
        return ref;
    }

    /** 소유자 또는 ADMIN만 고칠 수 있다(API-ANA-06·22, TC-ANA-099 다른 ANALYST 403) */
    public void requireOwnerOrAdmin(AnalysisRef ref) {
        if (ref.ownerUserId() != user().userId() && !roleChecker.has(Permission.IAM_MANAGE)) {
            throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
        }
    }

    static boolean covers(SpaceScope scope, AnalysisRef ref) {
        return scope.unrestricted() || ref.spaceScopeIds().stream().allMatch(scope::includes);
    }
}
