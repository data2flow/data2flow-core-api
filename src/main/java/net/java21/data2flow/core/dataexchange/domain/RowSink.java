package net.java21.data2flow.core.dataexchange.domain;

import java.io.IOException;
import java.io.OutputStream;
import java.time.ZoneId;
import java.util.List;

/**
 * 내보내기 행을 파일 형식으로 쓰는 곳(CSV·XLSX·Parquet). 행을 받는 대로 바로 쓰고 모아 두지 않는다(1년치도 메모리 일정, TSD-04.01 시연).
 * 시각 값은 {@link java.time.Instant}로 넘기고 형식마다 표시 시간대(tz)로 바꾼다.
 */
public interface RowSink extends AutoCloseable {

    /** 열 종류 */
    enum ColumnType { TIME, STRING, DOUBLE, INT }

    /** 열 정의 */
    record Column(String name, ColumnType type) {
    }

    /** 머리글(첫 행 전에 한 번) */
    void header(List<Column> columns) throws IOException;

    /** 한 행(열 순서대로, 빈 값은 null) */
    void row(Object[] values) throws IOException;

    /** 쓴 데이터 행 수 */
    long rows();

    /** 마무리(파일 끝 구조를 쓴다). 출력 스트림은 닫지 않는다 */
    @Override
    void close() throws IOException;

    static RowSink of(ExportFormat format, OutputStream out, ZoneId zone) {
        return switch (format) {
            case CSV -> new CsvRowSink(out, zone);
            case XLSX -> new XlsxRowSink(out, zone, XlsxRowSink.MAX_ROWS_PER_SHEET);
            case PARQUET -> new ParquetRowSink(out);
        };
    }
}
