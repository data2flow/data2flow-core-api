package net.java21.data2flow.core.script.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.script.domain.ScriptModels.ScriptKind;
import net.java21.data2flow.core.script.domain.ScriptModels.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스크립트 도메인 규칙(SCR-04.03 한도, SCR-01.01·01.02 연결 규칙) — TC-SCR-072·002 단위 행 */
class ScriptModelsTest {

    @Test
    @DisplayName("[SCR-04.03][AT-SCR-03.5] 코드 크기는 UTF-8 바이트 기준: 65,536 허용, 65,537(한글 포함) 400 SCRIPT_CODE_TOO_LARGE — TC-SCR-072")
    void codeSize() {
        String exact = "x".repeat(ScriptModels.MAX_CODE_BYTES);
        assertThat(ScriptModels.requireCodeSize(exact)).isSameAs(exact);
        String korean = "// 한" + "x".repeat(ScriptModels.MAX_CODE_BYTES - "// 한".getBytes(StandardCharsets.UTF_8).length + 1);
        assertThat(korean.length()).isLessThan(ScriptModels.MAX_CODE_BYTES);
        assertThatThrownBy(() -> ScriptModels.requireCodeSize(korean)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ScriptErrorCode.SCRIPT_CODE_TOO_LARGE);
        assertThatThrownBy(() -> ScriptModels.requireCodeSize(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("[SCR-01.01][SCR-01.02] DECODE → SOURCE만, TRANSFORM → MODEL·DEVICE만 — TC-SCR-002")
    void bindingRules() {
        assertThat(ScriptModels.bindingAllowed(ScriptKind.DECODE, TargetType.SOURCE)).isTrue();
        assertThat(ScriptModels.bindingAllowed(ScriptKind.DECODE, TargetType.MODEL)).isFalse();
        assertThat(ScriptModels.bindingAllowed(ScriptKind.DECODE, TargetType.DEVICE)).isFalse();
        assertThat(ScriptModels.bindingAllowed(ScriptKind.TRANSFORM, TargetType.SOURCE)).isFalse();
        assertThat(ScriptModels.bindingAllowed(ScriptKind.TRANSFORM, TargetType.MODEL)).isTrue();
        assertThat(ScriptModels.bindingAllowed(ScriptKind.TRANSFORM, TargetType.DEVICE)).isTrue();
    }

    @Test
    @DisplayName("[SCR-03.04] 종류 값은 대소문자 무시, 모르는 값·빈 값은 400, 코드 해시는 SHA-256 16진수 64자")
    void parsingAndHash() {
        assertThat(ScriptModels.kind(" transform ")).isEqualTo(ScriptKind.TRANSFORM);
        assertThatThrownBy(() -> ScriptModels.kind("MAGIC")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ScriptModels.kind(" ")).isInstanceOf(BusinessException.class);
        assertThat(ScriptModels.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("[SCR-01.05] 템플릿 6종(calibration-offset, unit-convert, moving-average, drop-zero, dew-point, milesight-decoder)과 기본 코드")
    void templates() {
        assertThat(ScriptTemplates.all()).extracting(ScriptTemplates.Template::key)
                .containsExactlyInAnyOrder("calibration-offset", "unit-convert", "moving-average", "drop-zero", "dew-point", "milesight-decoder");
        assertThat(ScriptTemplates.find("milesight-decoder")).get().extracting(ScriptTemplates.Template::kind).isEqualTo(ScriptKind.DECODE);
        assertThat(ScriptTemplates.find("nope")).isEmpty();
        assertThat(ScriptTemplates.defaultCode(ScriptKind.DECODE)).contains("function decode(input, ctx)");
        assertThat(ScriptTemplates.defaultCode(ScriptKind.TRANSFORM)).contains("function transform(msg, ctx)");
    }
}
