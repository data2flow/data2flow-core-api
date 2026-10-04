package net.java21.data2flow.core.storage.service;

import net.java21.data2flow.contracts.alarm.AlarmClearReason;
import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import net.java21.data2flow.contracts.alarm.AlarmSourceType;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.core.alarm.repository.AlarmRepository;
import net.java21.data2flow.core.alarm.repository.AlarmRepository.AlarmRow;
import net.java21.data2flow.core.alarm.service.AlarmService;
import net.java21.data2flow.core.ingest.repository.IngestSettingsRepository.OpsThresholdRow;
import net.java21.data2flow.core.ingest.service.IngestSettingsService;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.storage.repository.StorageMetricsRepository;
import net.java21.data2flow.core.storage.repository.StorageMetricsRepository.DailyTotal;
import net.java21.data2flow.core.storage.repository.StorageMetricsRepository.TableSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 저장 지표(OPS-01.03, API-OPS-03, BR-OPS-03).
 * <ul>
 *   <li>DB 용량은 {@code pg_database_size(data2flow)}, 표 크기는 {@code data2flow_*} 스키마만(큰 순 50개)</li>
 *   <li>증가 추세: 하루 1회 표 크기 기록({@code storage_snapshots}, 90일 보관)의 일별 합계와 전날 대비 증감</li>
 *   <li>디스크 여유: DB가 쓸 수 있는 용량을 설정({@code data2flow.core.storage.disk-capacity-bytes}, 환경변수
 *       {@code DATA2FLOW_STORAGE_DISK_CAPACITY_BYTES})으로 받아 (용량 − DB 크기) ÷ 용량. 설정이 없으면(0) null이고 알람도 판정하지 않는다
 *       (공용 PostgreSQL 서버의 디스크는 SQL로 볼 수 없고, 다른 팀 DB는 보지 않는다)</li>
 *   <li>알람: 여유가 운영 기준 DISK_FREE_PERCENT(기본 20%) 아래면 MAJOR, 그 절반(기본 10%) 아래면 CRITICAL로 같은 알람
 *       {@code system:DISK_FREE}를 승격한다. 기준 이상으로 돌아오면 자동 해제(AUTO)</li>
 * </ul>
 */
@Service
public class StorageMetricsService {

    private static final Logger log = LoggerFactory.getLogger(StorageMetricsService.class);
    static final String ALARM_KEY = "system:DISK_FREE";
    static final int TOP_TABLES = 50;
    static final int TREND_DAYS = 30;
    static final int KEEP_DAYS = 90;

    private final RoleChecker roleChecker;
    private final StorageMetricsRepository repository;
    private final IngestSettingsService settings;
    private final AlarmService alarms;
    private final AlarmRepository alarmRows;
    private final DeploymentOrganization deployment;
    private final Clock clock;
    private final long capacityBytes;

    public StorageMetricsService(RoleChecker roleChecker, StorageMetricsRepository repository, IngestSettingsService settings, AlarmService alarms,
                                 AlarmRepository alarmRows, DeploymentOrganization deployment, Clock clock,
                                 @Value("${data2flow.core.storage.disk-capacity-bytes:0}") long capacityBytes) {
        this.roleChecker = roleChecker;
        this.repository = repository;
        this.settings = settings;
        this.alarms = alarms;
        this.alarmRows = alarmRows;
        this.deployment = deployment;
        this.clock = clock;
        this.capacityBytes = capacityBytes;
    }

    public record TableItem(String schema, String table, long bytes, long rows) {
    }

    public record DailyItem(LocalDate day, long bytes, Long growthBytes) {
    }

    /** API-OPS-03 응답 */
    public record StorageMetrics(long dbSizeBytes, List<TableItem> tables, List<DailyItem> dailyGrowthBytes, Double diskFreePercent,
                                 Long diskCapacityBytes, Instant checkedAt) {
    }

    /** API-OPS-03 — OPS_MANAGE(ADMIN) */
    @Transactional(readOnly = true)
    public StorageMetrics metrics() {
        roleChecker.require(Permission.OPS_MANAGE);
        long db = repository.findDatabaseSize();
        List<TableItem> tables = repository.findTableSizes().stream().limit(TOP_TABLES)
                .map(t -> new TableItem(t.schema(), t.table(), t.bytes(), t.rows())).toList();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<DailyItem> trend = new ArrayList<>();
        Long previous = null;
        for (DailyTotal d : repository.findDailyTotals(today.minusDays(TREND_DAYS))) {
            trend.add(new DailyItem(d.day(), d.bytes(), previous == null ? null : d.bytes() - previous));
            previous = d.bytes();
        }
        return new StorageMetrics(db, tables, trend, freePercent(db).orElse(null), capacityBytes > 0 ? capacityBytes : null, clock.instant());
    }

    /** 하루 1회(스케줄): 표 크기 기록·오래된 기록 삭제·디스크 알람 판정 */
    @Transactional
    public void snapshot() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (TableSize t : repository.findTableSizes()) {
            repository.upsertSnapshot(today, t);
        }
        repository.deleteSnapshotsBefore(today.minusDays(KEEP_DAYS));
        evaluateDisk();
    }

    /** 디스크 여유 알람(BR-OPS-03, TC-OPS-013). 이 배포의 조직에 시스템 알람을 만들거나 승격·해제한다 */
    @Transactional
    public void evaluateDisk() {
        Optional<Double> free = freePercent(repository.findDatabaseSize());
        Optional<Long> org = deployment.current().map(o -> o.id());
        if (free.isEmpty() || org.isEmpty()) {
            return;
        }
        evaluate(org.get(), free.get());
    }

    /** 여유 비율 하나로 판정(스케줄·시험) */
    @Transactional
    public void evaluate(long orgId, double freePercent) {
        OpsThresholdRow threshold = settings.opsThreshold(orgId, "DISK_FREE_PERCENT");
        Instant now = clock.instant();
        if (threshold == null || !threshold.enabled() || freePercent >= threshold.value()) {
            alarms.clearByKey(orgId, ALARM_KEY, freePercent, now, AlarmClearReason.AUTO, "SYSTEM", null);
            return;
        }
        AlarmSeverity severity = freePercent < threshold.value() / 2 ? AlarmSeverity.CRITICAL : severity(threshold.severity());
        String title = String.format(Locale.ROOT, "디스크 여유 공간 부족(%.1f%%, 기준 %.0f%%)", freePercent, threshold.value());
        AlarmRow row = alarms.raise(new AlarmService.Raise(orgId, ALARM_KEY, AlarmSourceType.SYSTEM, null, null, null, severity, title, null, null,
                null, freePercent, threshold.value(), threshold.value(), now, "SYSTEM", false, true));
        if (!severity.name().equals(row.severity()) && rank(severity.name()) > rank(row.severity())) {
            alarmRows.updateSeverity(orgId, row.id(), severity.name(), now);
            log.warn("디스크 여유 알람 승격: {} → {} ({}%)", row.severity(), severity, freePercent);
        }
    }

    Optional<Double> freePercent(long dbBytes) {
        if (capacityBytes <= 0) {
            return Optional.empty();
        }
        double free = 100.0 * Math.max(0, capacityBytes - dbBytes) / capacityBytes;
        return Optional.of(Math.round(free * 10.0) / 10.0);
    }

    private static AlarmSeverity severity(String raw) {
        try {
            return raw == null ? AlarmSeverity.MAJOR : AlarmSeverity.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return AlarmSeverity.MAJOR;
        }
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 5;
            case "MAJOR" -> 4;
            case "MINOR" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }
}
