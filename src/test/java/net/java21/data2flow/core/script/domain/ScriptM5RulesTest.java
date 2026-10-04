package net.java21.data2flow.core.script.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 설정값 검사(SCR-04.02, BR-SCR-12, AT-SCR-08.3)와 모듈 참조 추출(SCR-04.01) — TC-SCR-070 */
class ScriptM5RulesTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"apiToken", "password", "DB_PASSWORD", "secret", "clientSecret", "api_key", "apiKey", "KEY"})
    @DisplayName("[SCR-04.02][AT-SCR-08.3] 비밀값 이름(password·secret·token·…key, 대소문자 무시)은 SCRIPT_CONFIG_SECRET_FORBIDDEN — TC-SCR-070")
    void secretNames(String name) {
        ObjectNode config = JSON.createObjectNode().put(name, "x");
        assertThatThrownBy(() -> ScriptM5Rules.validateConfig(config)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().code()).isEqualTo("SCRIPT_CONFIG_SECRET_FORBIDDEN");
    }

    @Test
    @DisplayName("[SCR-04.02] 값은 문자열·숫자·불리언(1KB), 이름 형식, 50개 한도")
    void valuesAndLimits() {
        assertThatCode(() -> ScriptM5Rules.validateConfig(JSON.createObjectNode().put("tempOffset", 0.5).put("label", "A동")
                .put("on", true).put("monkeyName", "x"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ScriptM5Rules.validateConfig(JSON.createObjectNode().put("a", "x".repeat(1025))))
                .isInstanceOf(BusinessException.class);
        ObjectNode nested = JSON.createObjectNode();
        nested.putArray("list");
        assertThatThrownBy(() -> ScriptM5Rules.validateConfig(nested)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ScriptM5Rules.validateConfig(JSON.createObjectNode().put("1bad", 1))).isInstanceOf(BusinessException.class);
        ObjectNode many = JSON.createObjectNode();
        for (int i = 0; i < 51; i++) {
            many.put("k" + i + "x", i);
        }
        assertThatThrownBy(() -> ScriptM5Rules.validateConfig(many)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ScriptM5Rules.validateConfig(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("[SCR-04.01] 'module:이름@버전' 가져오기를 정렬·중복 없이 뽑는다, 버전 없는 가져오기는 무시(pipeline 검사가 거부)")
    void moduleRefs() {
        String code = """
                import { parseChannels } from 'module:milesight@1';
                import { a } from "module:utils@12";
                import { b } from 'module:milesight@1';
                import { c } from 'module:noversion';
                import _ from 'lodash';
                """;
        assertThat(ScriptM5Rules.moduleRefs(code)).containsExactly("milesight@1", "utils@12");
        assertThat(ScriptM5Rules.moduleRefs(null)).isEmpty();
        assertThat(ScriptM5Rules.validModuleName("milesight")).isTrue();
        assertThat(ScriptM5Rules.validModuleName("ab")).isFalse();
        assertThat(ScriptM5Rules.validResultKey("thi")).isTrue();
        assertThat(ScriptM5Rules.validResultKey("1x")).isFalse();
    }
}
