package net.java21.data2flow.core.branding.domain;

/**
 * WCAG 2.x 대비(BR-DSH-20, AT-DSH-14.2). 주 색상과 흰 배경(#FFFFFF)의 대비가 4.5:1 미만이면 저장 전에 경고한다.
 * 예: #FFFF00 → 1.07:1, #0055AA → 7.4:1.
 */
public final class Contrast {

    public static final double AA = 4.5;

    private Contrast() {
    }

    /** {@code #RRGGBB}와 흰색의 대비(1~21) */
    public static double againstWhite(String hex) {
        double l = luminance(hex);
        return (1.0 + 0.05) / (l + 0.05);
    }

    static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** 소수 둘째 자리 반올림 */
    public static double round(double ratio) {
        return Math.round(ratio * 100.0) / 100.0;
    }
}
