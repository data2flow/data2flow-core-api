package net.java21.data2flow.core.dataexchange.domain;

import net.java21.data2flow.core.dataexchange.domain.RowSink.Column;
import net.java21.data2flow.core.dataexchange.domain.RowSink.ColumnType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 내보내기·가져오기 파일 형식 단위 시험(TSD-04.01·04.02·04.03, BR-TSD-14·15) */
class TelemetryExportServiceTest {

    static final List<Column> COLUMNS = List.of(new Column("time", ColumnType.TIME), new Column("device_name", ColumnType.STRING),
            new Column("value", ColumnType.DOUBLE), new Column("quality", ColumnType.INT));

    @Test
    @DisplayName("[TSD-04.01][AT-TSD-04.1] CSV: BOM, 따옴표·쉼표·줄바꿈 이스케이프, 수식 주입 방지, 지수 표기 없는 숫자, tz 표기 — TC-TSD-102")
    void csv() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CsvRowSink sink = new CsvRowSink(out, ZoneId.of("Asia/Seoul"))) {
            sink.header(COLUMNS);
            sink.row(new Object[]{Instant.parse("2026-10-02T15:00:00Z"), "실습실, \"A\"", 12345678.9, 0});
            sink.row(new Object[]{Instant.parse("2026-10-02T15:01:00Z"), "=cmd()", 1.0E-7, null});
            sink.row(new Object[]{Instant.parse("2026-10-02T15:02:00Z"), "-12", Double.NaN, 4});
            assertThat(sink.rows()).isEqualTo(3);
        }
        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿time,device_name,value,quality\n");
        assertThat(csv).contains("2026-10-03T00:00:00+09:00,\"실습실, \"\"A\"\"\",12345678.9,0\n");
        assertThat(csv).contains("2026-10-03T00:01:00+09:00,'=cmd(),0.0000001,\n");
        assertThat(csv).contains(",-12,,4\n");
    }

    @Test
    @DisplayName("[TSD-04.01][AT-TSD-04.3][BR-TSD-14] Excel은 시트당 한도를 넘으면 시트를 나눈다(250행·시트당 100행 → 3시트, 머리글 반복) — TC-TSD-102")
    void xlsxSplitsSheets() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsxRowSink sink = new XlsxRowSink(out, ZoneId.of("UTC"), 100);
        sink.header(COLUMNS);
        for (int i = 0; i < 250; i++) {
            sink.row(new Object[]{Instant.parse("2026-10-02T15:00:00Z").plusSeconds(i), "<기기&1>", (double) i, i % 2});
        }
        sink.close();
        assertThat(sink.sheets()).isEqualTo(3);
        List<String> entries = new ArrayList<>();
        String sheet3 = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                entries.add(e.getName());
                String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                if (e.getName().equals("xl/worksheets/sheet3.xml")) {
                    sheet3 = content;
                }
                if (e.getName().equals("xl/workbook.xml")) {
                    assertThat(content).contains("name=\"data1\"").contains("name=\"data3\"");
                }
            }
        }
        assertThat(entries).contains("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml");
        assertThat(sheet3).contains("<t xml:space=\"preserve\">time</t>").contains("&lt;기기&amp;1&gt;").contains("<v>249</v>");
        assertThat(sheet3.split("<row>").length - 1).isEqualTo(51);
    }

    @Test
    @DisplayName("[NFR-04.02][TSD-07.02] Parquet: PAR1 머리·꼬리, 행 수")
    void parquet() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ParquetRowSink sink = new ParquetRowSink(out)) {
            sink.header(COLUMNS);
            sink.row(new Object[]{Instant.parse("2026-10-02T15:00:00Z"), "a", 1.5, 0});
            sink.row(new Object[]{Instant.parse("2026-10-02T15:01:00Z"), null, null, null});
            assertThat(sink.rows()).isEqualTo(2);
        }
        byte[] b = out.toByteArray();
        assertThat(new String(b, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("PAR1");
        assertThat(new String(b, b.length - 4, 4, StandardCharsets.US_ASCII)).isEqualTo("PAR1");
    }

    @Test
    @DisplayName("[TSD-04.03][AT-TSD-12.1] 매일 07:00 KST + 전날 → 전날 00:00~24:00 KST, 지난주(월~일)·지난달 — TC-TSD-115")
    void relativePeriods() {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        Instant run = Instant.parse("2026-10-06T22:00:00Z"); // 10-07 07:00 KST(수)
        assertThat(RelativePeriod.PREVIOUS_DAY.range(run, kst)).isEqualTo(new RelativePeriod.Range(Instant.parse("2026-10-05T15:00:00Z"),
                Instant.parse("2026-10-06T15:00:00Z")));
        assertThat(RelativePeriod.PREVIOUS_WEEK.range(run, kst).from()).isEqualTo(Instant.parse("2026-09-27T15:00:00Z"));
        assertThat(RelativePeriod.PREVIOUS_WEEK.range(run, kst).to()).isEqualTo(Instant.parse("2026-10-04T15:00:00Z"));
        assertThat(RelativePeriod.PREVIOUS_MONTH.range(run, kst).from()).isEqualTo(Instant.parse("2026-08-31T15:00:00Z"));
        assertThat(RelativePeriod.parse("전날")).isEqualTo(RelativePeriod.PREVIOUS_DAY);
        assertThat(RelativePeriod.parse("previous_week")).isEqualTo(RelativePeriod.PREVIOUS_WEEK);
        assertThat(RelativePeriod.parse("어제")).isNull();
        assertThat(ExportFormat.parse("xlsx")).isEqualTo(ExportFormat.XLSX);
        assertThat(ExportFormat.parse(" ")).isNull();
    }

    @Test
    @DisplayName("[TSD-04.02][BR-TSD-15] CSV 읽기: BOM 건너뛰기, 따옴표 안 줄바꿈·쉼표, CRLF, 줄 번호, 닫히지 않은 따옴표 오류 — TC-TSD-109")
    void csvReader() throws Exception {
        CsvReader r = new CsvReader(new StringReader("﻿time,device,co2\r\n2026-10-01T00:00:00Z,\"a,1\",812\n\"x\ny\",b, 3 \n"), ',');
        assertThat(r.next()).isEqualTo(new CsvReader.Record(1, List.of("time", "device", "co2")));
        assertThat(r.next()).isEqualTo(new CsvReader.Record(2, List.of("2026-10-01T00:00:00Z", "a,1", "812")));
        assertThat(r.next()).isEqualTo(new CsvReader.Record(3, List.of("x\ny", "b", "3")));
        assertThat(r.next()).isNull();
        CsvReader bad = new CsvReader(new StringReader("a,\"b\n"), ',');
        assertThatThrownBy(bad::next).hasMessageContaining("1행");
    }
}
