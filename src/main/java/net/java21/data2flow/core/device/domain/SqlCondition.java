package net.java21.data2flow.core.device.domain;

import java.util.Map;

/**
 * 기기 목록에 덧붙이는 추가 조건(기기 검색식 DEV-13.03이 만든 SQL 조각). 기기 별칭은 {@code d}, 조직 매개변수는 {@code :org}를 쓴다.
 * 매개변수 이름은 겹치지 않게 접두어({@code dq})를 붙인다.
 */
public record SqlCondition(String sql, Map<String, Object> params) {

    public SqlCondition {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
