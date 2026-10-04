package net.java21.data2flow.core.branding;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.branding.domain.BrandingAssetValidator;
import net.java21.data2flow.core.branding.domain.Contrast;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DSH-13.01 브랜딩 자산·대비(BR-DSH-20) — TC-DSH-112, AT-DSH-14.2·14.3 */
class BrandingAssetValidatorTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    static final String SAFE_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><defs><linearGradient id=\"g\"/></defs>"
            + "<rect width=\"10\" height=\"10\" fill=\"url(#g)\"/><use href=\"#g\"/></svg>";

    @ParameterizedTest
    @DisplayName("[DSH-13.01][TC-DSH-112][AT-DSH-14.3] 악성 SVG 12종 → 400 BRANDING_ASSET_INVALID")
    @ValueSource(strings = {
            "<svg><script>alert(1)</script></svg>",
            "<svg><SCRIPT xlink:href='x.js'/></svg>",
            "<svg onload=\"alert(1)\"></svg>",
            "<svg><rect onclick='x()'/></svg>",
            "<svg><foreignObject><iframe/></foreignObject></svg>",
            "<svg><a href=\"javascript:alert(1)\">x</a></svg>",
            "<svg><image href=\"https://evil.example/x.png\"/></svg>",
            "<svg><use xlink:href=\"//evil.example/s.svg#a\"/></svg>",
            "<svg><style>@import url(https://evil.example/a.css);</style></svg>",
            "<svg><rect fill=\"url(http://evil.example/p#x)\"/></svg>",
            "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg>&x;</svg>",
            "<svg><embed src=\"x.swf\"/></svg>"})
    void unsafeSvg(String svg) {
        assertThatThrownBy(() -> BrandingAssetValidator.validate("LOGO_LIGHT", svg.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode().code()).isEqualTo("BRANDING_ASSET_INVALID"));
    }

    @Test
    @DisplayName("[DSH-13.01][TC-DSH-112] PNG·정상 SVG 통과, 1MB 초과(5MB SVG)·형식 밖·종류 밖 400, 파비콘 ICO·배경 JPEG 허용")
    void formats() {
        assertThat(BrandingAssetValidator.validate("LOGO_LIGHT", PNG)).isEqualTo("image/png");
        assertThat(BrandingAssetValidator.validate("LOGO_DARK", SAFE_SVG.getBytes(StandardCharsets.UTF_8))).isEqualTo("image/svg+xml");
        assertThat(BrandingAssetValidator.validate("FAVICON", new byte[]{0, 0, 1, 0, 1, 0})).isEqualTo("image/x-icon");
        assertThat(BrandingAssetValidator.validate("LOGIN_BACKGROUND", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
        byte[] big = ("<svg>" + "x".repeat(5 * 1024 * 1024) + "<script/></svg>").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> BrandingAssetValidator.validate("LOGO_LIGHT", big))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrors().getFirst().code()).isEqualTo("TOO_LARGE"));
        assertThatThrownBy(() -> BrandingAssetValidator.validate("LOGO_LIGHT", "GIF89a".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> BrandingAssetValidator.validate("BANNER", PNG)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> BrandingAssetValidator.validate("LOGO_LIGHT", new byte[0])).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> BrandingAssetValidator.validate("LOGO_LIGHT", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("[DSH-13.01][AT-DSH-14.2] 흰 배경 대비: #FFFF00 = 1.07:1(4.5 미만), #0055AA ≥ 4.5")
    void contrast() {
        assertThat(Contrast.round(Contrast.againstWhite("#FFFF00"))).isEqualTo(1.07);
        assertThat(Contrast.againstWhite("#0055AA")).isGreaterThanOrEqualTo(Contrast.AA);
        assertThat(Contrast.round(Contrast.againstWhite("#000000"))).isEqualTo(21.0);
    }
}
