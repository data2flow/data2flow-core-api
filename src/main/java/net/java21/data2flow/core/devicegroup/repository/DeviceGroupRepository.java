package net.java21.data2flow.core.devicegroup.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.device.domain.SqlCondition;
import net.java21.data2flow.core.devicesearch.domain.DeviceQueryParser;
import net.java21.data2flow.core.devicesearch.domain.DeviceQuerySql;
import net.java21.data2flow.core.devicegroup.domain.DeviceGroup;
import net.java21.data2flow.core.devicegroup.domain.GroupCriteria;
import net.java21.data2flow.core.devicegroup.domain.GroupCriteria.AttributeCondition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** 기기 그룹({@code device_groups})·구성원({@code device_group_members}), 동적 그룹 조건 평가(DEV-06, BR-DEV-12·13) */
@Repository
public class DeviceGroupRepository {

    private static final String COLUMNS = """
            id, organization_id, name, type, criteria::text AS criteria, member_count, description, version, created_at, updated_at""";

    private final JdbcClient jdbc;

    public DeviceGroupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DeviceGroup> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.device_groups WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).query(DeviceGroupRepository::map).optional();
    }

    public List<DeviceGroup> list(long organizationId, String q, String type, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.device_groups WHERE organization_id = :org"
                        + " AND (CAST(:q AS text) IS NULL OR name ILIKE '%' || CAST(:q AS text) || '%')"
                        + " AND (CAST(:type AS text) IS NULL OR type = :type) ORDER BY name, id LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("q", q).param("type", type).param("limit", limit).param("offset", offset)
                .query(DeviceGroupRepository::map).list();
    }

    public long count(long organizationId, String q, String type) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.device_groups WHERE organization_id = :org"
                        + " AND (CAST(:q AS text) IS NULL OR name ILIKE '%' || CAST(:q AS text) || '%')"
                        + " AND (CAST(:type AS text) IS NULL OR type = :type)")
                .param("org", organizationId).param("q", q).param("type", type).query(Long.class).single();
    }

    public List<DeviceGroup> findDynamic(long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.device_groups WHERE organization_id = :org AND type = 'DYNAMIC' ORDER BY id")
                .param("org", organizationId).query(DeviceGroupRepository::map).list();
    }

    /** 동적 그룹이 있는 조직(1시간 전체 재계산). 배포 조직으로 좁힌다(ADR-030) */
    @OrganizationScopeExempt("모든 조직을 도는 1시간 재계산(BR-DEV-13). DeploymentOrganization.restriction()으로 배포 조직만")
    public List<Long> findOrganizationsWithDynamicGroups(OptionalLong onlyOrganization) {
        Long org = onlyOrganization.isPresent() ? onlyOrganization.getAsLong() : null;
        return jdbc.sql("SELECT DISTINCT organization_id FROM data2flow_core.device_groups WHERE type = 'DYNAMIC'"
                        + " AND (CAST(:org AS bigint) IS NULL OR organization_id = :org) ORDER BY 1")
                .param("org", org).query(Long.class).list();
    }

    public boolean existsName(long organizationId, String name, Long exceptId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.device_groups WHERE organization_id = :org AND lower(name) = lower(:name)"
                        + " AND (CAST(:except AS bigint) IS NULL OR id <> :except))")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    public long insert(long organizationId, String name, String type, String criteriaJson, String description, long createdBy,
                       Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.device_groups (organization_id, name, type, criteria, description, created_by, updated_by,
                                                                  created_at, updated_at)
                        VALUES (:org, :name, :type, CAST(:criteria AS jsonb), :description, :by, :by, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", name).param("type", type).param("criteria", criteriaJson)
                .param("description", description).param("by", createdBy).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    public int update(long organizationId, long id, int baseVersion, String name, String criteriaJson, String description,
                      long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.device_groups
                           SET name = :name, criteria = CAST(:criteria AS jsonb), description = :description, version = version + 1,
                               updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("name", name).param("criteria", criteriaJson).param("description", description).param("by", updatedBy)
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", id).param("base", baseVersion)
                .update();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.device_groups WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    public List<Long> findMemberIds(long organizationId, long groupId) {
        return jdbc.sql("SELECT device_id FROM data2flow_core.device_group_members WHERE organization_id = :org AND group_id = :id ORDER BY device_id")
                .param("org", organizationId).param("id", groupId).query(Long.class).list();
    }

    public List<Long> findGroupIdsOfDevice(long organizationId, long deviceId) {
        return jdbc.sql("SELECT group_id FROM data2flow_core.device_group_members WHERE organization_id = :org AND device_id = :d ORDER BY group_id")
                .param("org", organizationId).param("d", deviceId).query(Long.class).list();
    }

    public boolean isMember(long organizationId, long groupId, long deviceId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.device_group_members WHERE organization_id = :org AND group_id = :g"
                        + " AND device_id = :d)")
                .param("org", organizationId).param("g", groupId).param("d", deviceId).query(Boolean.class).single();
    }

    /** 구성원 추가. 실제로 들어간 기기 ID */
    public List<Long> addMembers(long organizationId, long groupId, Collection<Long> deviceIds, String source, Instant now) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        INSERT INTO data2flow_core.device_group_members (organization_id, group_id, device_id, source, added_at)
                        SELECT :org, :g, x, :source, :now FROM unnest(CAST(:ids AS bigint[])) AS x
                        ON CONFLICT (group_id, device_id) DO NOTHING RETURNING device_id""")
                .param("org", organizationId).param("g", groupId).param("source", source).param("now", Pg.ts(now))
                .param("ids", Pg.bigintArray(deviceIds)).query(Long.class).list();
    }

    /** 구성원 제거. 실제로 빠진 기기 ID */
    public List<Long> removeMembers(long organizationId, long groupId, Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        DELETE FROM data2flow_core.device_group_members
                         WHERE organization_id = :org AND group_id = :g AND device_id = ANY(CAST(:ids AS bigint[])) RETURNING device_id""")
                .param("org", organizationId).param("g", groupId).param("ids", Pg.bigintArray(deviceIds)).query(Long.class).list();
    }

    /** member_count를 실제 행 수로 맞춘다 */
    public int updateMemberCount(long organizationId, long groupId) {
        return jdbc.sql("""
                        UPDATE data2flow_core.device_groups
                           SET member_count = (SELECT count(*) FROM data2flow_core.device_group_members WHERE group_id = :g)
                         WHERE organization_id = :org AND id = :g RETURNING member_count""")
                .param("org", organizationId).param("g", groupId).query(Integer.class).optional().orElse(0);
    }

    /** 이 조직 기기 중 조직에 있는(삭제되지 않은) 기기 ID만 */
    public List<Long> findExistingDeviceIds(long organizationId, Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id FROM data2flow_core.devices
                         WHERE organization_id = :org AND status <> 'DELETED' AND id = ANY(CAST(:ids AS bigint[])) ORDER BY id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds)).query(Long.class).list();
    }

    /**
     * 동적 그룹 조건에 맞는 기기 ID(삭제 제외). {@code onlyDeviceId}가 있으면 그 기기만 본다(기기 변경 시 부분 재계산).
     * {@code allowedSpaceIds}가 있으면 그 공간 기기만(미리 보기의 공간 범위, IAM-04.06).
     */
    public List<Long> findMatchingDeviceIds(long organizationId, GroupCriteria criteria, Long onlyDeviceId, Set<Long> allowedSpaceIds,
                                            int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = criteriaWhere(organizationId, criteria, params);
        StringBuilder sql = new StringBuilder("SELECT d.id FROM data2flow_core.devices d").append(where);
        if (onlyDeviceId != null) {
            sql.append(" AND d.id = :only");
            params.put("only", onlyDeviceId);
        }
        if (allowedSpaceIds != null) {
            sql.append(" AND d.space_id = ANY(CAST(:allowed AS bigint[]))");
            params.put("allowed", Pg.bigintArray(allowedSpaceIds));
        }
        sql.append(" ORDER BY d.id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(Long.class).list();
    }

    public long countMatching(long organizationId, GroupCriteria criteria, Set<Long> allowedSpaceIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM data2flow_core.devices d").append(criteriaWhere(organizationId, criteria, params));
        if (allowedSpaceIds != null) {
            sql.append(" AND d.space_id = ANY(CAST(:allowed AS bigint[]))");
            params.put("allowed", Pg.bigintArray(allowedSpaceIds));
        }
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    /** 미리 보기 표본 [id, name] */
    public List<Object[]> findDeviceNames(long organizationId, Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, name FROM data2flow_core.devices WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[])) ORDER BY id")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds))
                .query((rs, n) -> new Object[] {rs.getLong("id"), rs.getString("name")}).list();
    }

    static String criteriaWhere(long organizationId, GroupCriteria c, Map<String, Object> p) {
        StringBuilder sql = new StringBuilder(" WHERE d.organization_id = :org AND d.status <> 'DELETED'");
        p.put("org", organizationId);
        if (!c.modelIds().isEmpty() || !c.modelCodes().isEmpty()) {
            sql.append(" AND d.model_id IN (SELECT m.id FROM data2flow_core.device_models m WHERE m.organization_id = :org"
                    + " AND (m.id = ANY(CAST(:modelIds AS bigint[])) OR m.code = ANY(CAST(:modelCodes AS text[]))))");
            p.put("modelIds", Pg.bigintArray(c.modelIds()));
            p.put("modelCodes", Pg.textArray(c.modelCodes()));
        }
        if (!c.spaceIds().isEmpty()) {
            if (c.includeDescendants()) {
                sql.append(" AND d.space_id IN (SELECT ch.id FROM data2flow_core.spaces ch JOIN data2flow_core.spaces sp"
                        + " ON ch.path LIKE sp.path || '%' WHERE sp.organization_id = :org AND ch.organization_id = :org"
                        + " AND sp.id = ANY(CAST(:spaceIds AS bigint[])))");
            } else {
                sql.append(" AND d.space_id = ANY(CAST(:spaceIds AS bigint[]))");
            }
            p.put("spaceIds", Pg.bigintArray(c.spaceIds()));
        }
        if (!c.tagsAny().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM data2flow_core.device_tags t WHERE t.device_id = d.id"
                    + " AND lower(t.tag) = ANY(CAST(:tagsAny AS text[])))");
            p.put("tagsAny", Pg.textArray(c.tagsAny().stream().map(t -> t.toLowerCase(Locale.ROOT)).distinct().toList()));
        }
        if (!c.tagsAll().isEmpty()) {
            List<String> all = c.tagsAll().stream().map(t -> t.toLowerCase(Locale.ROOT)).distinct().toList();
            sql.append(" AND (SELECT count(DISTINCT lower(t.tag)) FROM data2flow_core.device_tags t WHERE t.device_id = d.id"
                    + " AND lower(t.tag) = ANY(CAST(:tagsAll AS text[]))) = :tagsAllCount");
            p.put("tagsAll", Pg.textArray(all));
            p.put("tagsAllCount", all.size());
        }
        if (!c.statuses().isEmpty()) {
            sql.append(" AND d.status = ANY(CAST(:statuses AS text[]))");
            p.put("statuses", Pg.textArray(c.statuses()));
        }
        if (c.query() != null) {
            SqlCondition q = DeviceQuerySql.toSql(DeviceQueryParser.parse(c.query()), null);
            sql.append(" AND (").append(q.sql()).append(')');
            p.putAll(q.params());
        }
        int i = 0;
        for (AttributeCondition a : c.attributes()) {
            String k = "attrKey" + i;
            String v = "attrValue" + i;
            p.put(k, a.key());
            String cmp = switch (a.op()) {
                case "EXISTS" -> "";
                case "EQ" -> " AND a.value = CAST(:" + v + " AS jsonb)";
                case "NE" -> " AND a.value <> CAST(:" + v + " AS jsonb)";
                default -> " AND jsonb_typeof(a.value) = 'number' AND (a.value #>> '{}')::numeric " + switch (a.op()) {
                    case "GT" -> ">";
                    case "GTE" -> ">=";
                    case "LT" -> "<";
                    default -> "<=";
                } + " :" + v;
            };
            if (a.op().equals("EQ") || a.op().equals("NE")) {
                p.put(v, a.valueJson());
            } else if (!a.op().equals("EXISTS")) {
                p.put(v, a.number());
            }
            sql.append(" AND EXISTS (SELECT 1 FROM data2flow_core.device_attributes a WHERE a.device_id = d.id AND a.key = :")
                    .append(k).append(cmp).append(')');
            i++;
        }
        return sql.toString();
    }

    static DeviceGroup map(ResultSet rs, int n) throws SQLException {
        return new DeviceGroup(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("type"),
                rs.getString("criteria"), rs.getInt("member_count"), rs.getString("description"), rs.getInt("version"),
                Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }
}
