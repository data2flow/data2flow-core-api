package net.java21.data2flow.core.dataexchange.domain;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Parquet(zstd) 쓰기(TSD-07.02, NFR-04.02). 시각은 UTC TIMESTAMP_MICROS, 나머지 열은 빈 값 허용 */
public final class ParquetRowSink implements RowSink {

    static final int ROW_GROUP = 100_000;

    private final OutputStream out;
    private ParquetWriter writer;

    public ParquetRowSink(OutputStream out) {
        this.out = out;
    }

    @Override
    public void header(List<Column> columns) {
        List<ParquetWriter.Column> cols = new ArrayList<>();
        for (Column c : columns) {
            ParquetWriter.ColumnType type = switch (c.type()) {
                case TIME -> ParquetWriter.ColumnType.TIMESTAMP_MICROS;
                case DOUBLE -> ParquetWriter.ColumnType.DOUBLE;
                case INT -> ParquetWriter.ColumnType.INT64;
                case STRING -> ParquetWriter.ColumnType.STRING;
            };
            cols.add(new ParquetWriter.Column(c.name(), type, c.type() != ColumnType.TIME));
        }
        writer = new ParquetWriter(out, cols, ROW_GROUP);
    }

    @Override
    public void row(Object[] values) {
        Object[] converted = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            converted[i] = v instanceof Integer n ? Long.valueOf(n) : v;
        }
        writer.write(converted);
    }

    @Override
    public long rows() {
        return writer == null ? 0 : writer.rows();
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
        out.flush();
    }
}
