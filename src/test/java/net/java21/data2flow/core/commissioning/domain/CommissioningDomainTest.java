package net.java21.data2flow.core.commissioning.domain;

import com.google.zxing.common.BitMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DEV-09.04 QR 라벨, DEV-13.05 설치 상태·체크리스트(BR-DEV-38) — TC-DEV-255·323 */
class CommissioningDomainTest {

    private static final Instant T = Instant.parse("2026-10-03T00:00:00Z");

    @Test
    @DisplayName("[DEV-13.05][BR-DEV-38][TC-DEV-323] 설치 뒤 수신이면 VERIFIED, 10분 무수신이면 PROBLEM, PROBLEM도 뒤늦게 수신되면 VERIFIED")
    void nextStatus() {
        assertThat(CommissioningRules.next("INSTALLED", T, null, T.plusSeconds(599))).isEqualTo("INSTALLED");
        assertThat(CommissioningRules.next("INSTALLED", T, null, T.plusSeconds(600))).isEqualTo("PROBLEM");
        assertThat(CommissioningRules.next("INSTALLED", T, T.minusSeconds(60), T.plusSeconds(600))).isEqualTo("PROBLEM");
        assertThat(CommissioningRules.next("INSTALLED", T, T.plusSeconds(180), T.plusSeconds(181))).isEqualTo("VERIFIED");
        assertThat(CommissioningRules.next("PROBLEM", T, T.plusSeconds(900), T.plusSeconds(901))).isEqualTo("VERIFIED");
        assertThat(CommissioningRules.next("VERIFIED", T, null, T.plusSeconds(9999))).isEqualTo("VERIFIED");
        assertThat(CommissioningRules.next("PLANNED", null, null, T)).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("[DEV-13.05][BR-DEV-38] 점검 체크리스트: 첫 수신·위치·사진·신호(rssi ≥ -115, snr ≥ -15)·배터리 ≥ 20·게이트웨이·소스")
    void checklist() {
        Map<String, Boolean> ok = CommissioningRules.checklist(T, true, true, new CommissioningRules.Observed(T.plusSeconds(1),
                BigDecimal.valueOf(80), BigDecimal.valueOf(-90), BigDecimal.valueOf(5), "gw", true, "ACTIVE", "CONNECTED"));
        assertThat(ok).containsOnlyKeys("firstData", "position", "photo", "signal", "battery", "gateway", "source").doesNotContainValue(false);
        Map<String, Boolean> bad = CommissioningRules.checklist(T, false, false, new CommissioningRules.Observed(null, BigDecimal.TEN,
                BigDecimal.valueOf(-120), BigDecimal.ZERO, null, false, "PAUSED", "ERROR"));
        assertThat(bad).doesNotContainValue(true);
        assertThat(CommissioningRules.checklist(T, true, false, null).get("signal")).isFalse();
    }

    @Test
    @DisplayName("[DEV-09.04][TC-DEV-255] QR 행렬(여백 없음)과 A4 3×8 라벨 PDF(25대면 2쪽), 라틴 밖 글자는 ?로")
    void labels() {
        BitMatrix m = QrLabelPdf.matrix("https://data2flow.java21.net/d/abcdefghijklmnopqrstuvwx");
        assertThat(m.getWidth()).isEqualTo(m.getHeight()).isGreaterThanOrEqualTo(21);
        List<QrLabelPdf.Label> labels = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            labels.add(new QrLabelPdf.Label("https://x/d/" + i, "dev-" + i, "실습실(" + i + ")"));
        }
        String pdf = new String(QrLabelPdf.render(labels), StandardCharsets.ISO_8859_1);
        assertThat(pdf).startsWith("%PDF-1.4").contains("/Count 2").contains("(???\\(0\\)) Tj").contains("startxref");
        assertThat(QrLabelPdf.escape("a(b)\\c")).isEqualTo("a\\(b\\)\\\\c");
        assertThat(new String(QrLabelPdf.render(List.of()), StandardCharsets.ISO_8859_1)).contains("/Count 1");
    }
}
