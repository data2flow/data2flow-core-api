package net.java21.data2flow.core.devicesearch.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * 기기 검색식 AST(DEV-13.03, BR-DEV-35). 저장된 검색({@code saved_searches.ast})에도 이 모양으로 남는다.
 * 예: {@code model = "EM300-TH" and battery < 20 and space in "3층"} →
 * {@code And[Cmp(model,=,["EM300-TH"]), Cmp(battery,<,[20]), Cmp(space,in,["3층"])]}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "node")
@JsonSubTypes({@JsonSubTypes.Type(value = DeviceQuery.Or.class, name = "or"), @JsonSubTypes.Type(value = DeviceQuery.And.class, name = "and"),
        @JsonSubTypes.Type(value = DeviceQuery.Not.class, name = "not"), @JsonSubTypes.Type(value = DeviceQuery.Cmp.class, name = "cmp")})
public sealed interface DeviceQuery {

    record Or(List<DeviceQuery> items) implements DeviceQuery {
    }

    record And(List<DeviceQuery> items) implements DeviceQuery {
    }

    record Not(DeviceQuery item) implements DeviceQuery {
    }

    /**
     * 비교 하나.
     *
     * @param field  필드(소문자로 맞춘 이름, 예: model·battery·metric.co2·attr.floor)
     * @param op     = != &lt; &lt;= &gt; &gt;= ~ in
     * @param values 값(in이면 여럿). 문자열·숫자(BigDecimal 문자열)·true/false·기간(예: 24h)
     * @param kinds  값 종류(STRING·NUMBER·BOOLEAN·DURATION), values와 같은 순서
     * @param column 필드가 시작하는 열(1부터)
     */
    record Cmp(String field, String op, List<String> values, List<String> kinds, int column) implements DeviceQuery {
    }
}
