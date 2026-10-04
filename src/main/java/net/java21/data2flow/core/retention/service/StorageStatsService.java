package net.java21.data2flow.core.retention.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.core.retention.dto.RetentionDtos.PartitionStat;
import net.java21.data2flow.core.retention.dto.RetentionDtos.Range;
import net.java21.data2flow.core.retention.dto.RetentionDtos.StorageStatsResponse;
import net.java21.data2flow.core.retention.repository.StorageStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 저장 현황(API-TSD-43, TSD-02.03): 시계열·원본 월 파티션의 상태(CREATED·COMPRESSED·ARCHIVED·DROPPED)·행 수·크기·콜드 압축 비율 */
@Service
public class StorageStatsService {

    private final RoleChecker roleChecker;
    private final StorageStatsRepository stats;

    public StorageStatsService(RoleChecker roleChecker, StorageStatsRepository stats) {
        this.roleChecker = roleChecker;
        this.stats = stats;
    }

    @Transactional(readOnly = true)
    public StorageStatsResponse stats() {
        roleChecker.require(Permission.TS_POLICY);
        List<PartitionStat> partitions = stats.listPartitions().stream()
                .map(p -> new PartitionStat(p.table(), p.name(), new Range(p.rangeFrom(), p.rangeTo()), p.state(), p.rows(), p.bytes(),
                        p.compressionRatio()))
                .toList();
        long total = partitions.stream().filter(p -> !"DROPPED".equals(p.state()) && p.bytes() != null).mapToLong(PartitionStat::bytes).sum();
        return new StorageStatsResponse(partitions, total);
    }
}
