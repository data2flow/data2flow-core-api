package net.java21.data2flow.core.space.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 공간 도메인 규칙 단위 테스트 — TC-DEV-001·009·015·261·280·303 */
class SpaceDomainTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("[DEV-10.01] 기상청 격자: 서울시청 (60,127), 광주 (58,74), 국외는 비어 있다 — TC-DEV-261")
    void kmaGrid() {
        assertThat(KmaGrid.of(37.5665, 126.9780)).contains(new KmaGrid.Point(60, 127));
        assertThat(KmaGrid.of(35.1595, 126.8526)).contains(new KmaGrid.Point(58, 74));
        assertThat(KmaGrid.of(33.4996, 126.5312)).contains(new KmaGrid.Point(53, 38));
        assertThat(KmaGrid.of(48.8566, 2.3522)).isEmpty();
        assertThat(KmaGrid.of(-33.8, 151.2)).isEmpty();
    }

    @Test
    @DisplayName("[DEV-01.01][BR-DEV-01·02] 종류 순서: 최상위는 SITE, 하위는 상위보다 큰 단위 금지(같은 단위·생략 허용) — TC-DEV-001")
    void typeOrder() {
        assertThat(SpaceType.SITE.allowedUnder(null)).isTrue();
        assertThat(SpaceType.ROOM.allowedUnder(null)).isFalse();
        assertThat(SpaceType.ROOM.allowedUnder(SpaceType.SITE)).isTrue();
        assertThat(SpaceType.ZONE.allowedUnder(SpaceType.ZONE)).isTrue();
        assertThat(SpaceType.BUILDING.allowedUnder(SpaceType.FLOOR)).isFalse();
        assertThat(SpaceType.SITE.allowedUnder(SpaceType.SITE)).isFalse();
        assertThat(SpaceType.parse(" room ")).contains(SpaceType.ROOM);
        assertThat(SpaceType.parse("CASTLE")).isEmpty();
        assertThat(SpaceType.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("[DEV-11.01][AT-DEV-02.2] 월 09:00~18:00: 08:59 UNOCCUPIED, 09:00 OCCUPIED, 18:00 UNOCCUPIED, 다음 변경 시각 — TC-DEV-009·280")
    void scheduleMode() {
        List<ScheduleSlot> slots = List.of(new ScheduleSlot(1, "09:00", "18:00"));
        assertThat(SpaceMode.fromSchedule(slots, SEOUL, Instant.parse("2026-10-04T23:59:00Z")).mode()).isEqualTo("UNOCCUPIED");
        SpaceMode open = SpaceMode.fromSchedule(slots, SEOUL, Instant.parse("2026-10-05T00:00:00Z"));
        assertThat(open.mode()).isEqualTo("OCCUPIED");
        assertThat(open.nextChangeAt()).isEqualTo(Instant.parse("2026-10-05T09:00:00Z"));
        SpaceMode closed = SpaceMode.fromSchedule(slots, SEOUL, Instant.parse("2026-10-05T09:00:00Z"));
        assertThat(closed.mode()).isEqualTo("UNOCCUPIED");
        assertThat(closed.nextChangeAt()).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
        assertThat(SpaceMode.fromSchedule(List.of(), SEOUL, Instant.parse("2026-10-05T00:00:00Z")).nextChangeAt()).isNull();
        SpaceMode late = SpaceMode.fromSchedule(List.of(new ScheduleSlot(7, "22:00", "24:00")), SEOUL, Instant.parse("2026-10-04T14:30:00Z"));
        assertThat(late.mode()).isEqualTo("OCCUPIED");
        assertThat(late.nextChangeAt()).isEqualTo(Instant.parse("2026-10-04T15:00:00Z"));
        assertThat(SpaceMode.override("HOLIDAY", null).source()).isEqualTo("OVERRIDE");
        assertThat(new ScheduleSlot(1, "09:00", "12:00").overlaps(new ScheduleSlot(1, "11:00", "13:00"))).isTrue();
        assertThat(new ScheduleSlot(1, "09:00", "12:00").overlaps(new ScheduleSlot(1, "12:00", "13:00"))).isFalse();
        assertThat(new ScheduleSlot(1, "09:00", "12:00").overlaps(new ScheduleSlot(2, "09:00", "12:00"))).isFalse();
        assertThat(ScheduleSlot.validTime("24:00")).isTrue();
        assertThat(ScheduleSlot.validTime("24:01")).isFalse();
        assertThat(ScheduleSlot.validTime(null)).isFalse();
    }

    @Test
    @DisplayName("[DEV-01.01] 경로 도우미: 조상 ID, 이벤트 경로(끝 / 없음), 조상 판정")
    void paths() {
        assertThat(Space.pathIds("/1/4/9/")).containsExactly(1L, 4L, 9L);
        assertThat(Space.pathIds(null)).isEmpty();
        assertThat(Space.eventPath("/1/7/31/")).isEqualTo("/1/7/31");
        assertThat(Space.eventPath("/")).isEqualTo("/");
    }

    @Test
    @DisplayName("[DEV-01.03] 평면도 검사: PNG·JPG 크기 읽기, 400×300 미만·10MB 초과·임의 바이트 거부, SVG 크기·안전성 — TC-DEV-015")
    void floorplanImages() throws Exception {
        assertThat(FloorplanImage.inspect(image("png", 1600, 900))).contains(new FloorplanImage.Info("image/png", 1600, 900));
        assertThat(FloorplanImage.inspect(image("jpg", 800, 600))).contains(new FloorplanImage.Info("image/jpeg", 800, 600));
        assertThat(FloorplanImage.inspect(image("png", 399, 300))).isEmpty();
        assertThat(FloorplanImage.inspect(null)).isEmpty();
        assertThat(FloorplanImage.inspect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9})).isEmpty();
        assertThat(FloorplanImage.inspect(svg("<svg xmlns='http://www.w3.org/2000/svg' width='800px' height='600'/>")))
                .contains(new FloorplanImage.Info("image/svg+xml", 800, 600));
        assertThat(FloorplanImage.inspect(svg("<svg xmlns='http://www.w3.org/2000/svg' width='800'/>"))).isEmpty();
        assertThat(FloorplanImage.inspect(svg("<svg xmlns='http://www.w3.org/2000/svg' width='10%' viewBox='0 0 640 480'/>")))
                .contains(new FloorplanImage.Info("image/svg+xml", 640, 480));
        assertThat(FloorplanImage.inspect(svg("<svg xmlns='http://www.w3.org/2000/svg' width='800' height='600'><foreignObject/></svg>"))).isEmpty();
        assertThat(FloorplanImage.inspect(svg("<svg xmlns='http://www.w3.org/2000/svg' width='800' height='600'><?xml-stylesheet href='x'?></svg>"))).isEmpty();
        assertThat(FloorplanImage.inspect(svg("<svg xmlns='http://www.w3.org/2000/svg' width='800' height='600'><a href='javascript:x'/></svg>"))).isEmpty();
        assertThat(FloorplanImage.inspect(svg("<html><svg/></html>"))).isEmpty();
        assertThat(FloorplanImage.inspect(svg("<svg width='800' height='600'"))).isEmpty();
    }

    @Test
    @DisplayName("[DEV-13.01][BR-DEV-33] 표준 어휘(대소문자 무시)·custom: 접두사, 틀린 값은 가까운 후보(Tempurature → Temperature) — TC-DEV-303")
    void vocabulary() {
        assertThat(SemanticVocabulary.quantity("zone_air_temperature")).contains("Zone_Air_Temperature");
        assertThat(SemanticVocabulary.quantity("custom:My_Thing")).contains("custom:My_Thing");
        assertThat(SemanticVocabulary.quantity("custom:bad tag")).isEmpty();
        assertThat(SemanticVocabulary.quantity("Tempurature")).isEmpty();
        assertThat(SemanticVocabulary.quantity(" ")).isEmpty();
        assertThat(SemanticVocabulary.suggestQuantity("Tempurature")).isEqualTo("Temperature");
        assertThat(SemanticVocabulary.equipClass("ahu")).contains("AHU");
        assertThat(SemanticVocabulary.suggestEquipClass("Thermostatt")).isEqualTo("Thermostat");
        assertThat(SemanticVocabulary.suggestQuantity(null)).isNotBlank();
        assertThat(SemanticVocabulary.distance("kitten", "sitting")).isEqualTo(3);
    }

    private static byte[] svg(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }
}
