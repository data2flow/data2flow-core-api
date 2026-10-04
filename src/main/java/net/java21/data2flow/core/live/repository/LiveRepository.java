package net.java21.data2flow.core.live.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 실시간 스트림 읽기(API-DSH-20·21): 토픽 권한 판정용 기기·공간·소스 조회, 연결 중 세션·계정 상태 확인(IAM-07.06),
 * pipeline 소유 {@code data2flow_pipeline.raw_messages} 폴링(읽기 전용).
 */
@Repository
public class LiveRepository {

    private final JdbcClient jdbc;

    public LiveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 삭제되지 않은 기기의 공간·상태 */
    public Optional<DeviceRef> findDevice(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT id, space_id, status FROM data2flow_core.devices
                         WHERE organization_id = :org AND id = :id AND status <> 'DELETED'""")
                .param("org", organizationId).param("id", deviceId)
                .query((rs, n) -> new DeviceRef(rs.getLong("id"), Pg.longOrNull(rs, "space_id"), rs.getString("status")))
                .optional();
    }

    /** 여러 기기의 공간·상태(id → 참조) */
    public Map<Long, DeviceRef> findDevices(long organizationId, Collection<Long> deviceIds) {
        Map<Long, DeviceRef> result = new HashMap<>();
        if (deviceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT id, space_id, status FROM data2flow_core.devices
                         WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))""")
                .param("org", organizationId).param("ids", Pg.bigintArray(List.copyOf(deviceIds)))
                .query((ResultSet rs) -> {
                    result.put(rs.getLong("id"), new DeviceRef(rs.getLong("id"), Pg.longOrNull(rs, "space_id"), rs.getString("status")));
                });
        return result;
    }

    /** ACTIVE 공간과 그 하위 공간 ID(없으면 빈 집합). path는 조상 ID를 / 로 이은 값(/1/4/9/) */
    public Set<Long> findSubtreeIds(long organizationId, long spaceId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT c.id FROM data2flow_core.spaces p
                          JOIN data2flow_core.spaces c ON c.organization_id = p.organization_id AND c.path LIKE p.path || '%'
                         WHERE p.organization_id = :org AND p.id = :id AND p.status = 'ACTIVE' AND c.status = 'ACTIVE'""")
                .param("org", organizationId).param("id", spaceId).query(Long.class).list());
    }

    public boolean existsSource(long organizationId, long sourceId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.data_sources WHERE organization_id = :org AND id = :id)")
                .param("org", organizationId).param("id", sourceId).query(Boolean.class).single();
    }

    /**
     * 연결 중 확인: 계정 상태와 세션 폐기 여부. 세션 ID가 없거나(장기 토큰·테스트) 계보 행이 없으면 폐기로 보지 않는다.
     * 세션은 그 세션의 Refresh 계보가 모두 revoked_at을 가지면 폐기된 것이다(BR-IAM-12).
     */
    public SessionState findSessionState(long organizationId, long userId, UUID sessionId) {
        String status = jdbc.sql("SELECT status FROM data2flow_core.app_users WHERE organization_id = :org AND id = :user")
                .param("org", organizationId).param("user", userId).query(String.class).optional().orElse("DELETED");
        boolean revoked = false;
        if (sessionId != null) {
            revoked = jdbc.sql("""
                            SELECT count(*) > 0 AND count(*) FILTER (WHERE revoked_at IS NULL) = 0
                              FROM data2flow_core.refresh_tokens
                             WHERE organization_id = :org AND user_id = :user AND session_id = :sid""")
                    .param("org", organizationId).param("user", userId).param("sid", sessionId).query(Boolean.class).single();
        }
        return new SessionState(status, revoked);
    }

    /** 이 시각 이후 원본 메시지의 가장 큰 ID(폴링 시작점). 없으면 0 */
    public long findMaxRawMessageId(long organizationId, Instant since) {
        return jdbc.sql("""
                        SELECT COALESCE(max(id), 0) FROM data2flow_pipeline.raw_messages
                         WHERE organization_id = :org AND received_at >= :since""")
                .param("org", organizationId).param("since", Pg.ts(since)).query(Long.class).single();
    }

    /**
     * ID 순서로 afterId 다음 원본 메시지. IDENTITY라 대체로 수신 순서이고, 커밋이 늦은 행은 놓칠 수 있다(손실 허용, API-DSH-21 표본).
     * received_at 조건은 파티션을 최근 것으로 줄이려는 것이다.
     */
    public List<RawRow> findRawMessagesAfter(long organizationId, long afterId, Instant since, int limit) {
        return jdbc.sql("""
                        SELECT id, source_id, device_id, topic, external_id, payload, payload_encoding, status, error_code, received_at,
                               processing_trace::text AS trace
                          FROM data2flow_pipeline.raw_messages
                         WHERE organization_id = :org AND id > :after AND received_at >= :since
                         ORDER BY id LIMIT :limit""")
                .param("org", organizationId).param("after", afterId).param("since", Pg.ts(since)).param("limit", limit)
                .query((rs, n) -> new RawRow(rs.getLong("id"), rs.getLong("source_id"), Pg.longOrNull(rs, "device_id"),
                        rs.getString("topic"), rs.getString("external_id"), rs.getBytes("payload"), rs.getString("payload_encoding"),
                        rs.getString("status"), rs.getString("error_code"), Pg.instant(rs, "received_at"), rs.getString("trace")))
                .list();
    }

    public record DeviceRef(long id, Long spaceId, String status) {
    }

    public record SessionState(String userStatus, boolean sessionRevoked) {

        public boolean userActive() {
            return "ACTIVE".equals(userStatus);
        }
    }

    public record RawRow(long id, long sourceId, Long deviceId, String topic, String externalId, byte[] payload, String encoding,
                         String status, String errorCode, Instant receivedAt, String trace) {
    }

    /** 명령 출처 표시 이름(flowName·userName, ADR-043). 없으면 빈 맵 */
    public java.util.Map<String, Object> findSourceNames(long organizationId, String flowId, Long userId) {
        java.util.Map<String, Object> names = new java.util.LinkedHashMap<>();
        if (flowId != null && flowId.matches("[0-9a-fA-F-]{36}")) {
            jdbc.sql("SELECT name FROM data2flow_core.flows WHERE organization_id = :org AND id = CAST(:id AS uuid)")
                    .param("org", organizationId).param("id", flowId).query(String.class).optional()
                    .ifPresent(n -> names.put("flowName", n));
        }
        if (userId != null) {
            jdbc.sql("SELECT name FROM data2flow_core.app_users WHERE organization_id = :org AND id = :id")
                    .param("org", organizationId).param("id", userId).query(String.class).optional()
                    .ifPresent(n -> names.put("userName", n));
        }
        return names;
    }

    /** 알람 제목(웹 알림 SSE) */
    public java.util.Optional<String> findAlarmTitle(long organizationId, long alarmId) {
        return jdbc.sql("SELECT title FROM data2flow_core.alarms WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", alarmId).query(String.class).optional();
    }
}
