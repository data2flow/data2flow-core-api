package net.java21.data2flow.core.devicegroup.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupCriteriaTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private GroupCriteria parse(String s) {
        return GroupCriteria.parse(json.readTree(s), "criteria");
    }

    @Test
    @DisplayName("[DEV-06.02] 문서 모양 조건: models(ID·코드)·spaceIds·tags{any,all}·status·attributes — TC-DEV-169")
    void documentShape() {
        GroupCriteria c = parse("""
                {"models":["11","EM500-CO2"],"spaceIds":["3"],"includeDescendants":false,"tags":{"any":["pilot"],"all":["a","b"]},
                 "status":["active"],"attributes":[{"key":"maxTemp","op":">","value":28},{"key":"floor","op":"EQ","value":"3F"},
                 {"key":"x","op":"exists"}]}""");
        assertThat(c.modelIds()).containsExactly(11L);
        assertThat(c.modelCodes()).containsExactly("EM500-CO2");
        assertThat(c.includeDescendants()).isFalse();
        assertThat(c.tagsAny()).containsExactly("pilot");
        assertThat(c.tagsAll()).containsExactly("a", "b");
        assertThat(c.statuses()).containsExactly("ACTIVE");
        assertThat(c.attributes()).extracting(GroupCriteria.AttributeCondition::op).containsExactly("GT", "EQ", "EXISTS");
    }

    @Test
    @DisplayName("[DEV-06.02] 화면 모양 조건: modelIds·tags{match,values}·statuses도 받는다")
    void webShape() {
        GroupCriteria c = parse("{\"modelIds\":[\"11\"],\"spaceIds\":[\"3\"],\"includeDescendants\":true,"
                + "\"tags\":{\"match\":\"all\",\"values\":[\"x\"]},\"statuses\":[\"PENDING\"]}");
        assertThat(c.modelIds()).containsExactly(11L);
        assertThat(c.includeDescendants()).isTrue();
        assertThat(c.tagsAll()).containsExactly("x");
        assertThat(parse("{\"tags\":{\"values\":[\"y\"]}}").tagsAny()).containsExactly("y");
    }

    @Test
    @DisplayName("[DEV-06.02] 모르는 필드·잘못된 값·빈 조건은 400 INVALID_REQUEST — TC-DEV-169")
    void rejectsInvalid() {
        for (String bad : new String[] {"{\"color\":[\"red\"]}", "{}", "null", "{\"spaceIds\":[\"x\"]}", "{\"status\":[\"GONE\"]}",
                "{\"tags\":{\"match\":\"none\",\"values\":[\"a\"]}}", "{\"tags\":{\"some\":[\"a\"]}}", "{\"tags\":[\"a\"]}",
                "{\"attributes\":[{\"key\":\"1x\",\"op\":\"EQ\",\"value\":1}]}", "{\"attributes\":[{\"key\":\"a\",\"op\":\"LIKE\",\"value\":1}]}",
                "{\"attributes\":[{\"key\":\"a\",\"op\":\"GT\",\"value\":\"x\"}]}", "{\"attributes\":[{\"key\":\"a\",\"op\":\"EQ\"}]}",
                "{\"attributes\":[1]}", "{\"includeDescendants\":\"yes\",\"spaceIds\":[\"1\"]}", "{\"models\":{}}", "{\"models\":[{}]}",
                "{\"tags\":{\"any\":[\"" + "a".repeat(41) + "\"]}}", "{\"tags\":{\"any\":\"a\"}}"}) {
            assertThatThrownBy(() -> parse(bad)).as(bad).isInstanceOf(BusinessException.class);
        }
    }
}
