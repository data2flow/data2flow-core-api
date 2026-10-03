package net.java21.data2flow.core.space.repository;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.common.Pg;

import java.util.Map;

/**
 * 공간 범위 SQL 조각(IAM-04.05·04.06, BR-IAM-16·BR-DEV-25). 목록·검색·집계·내보내기·스트림 조회가 같은 조건을 쓰도록 한 곳에 둔다.
 * {@code RoleChecker#spaceScope()}의 {@code allowedSpaceIds}는 이미 하위 공간까지 펼친 집합이라 {@code = ANY(...)} 하나로 충분하다.
 * 리포지토리가 쓰므로 repository 패키지에 둔다(ArchUnit: repository → service 의존 금지). 다른 기능의 리포지토리도 이 클래스를 쓴다.
 *
 * <pre>{@code
 * Map<String, Object> params = new HashMap<>();
 * String sql = "SELECT … FROM data2flow_core.devices d WHERE d.organization_id = :org"
 *         + SpaceScopeSql.and(scope, "d.space_id", params);          // 제한된 사용자면 " AND d.space_id = ANY(CAST(:scopeSpaceIds AS bigint[]))"
 * jdbc.sql(sql).params(params)…
 * }</pre>
 *
 * 공간이 없는 행(예: 공간을 아직 안 정한 PENDING 기기)은 제한된 사용자에게 보이지 않는다(열 값이 NULL이면 ANY가 참이 아님).
 */
public final class SpaceScopeSql {

    /** 기본 매개변수 이름 */
    public static final String PARAM = "scopeSpaceIds";

    private SpaceScopeSql() {
    }

    /** 전체 범위면 빈 문자열, 제한된 범위면 {@code " AND <column> = ANY(CAST(:scopeSpaceIds AS bigint[]))"}를 돌려주고 매개변수를 넣는다 */
    public static String and(SpaceScope scope, String column, Map<String, Object> params) {
        return and(scope, column, PARAM, params);
    }

    /** 매개변수 이름을 정한다(한 쿼리에서 두 번 쓸 때) */
    public static String and(SpaceScope scope, String column, String param, Map<String, Object> params) {
        if (scope == null || scope.unrestricted()) {
            return "";
        }
        params.put(param, Pg.bigintArray(scope.allowedSpaceIds()));
        return " AND " + column + " = ANY(CAST(:" + param + " AS bigint[]))";
    }
}
