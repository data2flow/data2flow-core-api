package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.core.dataexchange.domain.ExportFormat;
import net.java21.data2flow.core.dataexchange.domain.ExportPlan;
import net.java21.data2flow.core.dataexchange.domain.ExportPlan.PlanSeries;
import net.java21.data2flow.core.dataexchange.domain.RowSink;
import net.java21.data2flow.core.dataexchange.domain.RowSink.Column;
import net.java21.data2flow.core.dataexchange.domain.RowSink.ColumnType;
import net.java21.data2flow.core.dataexchange.repository.ExportDataRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * 내보내기 계획을 파일로 쓴다(TSD-04.01, AT-TSD-04.1). DB 커서로 읽은 점을 바로 형식별 쓰기({@link RowSink})에 넘긴다.
 * <ul>
 *   <li>긴 형식(LONG) 머리글 {@code time,device_id,device_name,space_path,metric,value,unit,quality}(AT-TSD-04.1). 품질을 빼면 quality 열 없음.
 *       집계 행의 quality는 비운다(표본 수는 품질이 아님)</li>
 *   <li>표시 온도 단위가 ℉면(DEV-04.04) {@code display_value,display_unit} 열을 더해 저장 단위(℃)와 표시 단위를 함께 적는다</li>
 *   <li>넓은 형식(WIDE) {@code time,{기기}.{측정 항목}…}: 같은 시각 값을 한 행으로(시각 정렬). 원본 + 품질 포함이면 {@code {열}.quality}</li>
 *   <li>Parquet은 긴 형식만</li>
 * </ul>
 */
@Component
public class ExportFileWriter {

    static final Set<String> CELSIUS = Set.of("℃", "°C", "C", "degC");
    static final int CANCEL_CHECK_ROWS = 50_000;

    private final ExportDataRepository data;
    private final TransactionTemplate readTx;

    public ExportFileWriter(ExportDataRepository data, PlatformTransactionManager txManager) {
        this.data = data;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    /** 쓴 데이터 행 수 */
    public long write(long organizationId, ExportPlan plan, ExportFormat format, OutputStream out, BooleanSupplier cancelled) {
        ZoneId zone = ZoneId.of(plan.timezone());
        boolean wide = plan.wide() && format != ExportFormat.PARQUET;
        boolean display = "F".equals(plan.temperatureUnit()) && plan.series().stream().anyMatch(s -> celsius(s.unit()));
        try (RowSink sink = RowSink.of(format, out, zone)) {
            List<PlanSeries> series = plan.series();
            if (wide) {
                sink.header(wideColumns(plan));
            } else {
                sink.header(longColumns(plan.includeQuality(), display));
            }
            Long count = readTx.execute(status -> {
                long[] rows = {0};
                Object[][] pending = {null};
                Instant[] pendingTime = {null};
                data.streamPoints(organizationId, plan, (s, t, v, q) -> {
                    PlanSeries ps = series.get(s);
                    if (wide) {
                        if (pendingTime[0] != null && !pendingTime[0].equals(t)) {
                            sink.row(pending[0]);
                            rows[0]++;
                            pending[0] = null;
                        }
                        if (pending[0] == null) {
                            pending[0] = new Object[1 + series.size() * (wideQuality(plan) ? 2 : 1)];
                            pending[0][0] = t;
                            pendingTime[0] = t;
                        }
                        int col = 1 + s * (wideQuality(plan) ? 2 : 1);
                        pending[0][col] = v;
                        if (wideQuality(plan)) {
                            pending[0][col + 1] = q;
                        }
                    } else {
                        sink.row(longRow(ps, t, v, plan.raw() && !ps.space() ? q : null, plan.includeQuality(), display));
                        rows[0]++;
                    }
                    if (rows[0] % CANCEL_CHECK_ROWS == 0 && rows[0] > 0 && cancelled.getAsBoolean()) {
                        throw new CancellationException("내보내기가 취소되었습니다");
                    }
                });
                if (pending[0] != null) {
                    try {
                        sink.row(pending[0]);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                    rows[0]++;
                }
                return rows[0];
            });
            return count == null ? 0 : count;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static boolean wideQuality(ExportPlan plan) {
        return plan.includeQuality() && plan.raw();
    }

    static List<Column> longColumns(boolean includeQuality, boolean display) {
        List<Column> columns = new ArrayList<>(List.of(new Column("time", ColumnType.TIME), new Column("device_id", ColumnType.INT),
                new Column("device_name", ColumnType.STRING), new Column("space_path", ColumnType.STRING),
                new Column("metric", ColumnType.STRING), new Column("value", ColumnType.DOUBLE), new Column("unit", ColumnType.STRING)));
        if (includeQuality) {
            columns.add(new Column("quality", ColumnType.INT));
        }
        if (display) {
            columns.add(new Column("display_value", ColumnType.DOUBLE));
            columns.add(new Column("display_unit", ColumnType.STRING));
        }
        return columns;
    }

    static List<Column> wideColumns(ExportPlan plan) {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column("time", ColumnType.TIME));
        for (PlanSeries s : plan.series()) {
            columns.add(new Column(s.columnName(), ColumnType.DOUBLE));
            if (wideQuality(plan)) {
                columns.add(new Column(s.columnName() + ".quality", ColumnType.INT));
            }
        }
        return columns;
    }

    static Object[] longRow(PlanSeries s, Instant t, Double v, Integer quality, boolean includeQuality, boolean display) {
        List<Object> row = new ArrayList<>(10);
        row.add(t);
        row.add(s.deviceId());
        row.add(s.space() ? null : s.deviceName());
        row.add(s.spacePath());
        row.add(s.metric());
        row.add(v);
        row.add(s.unit());
        if (includeQuality) {
            row.add(quality);
        }
        if (display) {
            if (celsius(s.unit()) && v != null) {
                row.add(Math.round((v * 9 / 5 + 32) * 100.0) / 100.0);
                row.add("℉");
            } else {
                row.add(v);
                row.add(s.unit());
            }
        }
        return row.toArray();
    }

    static boolean celsius(String unit) {
        return unit != null && CELSIUS.contains(unit.strip());
    }
}
