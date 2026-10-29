package net.java21.data2flow.core.energy.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 전기 고지서 저장소(ENE-01.01, {@code energy_bills}). 달은 그 달 1일로 저장한다 */
@Repository
public class EnergyBillRepository {

    private static final String BILL = """
            SELECT b.id, b.organization_id, b.space_id, b.month, b.kwh, b.amount_krw, b.attachment_object_key, b.created_by, u.name AS created_by_name,
                   b.updated_at
              FROM data2flow_core.energy_bills b
              LEFT JOIN data2flow_core.app_users u ON u.id = b.created_by
            """;

    private final JdbcClient jdbc;

    public EnergyBillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record BillRow(long id, long organizationId, long spaceId, LocalDate month, BigDecimal kwh, BigDecimal amountKrw, String attachmentObjectKey,
                          long createdBy, String createdByName, Instant updatedAt) {
    }

    /** 조직의 한 해 고지서(달 순). {@code spaceId}가 있으면 그 공간 것만 */
    public List<BillRow> list(long orgId, Long spaceId, LocalDate from, LocalDate to) {
        return jdbc.sql(BILL + """
                         WHERE b.organization_id = :org AND (CAST(:space AS bigint) IS NULL OR b.space_id = :space) AND b.month BETWEEN :from AND :to
                         ORDER BY b.space_id, b.month""")
                .param("org", orgId).param("space", spaceId).param("from", from).param("to", to).query(EnergyBillRepository::bill).list();
    }

    public Optional<BillRow> find(long orgId, long spaceId, LocalDate month) {
        return jdbc.sql(BILL + " WHERE b.organization_id = :org AND b.space_id = :space AND b.month = :month").param("org", orgId)
                .param("space", spaceId).param("month", month).query(EnergyBillRepository::bill).optional();
    }

    /** 새 달은 넣고, 있는 달은 덮어쓴다(덮어쓸지는 서비스가 정한다) */
    public void upsert(long orgId, long spaceId, LocalDate month, BigDecimal kwh, BigDecimal amountKrw, String attachment, long userId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.energy_bills (organization_id, space_id, month, kwh, amount_krw, attachment_object_key, created_by,
                               created_at, updated_at)
                        VALUES (:org, :space, :month, :kwh, :amount, :attachment, :by, :now, :now)
                        ON CONFLICT (space_id, month) DO UPDATE SET kwh = EXCLUDED.kwh, amount_krw = EXCLUDED.amount_krw,
                               attachment_object_key = EXCLUDED.attachment_object_key, created_by = EXCLUDED.created_by, updated_at = EXCLUDED.updated_at""")
                .param("org", orgId).param("space", spaceId).param("month", month).param("kwh", kwh).param("amount", amountKrw)
                .param("attachment", attachment).param("by", userId).param("now", Pg.ts(now)).update();
    }

    public int delete(long orgId, long spaceId, LocalDate month) {
        return jdbc.sql("DELETE FROM data2flow_core.energy_bills WHERE organization_id = :org AND space_id = :space AND month = :month")
                .param("org", orgId).param("space", spaceId).param("month", month).update();
    }

    static BillRow bill(ResultSet rs, int n) throws SQLException {
        return new BillRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("space_id"), rs.getObject("month", LocalDate.class),
                rs.getBigDecimal("kwh"), rs.getBigDecimal("amount_krw"), rs.getString("attachment_object_key"), rs.getLong("created_by"),
                rs.getString("created_by_name"), Pg.instant(rs, "updated_at"));
    }
}
