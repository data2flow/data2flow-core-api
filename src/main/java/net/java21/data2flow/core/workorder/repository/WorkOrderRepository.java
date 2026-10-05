package net.java21.data2flow.core.workorder.repository;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.workorder.domain.WorkOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 작업 지시와 대상·체크리스트·첨부·댓글(DEV-08.02·08.06, {@code work_orders} 외 4개 표) */
@Repository
public class WorkOrderRepository {

    private static final String COLUMNS = """
            w.id, w.organization_id, w.title, w.type, w.status, w.priority, w.assignee_id, w.requester_id, w.due_at, w.origin,
            w.origin_ref, w.linked_origins::text AS linked_origins, w.maintenance_plan_id, w.result::text AS result, w.completed_at,
            w.version, w.created_at, w.updated_at""";

    private final JdbcClient jdbc;

    public WorkOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 목록 조건. allowedSpaceIds가 null이면 공간 제한 없음 */
    public record Filter(long organizationId, List<String> statuses, Long assigneeId, Instant dueBefore, boolean overdue, Long spaceId,
                         String type, Instant createdFrom, Instant createdTo, Set<Long> allowedSpaceIds, Instant now) {
    }

    public Optional<WorkOrder> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.work_orders w WHERE w.organization_id = :org AND w.id = :id")
                .param("org", organizationId).param("id", id).query(WorkOrderRepository::map).optional();
    }

    public Optional<WorkOrder> lockById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.work_orders w WHERE w.organization_id = :org AND w.id = :id FOR UPDATE")
                .param("org", organizationId).param("id", id).query(WorkOrderRepository::map).optional();
    }

    /** 같은 출처(origin, originRef)의 열린 작업 지시 */
    public Optional<WorkOrder> findOpenByOrigin(long organizationId, String origin, String originRef) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM data2flow_core.work_orders w
                        WHERE w.organization_id = :org AND w.origin = :origin AND w.origin_ref = :ref
                          AND w.status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS') FOR UPDATE""")
                .param("org", organizationId).param("origin", origin).param("ref", originRef).query(WorkOrderRepository::map).optional();
    }

    /** BR-DEV-21: 같은 기기·같은 유형의 열린 작업 지시(가장 오래된 것) */
    public Optional<WorkOrder> findOpenByDeviceAndType(long organizationId, Collection<Long> deviceIds, String type) {
        if (deviceIds.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM data2flow_core.work_orders w
                        WHERE w.organization_id = :org AND w.type = :type AND w.status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS')
                          AND EXISTS (SELECT 1 FROM data2flow_core.work_order_targets t
                                       WHERE t.work_order_id = w.id AND t.organization_id = :org
                                         AND t.device_id = ANY(CAST(:ids AS bigint[])))
                        ORDER BY w.created_at, w.id LIMIT 1 FOR UPDATE""")
                .param("org", organizationId).param("type", type).param("ids", Pg.bigintArray(deviceIds))
                .query(WorkOrderRepository::map).optional();
    }

    public long insert(long organizationId, String title, String type, String status, String priority, Long assigneeId, Long requesterId,
                       Instant dueAt, String origin, String originRef, Long planId, Long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.work_orders (organization_id, title, type, status, priority, assignee_id, requester_id, due_at,
                               origin, origin_ref, maintenance_plan_id, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :title, :type, :status, :priority, :assignee, :requester, :due, :origin, :ref, :plan, :by, :by, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("title", title).param("type", type).param("status", status)
                .param("priority", priority).param("assignee", assigneeId).param("requester", requesterId).param("due", Pg.ts(dueAt))
                .param("origin", origin).param("ref", originRef).param("plan", planId).param("by", createdBy).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 덧붙인 출처 기록(BR-DEV-21) */
    public int appendLinkedOrigin(long organizationId, long id, String originJson, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.work_orders SET linked_origins = linked_origins || CAST(:o AS jsonb), updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("o", "[" + originJson + "]").param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    public int updateState(long organizationId, long id, int baseVersion, String status, Long assigneeId, String resultJson,
                           Instant completedAt, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.work_orders
                           SET status = :status, assignee_id = :assignee, result = CAST(:result AS jsonb), completed_at = :completed,
                               version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("status", status).param("assignee", assigneeId).param("result", resultJson).param("completed", Pg.ts(completedAt))
                .param("by", updatedBy).param("now", Pg.ts(now)).param("org", organizationId).param("id", id).param("base", baseVersion)
                .update();
    }

    public int touch(long organizationId, long id, long updatedBy, Instant now) {
        return jdbc.sql("UPDATE data2flow_core.work_orders SET updated_by = :by, updated_at = :now WHERE organization_id = :org AND id = :id")
                .param("by", updatedBy).param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    // ---- 목록(DEV-08.06)

    public List<WorkOrder> list(Filter f, int limit, long offset) {
        Where w = where(f);
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.work_orders w" + w.sql
                        + " ORDER BY w.due_at ASC NULLS LAST, w.id DESC LIMIT :limit OFFSET :offset")
                .params(w.params).param("limit", limit).param("offset", offset).query(WorkOrderRepository::map).list();
    }

    public long count(Filter f) {
        Where w = where(f);
        return jdbc.sql("SELECT count(*) FROM data2flow_core.work_orders w" + w.sql).params(w.params).query(Long.class).single();
    }

    /** 평균 처리 시간(생성 → 완료, 시간). 상태 조건은 보지 않고 DONE만(DEV-08.06) */
    public Double averageLeadTimeHours(Filter f) {
        Filter done = new Filter(f.organizationId(), List.of("DONE"), f.assigneeId(), null, false, f.spaceId(), f.type(), f.createdFrom(),
                f.createdTo(), f.allowedSpaceIds(), f.now());
        Where w = where(done);
        return jdbc.sql("SELECT avg(EXTRACT(EPOCH FROM (w.completed_at - w.created_at)) / 3600.0) FROM data2flow_core.work_orders w"
                        + w.sql + " AND w.completed_at IS NOT NULL")
                .params(w.params).query(Double.class).optional().orElse(null);
    }

    private static Where where(Filter f) {
        StringBuilder sql = new StringBuilder(" WHERE w.organization_id = :org");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("org", f.organizationId());
        if (!f.statuses().isEmpty()) {
            sql.append(" AND w.status = ANY(CAST(:statuses AS text[]))");
            p.put("statuses", Pg.textArray(f.statuses()));
        }
        if (f.assigneeId() != null) {
            sql.append(" AND w.assignee_id = :assignee");
            p.put("assignee", f.assigneeId());
        }
        if (f.dueBefore() != null) {
            sql.append(" AND w.due_at <= :dueBefore AND w.status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS')");
            p.put("dueBefore", Pg.ts(f.dueBefore()));
        }
        if (f.overdue()) {
            sql.append(" AND w.due_at < :now AND w.status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS')");
            p.put("now", Pg.ts(f.now()));
        }
        if (f.type() != null) {
            sql.append(" AND w.type = :type");
            p.put("type", f.type());
        }
        if (f.createdFrom() != null) {
            sql.append(" AND w.created_at >= :createdFrom");
            p.put("createdFrom", Pg.ts(f.createdFrom()));
        }
        if (f.createdTo() != null) {
            sql.append(" AND w.created_at < :createdTo");
            p.put("createdTo", Pg.ts(f.createdTo()));
        }
        if (f.spaceId() != null) {
            // 공간 필터는 하위 공간을 포함한다: 대상 공간이나 대상 기기의 공간이 그 공간 아래
            sql.append(" ").append("""
                     AND EXISTS (SELECT 1 FROM data2flow_core.work_order_targets t
                                   LEFT JOIN data2flow_core.devices d ON d.id = t.device_id AND d.organization_id = t.organization_id
                                   JOIN data2flow_core.spaces s ON s.id = coalesce(t.space_id, d.space_id) AND s.organization_id = t.organization_id
                                   JOIN data2flow_core.spaces root ON root.id = :spaceId AND root.organization_id = t.organization_id
                                  WHERE t.work_order_id = w.id AND t.organization_id = w.organization_id AND s.path LIKE root.path || '%')""");
            p.put("spaceId", f.spaceId());
        }
        if (f.allowedSpaceIds() != null) {
            sql.append(" ").append("""
                     AND EXISTS (SELECT 1 FROM data2flow_core.work_order_targets t
                                   LEFT JOIN data2flow_core.devices d ON d.id = t.device_id AND d.organization_id = t.organization_id
                                  WHERE t.work_order_id = w.id AND t.organization_id = w.organization_id
                                    AND coalesce(t.space_id, d.space_id) = ANY(CAST(:allowed AS bigint[])))""");
            p.put("allowed", Pg.bigintArray(f.allowedSpaceIds()));
        }
        return new Where(sql.toString(), p);
    }

    private record Where(String sql, Map<String, Object> params) {
    }

    // ---- 대상

    public void insertTargets(long organizationId, long workOrderId, List<Target> targets) {
        for (Target t : targets) {
            jdbc.sql("INSERT INTO data2flow_core.work_order_targets (organization_id, work_order_id, device_id, space_id) VALUES (:org, :wo, :d, :s)")
                    .param("org", organizationId).param("wo", workOrderId).param("d", t.deviceId()).param("s", t.spaceId()).update();
        }
    }

    /** 작업 지시별 대상(대상 기기의 현재 공간 포함) */
    public Map<Long, List<Target>> findTargets(long organizationId, Collection<Long> workOrderIds) {
        Map<Long, List<Target>> out = new LinkedHashMap<>();
        if (workOrderIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT t.work_order_id, t.device_id, coalesce(t.space_id, d.space_id) AS space_id, t.space_id IS NULL AS via_device
                          FROM data2flow_core.work_order_targets t
                          LEFT JOIN data2flow_core.devices d ON d.id = t.device_id AND d.organization_id = t.organization_id
                         WHERE t.organization_id = :org AND t.work_order_id = ANY(CAST(:ids AS bigint[])) ORDER BY t.id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(workOrderIds))
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getLong("work_order_id"), k -> new ArrayList<>())
                            .add(new Target(Pg.longOrNull(rs, "device_id"), Pg.longOrNull(rs, "space_id")));
                    return null;
                }).list();
        return out;
    }

    public record Target(Long deviceId, Long spaceId) {
    }

    // ---- 체크리스트

    public void insertChecklist(long organizationId, long workOrderId, List<String> items) {
        int i = 0;
        for (String text : items) {
            jdbc.sql("INSERT INTO data2flow_core.work_order_checklists (organization_id, work_order_id, sort_order, text) VALUES (:org, :wo, :i, :t)")
                    .param("org", organizationId).param("wo", workOrderId).param("i", i++).param("t", text).update();
        }
    }

    public Map<Long, List<ChecklistItem>> findChecklists(long organizationId, Collection<Long> workOrderIds) {
        Map<Long, List<ChecklistItem>> out = new LinkedHashMap<>();
        if (workOrderIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT id, work_order_id, text, done, done_by, done_at FROM data2flow_core.work_order_checklists
                         WHERE organization_id = :org AND work_order_id = ANY(CAST(:ids AS bigint[])) ORDER BY sort_order, id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(workOrderIds))
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getLong("work_order_id"), k -> new ArrayList<>()).add(checklist(rs));
                    return null;
                }).list();
        return out;
    }

    public Optional<ChecklistItem> findChecklistItem(long organizationId, long workOrderId, long itemId) {
        return jdbc.sql("""
                        SELECT id, work_order_id, text, done, done_by, done_at FROM data2flow_core.work_order_checklists
                         WHERE organization_id = :org AND work_order_id = :wo AND id = :id""")
                .param("org", organizationId).param("wo", workOrderId).param("id", itemId).query((rs, n) -> checklist(rs)).optional();
    }

    public int updateChecklistItem(long organizationId, long workOrderId, long itemId, boolean done, Long doneBy, Instant doneAt) {
        return jdbc.sql("""
                        UPDATE data2flow_core.work_order_checklists SET done = :done, done_by = :by, done_at = :at
                         WHERE organization_id = :org AND work_order_id = :wo AND id = :id""")
                .param("done", done).param("by", doneBy).param("at", Pg.ts(doneAt)).param("org", organizationId).param("wo", workOrderId)
                .param("id", itemId).update();
    }

    private static ChecklistItem checklist(ResultSet rs) throws SQLException {
        return new ChecklistItem(rs.getLong("id"), rs.getString("text"), rs.getBoolean("done"), Pg.longOrNull(rs, "done_by"),
                Pg.instant(rs, "done_at"));
    }

    public record ChecklistItem(long id, String text, boolean done, Long doneBy, Instant doneAt) {
    }

    // ---- 첨부·댓글

    public long insertAttachment(long organizationId, long workOrderId, String objectKey, String kind, long uploadedBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.work_order_attachments (organization_id, work_order_id, object_key, kind, uploaded_by, created_at)
                        VALUES (:org, :wo, :key, :kind, :by, :now) RETURNING id""")
                .param("org", organizationId).param("wo", workOrderId).param("key", objectKey).param("kind", kind).param("by", uploadedBy)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public List<Attachment> findAttachments(long organizationId, long workOrderId) {
        return jdbc.sql("""
                        SELECT a.id, a.work_order_id, a.object_key, a.kind, a.uploaded_by, a.created_at, f.file_name, f.size_bytes, f.content_type
                          FROM data2flow_core.work_order_attachments a
                          LEFT JOIN data2flow_core.file_blobs f ON a.object_key = 'db:file_blobs/' || f.id AND f.organization_id = a.organization_id
                         WHERE a.organization_id = :org AND a.work_order_id = :wo ORDER BY a.id""")
                .param("org", organizationId).param("wo", workOrderId).query(WorkOrderRepository::attachment).list();
    }

    public Optional<Attachment> findAttachment(long organizationId, long workOrderId, long attachmentId) {
        return jdbc.sql("""
                        SELECT a.id, a.work_order_id, a.object_key, a.kind, a.uploaded_by, a.created_at, f.file_name, f.size_bytes, f.content_type
                          FROM data2flow_core.work_order_attachments a
                          LEFT JOIN data2flow_core.file_blobs f ON a.object_key = 'db:file_blobs/' || f.id AND f.organization_id = a.organization_id
                         WHERE a.organization_id = :org AND a.work_order_id = :wo AND a.id = :id""")
                .param("org", organizationId).param("wo", workOrderId).param("id", attachmentId).query(WorkOrderRepository::attachment).optional();
    }

    public int deleteAttachment(long organizationId, long workOrderId, long attachmentId) {
        return jdbc.sql("DELETE FROM data2flow_core.work_order_attachments WHERE organization_id = :org AND work_order_id = :wo AND id = :id")
                .param("org", organizationId).param("wo", workOrderId).param("id", attachmentId).update();
    }

    private static Attachment attachment(ResultSet rs, int n) throws SQLException {
        return new Attachment(rs.getLong("id"), rs.getLong("work_order_id"), rs.getString("object_key"), rs.getString("kind"),
                rs.getLong("uploaded_by"), Pg.instant(rs, "created_at"), rs.getString("file_name"), rs.getLong("size_bytes"),
                rs.getString("content_type"));
    }

    public record Attachment(long id, long workOrderId, String objectKey, String kind, long uploadedBy, Instant createdAt, String fileName,
                             long sizeBytes, String contentType) {
    }

    public long insertComment(long organizationId, long workOrderId, long authorId, String body, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.work_order_comments (organization_id, work_order_id, author_id, body, created_at)
                        VALUES (:org, :wo, :a, :b, :now) RETURNING id""")
                .param("org", organizationId).param("wo", workOrderId).param("a", authorId).param("b", body).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    public List<Comment> findComments(long organizationId, long workOrderId) {
        return jdbc.sql("""
                        SELECT c.id, c.author_id, u.name AS author_name, c.body, c.created_at
                          FROM data2flow_core.work_order_comments c
                          LEFT JOIN data2flow_core.app_users u ON u.id = c.author_id AND u.organization_id = c.organization_id
                         WHERE c.organization_id = :org AND c.work_order_id = :wo ORDER BY c.created_at, c.id""")
                .param("org", organizationId).param("wo", workOrderId)
                .query((rs, n) -> new Comment(rs.getLong("id"), rs.getLong("author_id"), rs.getString("author_name"), rs.getString("body"),
                        Pg.instant(rs, "created_at")))
                .list();
    }

    public record Comment(long id, long authorId, String authorName, String body, Instant createdAt) {
    }

    // ---- 참조

    /** 조직의 활성 사용자인가(담당자 지정) */
    public boolean existsActiveUser(long organizationId, long userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.app_users WHERE organization_id = :org AND id = :id AND status = 'ACTIVE')")
                .param("org", organizationId).param("id", userId).query(Boolean.class).single();
    }

    static WorkOrder map(ResultSet rs, int n) throws SQLException {
        return new WorkOrder(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("title"), rs.getString("type"),
                rs.getString("status"), rs.getString("priority"), Pg.longOrNull(rs, "assignee_id"), Pg.longOrNull(rs, "requester_id"),
                Pg.instant(rs, "due_at"), rs.getString("origin"), rs.getString("origin_ref"), rs.getString("linked_origins"),
                Pg.longOrNull(rs, "maintenance_plan_id"), rs.getString("result"), Pg.instant(rs, "completed_at"), rs.getInt("version"),
                Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }
}
