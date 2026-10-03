package net.java21.data2flow.core.space.domain;

import java.util.Optional;

/**
 * 위·경도 → 기상청 동네예보 격자(nx, ny) 변환(DEV-10.01, DSC-06.01). 기상청 공개 Lambert 정각원추도법(LCC) 공식:
 * 지구 반경 6371.00877km, 격자 5km, 표준 위도 30°·60°, 기준점 (126°E, 38°N) = 격자 (43, 136).
 * 격자 범위(nx 1~149, ny 1~253) 밖이면 비어 있다(국외 좌표). 다른 기능(외부 맥락 소스)도 이 클래스를 쓴다.
 */
public final class KmaGrid {

    private static final double RE = 6371.00877;
    private static final double GRID = 5.0;
    private static final double SLAT1 = 30.0;
    private static final double SLAT2 = 60.0;
    private static final double OLON = 126.0;
    private static final double OLAT = 38.0;
    private static final double XO = 43;
    private static final double YO = 136;
    private static final int MAX_NX = 149;
    private static final int MAX_NY = 253;

    private KmaGrid() {
    }

    /** 격자 좌표 */
    public record Point(int nx, int ny) {
    }

    public static Optional<Point> of(double latitude, double longitude) {
        double degrad = Math.PI / 180.0;
        double re = RE / GRID;
        double slat1 = SLAT1 * degrad;
        double slat2 = SLAT2 * degrad;
        double olon = OLON * degrad;
        double olat = OLAT * degrad;

        double sn = Math.tan(Math.PI * 0.25 + slat2 * 0.5) / Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn);
        double sf = Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sf = Math.pow(sf, sn) * Math.cos(slat1) / sn;
        double ro = Math.tan(Math.PI * 0.25 + olat * 0.5);
        ro = re * sf / Math.pow(ro, sn);

        double ra = Math.tan(Math.PI * 0.25 + latitude * degrad * 0.5);
        ra = re * sf / Math.pow(ra, sn);
        double theta = longitude * degrad - olon;
        if (theta > Math.PI) {
            theta -= 2.0 * Math.PI;
        }
        if (theta < -Math.PI) {
            theta += 2.0 * Math.PI;
        }
        theta *= sn;
        int nx = (int) Math.floor(ra * Math.sin(theta) + XO + 0.5);
        int ny = (int) Math.floor(ro - ra * Math.cos(theta) + YO + 0.5);
        if (nx < 1 || nx > MAX_NX || ny < 1 || ny > MAX_NY) {
            return Optional.empty();
        }
        return Optional.of(new Point(nx, ny));
    }
}
