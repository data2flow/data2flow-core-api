package net.java21.data2flow.core.devicesearch.domain;

import net.java21.data2flow.core.device.domain.SqlCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEV-13.03 검색식 파서·SQL 변환(BR-DEV-35) — TC-DEV-313 */
class DeviceQueryParserTest {

    @Test
    @DisplayName("[DEV-13.03][TC-DEV-313] 예시 검색식 model = \"EM300-TH\" and battery < 20 and space in \"3층\" → And(Cmp×3)")
    void parsesExample() {
        DeviceQuery q = DeviceQueryParser.parse("model = \"EM300-TH\" and battery < 20 and space in \"3층\"");
        assertThat(q).isInstanceOf(DeviceQuery.And.class);
        List<DeviceQuery> items = ((DeviceQuery.And) q).items();
        assertThat(items).hasSize(3);
        assertThat(items.get(0)).isEqualTo(new DeviceQuery.Cmp("model", "=", List.of("EM300-TH"), List.of("STRING"), 1));
        assertThat(items.get(1)).isEqualTo(new DeviceQuery.Cmp("battery", "<", List.of("20"), List.of("NUMBER"), 24));
        assertThat(((DeviceQuery.Cmp) items.get(2)).op()).isEqualTo("in");
    }

    @Test
    @DisplayName("[DEV-13.03][AT-DEV-25.2] battery < 뒤에 값이 없으면 DEVICE_QUERY_INVALID, 위치 열 11")
    void missingValueColumn() {
        assertThatThrownBy(() -> DeviceQueryParser.parse("battery < "))
                .isInstanceOfSatisfying(DeviceQueryException.class, ex -> assertThat(ex.column()).isEqualTo(11));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "color = \"red\"|1", "battery ~ 3|9", "name = |7", "(status = ACTIVE|17", "model = \"x|9", "tag in (\"a\",)|13",
            "battery < \"low\"|1", "status = ACTIVE extra|17", "lastSeen < 5|1"})
    @DisplayName("[DEV-13.03][BR-DEV-35] 모르는 필드·쓸 수 없는 연산자·값 누락·괄호·문자열·종류 오류는 위치와 함께 거부")
    void rejects(String input, int column) {
        assertThatThrownBy(() -> DeviceQuerySql.toSql(DeviceQueryParser.parse(input), Instant.EPOCH))
                .isInstanceOfSatisfying(DeviceQueryException.class, ex -> assertThat(ex.column()).isEqualTo(column));
    }

    @Test
    @DisplayName("[DEV-13.03][BR-DEV-35] or·not·괄호 우선순위, 값은 모두 바인드 매개변수(주입 없음), 기간은 지금 기준")
    void sqlBinding() {
        DeviceQuery q = DeviceQueryParser.parse("not (tag = \"x' OR 1=1 --\") or (metric.co2 > 1000 and lastSeen < 24h) or attr.floor = 3");
        assertThat(q).isInstanceOf(DeviceQuery.Or.class);
        SqlCondition c = DeviceQuerySql.toSql(q, Instant.parse("2026-10-03T00:00:00Z"));
        assertThat(c.sql()).doesNotContain("1=1").contains("NOT (").contains(":dq0");
        assertThat(c.params()).containsValue("x' OR 1=1 --").containsValue(new BigDecimal("1000"));
        assertThat(c.params().values()).anyMatch(v -> v.toString().startsWith("2026-10-02T00:00"));
        assertThat(DeviceQuerySql.toSql(DeviceQueryParser.parse("lastSeen < 30m"), null).sql()).contains("now() - CAST(");
    }

    @Test
    @DisplayName("[DEV-13.03] 목록 q가 검색식인지 키워드인지 가른다")
    void looksLikeExpression() {
        assertThat(DeviceQueryParser.looksLikeExpression("battery < 20")).isTrue();
        assertThat(DeviceQueryParser.looksLikeExpression("tag in (\"a\")")).isTrue();
        assertThat(DeviceQueryParser.looksLikeExpression("not virtual = true")).isTrue();
        assertThat(DeviceQueryParser.looksLikeExpression("실습실 온도")).isFalse();
        assertThat(DeviceQueryParser.looksLikeExpression("24e124")).isFalse();
        assertThat(DeviceQueryParser.looksLikeExpression(null)).isFalse();
    }
}
