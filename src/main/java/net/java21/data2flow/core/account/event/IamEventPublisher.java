package net.java21.data2flow.core.account.event;

import net.java21.data2flow.core.outbox.service.OutboxWriter;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IAM 도메인 이벤트 발행(design/api/IAM-api.md §8). 업무 트랜잭션 안에서 아웃박스에 기록하고 릴레이가
 * {@code data2flow.events}(topic)로 보낸다. 본문에는 {@code v=1}, {@code messageId}, {@code occurredAt}이 들어간다.
 * 토큰 폐기 알림(EVT-IAM-03, Redis Pub/Sub)은 auth가 발행하고, core는 auth 블랙리스트 등록(API-IAM-37b)을 아웃박스로 부른다.
 */
@Component
public class IamEventPublisher {

    public static final String USER_STATE_CHANGED = "iam.user.state.changed";
    public static final String USER_PERMISSION_CHANGED = "iam.user.permission.changed";
    public static final String SECURITY_ALERT = "iam.security.alert";
    public static final String ROLE_CHANGED = "iam.role.changed";

    private final OutboxWriter outbox;

    public IamEventPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** EVT-IAM-01 사용자 상태 변경 */
    public void userStateChanged(long orgId, long userId, String from, String to, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", Long.toString(userId));
        body.put("orgId", Long.toString(orgId));
        body.put("from", from);
        body.put("to", to);
        body.put("reason", reason);
        outbox.event(orgId, USER_STATE_CHANGED, body);
    }

    /** EVT-IAM-02 권한 변경(모든 서비스의 10초 권한 캐시 즉시 무효화) */
    public void permissionChanged(long orgId, long userId, String role, Long customRoleId, Collection<Long> spaceScope, int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", Long.toString(userId));
        body.put("orgId", Long.toString(orgId));
        body.put("role", role);
        body.put("customRoleId", customRoleId == null ? null : Long.toString(customRoleId));
        body.put("spaceScope", spaceScope == null ? List.of() : spaceScope.stream().map(String::valueOf).toList());
        body.put("version", version);
        outbox.event(orgId, USER_PERMISSION_CHANGED, body);
    }

    /** EVT-IAM-04 보안 경고(REFRESH_REUSED, BRUTE_FORCE, MFA_RESET, ADMIN_CREATED) */
    public void securityAlert(long orgId, String kind, Long userId, String ip, Map<String, Object> detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("userId", userId == null ? null : Long.toString(userId));
        body.put("ip", ip);
        body.put("detail", detail == null ? Map.of() : detail);
        outbox.event(orgId, SECURITY_ALERT, body);
    }

    /** EVT-IAM-05 사용자 정의 역할 변경 */
    public void roleChanged(long orgId, long customRoleId, Collection<String> permissions, int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customRoleId", Long.toString(customRoleId));
        body.put("permissions", permissions == null ? List.of() : List.copyOf(permissions));
        body.put("version", version);
        outbox.event(orgId, ROLE_CHANGED, body);
    }
}
