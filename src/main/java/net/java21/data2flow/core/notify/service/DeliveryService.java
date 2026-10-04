package net.java21.data2flow.core.notify.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.core.alarm.service.AlarmQueryService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 발송 이력(API-RUL-27 = API-OPS-32, 커서 목록)과 재발송(API-OPS-33). 이력은 action {@code data2flow_action.notification_deliveries}에
 * 있어 action 내부 API를 그대로 중계한다. 조회는 ALARM_READ(알람으로 거르면 그 알람이 보여야 함), 재발송은 NOTIFY_CHANNEL_MANAGE.
 */
@Service
public class DeliveryService {

    private final NotificationActionClient action;
    private final AlarmQueryService alarms;
    private final RoleChecker roleChecker;

    public DeliveryService(NotificationActionClient action, AlarmQueryService alarms, RoleChecker roleChecker) {
        this.action = action;
        this.alarms = alarms;
        this.roleChecker = roleChecker;
    }

    public JsonNode list(String alarmId, String channelId, String status, String from, String to, String cursor, Integer size) {
        roleChecker.require(Permission.ALARM_READ);
        long orgId = roleChecker.currentUser().organizationId();
        if (alarmId != null && !alarmId.isBlank()) {
            alarms.visible(orgId, AlarmQueryService.id(alarmId, "alarmId"));
        } else if (!roleChecker.has(Permission.NOTIFY_CHANNEL_MANAGE) && !roleChecker.spaceScope().unrestricted()) {
            // 범위가 제한된 사용자는 알람별로만 본다(다른 공간 알람의 수신자가 보이지 않게)
            roleChecker.require(Permission.NOTIFY_CHANNEL_MANAGE);
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("organizationId", orgId);
        put(query, "alarmId", alarmId);
        put(query, "channelId", channelId);
        put(query, "status", status);
        put(query, "from", from);
        put(query, "to", to);
        put(query, "cursor", cursor);
        if (size != null) {
            query.put("size", size);
        }
        return action.deliveries(query);
    }

    public JsonNode resend(String deliveryId) {
        roleChecker.require(Permission.NOTIFY_CHANNEL_MANAGE);
        return action.resend(deliveryId);
    }

    static void put(Map<String, Object> query, String key, String value) {
        if (value != null && !value.isBlank()) {
            query.put(key, value.strip());
        }
    }
}
