package net.java21.data2flow.core.notify.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.HandleResult;
import net.java21.data2flow.core.alarm.service.AlarmHandlingService;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Silence;
import net.java21.data2flow.core.notify.repository.NotifyPrefRepository;
import net.java21.data2flow.core.notify.repository.NotifyPrefRepository.CodeRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 메신저 응답 처리(RUL-05.02, BR-RUL-18, API-RUL-47~49). 외부 콜백은 BFF({@code /hooks/messenger/{channel}})가 받아 action에 넘기고, action의
 * 채널 SPI가 검증·해석한 뒤 연결된 사용자({@code X-USER-ID}·{@code X-ORG-ID})로 core 내부 API를 부른다(ADR-049).
 * <ul>
 *   <li>연결 코드 확정: 10분 안의 일회용 코드면 외부 계정을 사용자에 잇는다(외부 계정 1 ↔ 사용자 1). 없는 코드 404, 쓴·만료 코드 409</li>
 *   <li>확인: 그 사용자의 권한(ALARM_HANDLE + 공간 범위)으로. 이미 확인했으면 {@code alreadyAcked}</li>
 *   <li>무음: 같은 권한으로 그 알람만 N분(기본 30) 일회 무음</li>
 * </ul>
 */
@Service
public class MessengerService {

    private final NotifyPrefRepository prefs;
    private final AlarmHandlingService handling;
    private final SilenceService silences;
    private final InternalOrganizations organizations;
    private final CoreEventPublisher publisher;
    private final JsonMapper json;
    private final Clock clock;

    public MessengerService(NotifyPrefRepository prefs, AlarmHandlingService handling, SilenceService silences,
                            InternalOrganizations organizations, CoreEventPublisher publisher, JsonMapper json, Clock clock) {
        this.prefs = prefs;
        this.handling = handling;
        this.silences = silences;
        this.organizations = organizations;
        this.publisher = publisher;
        this.json = json;
        this.clock = clock;
    }

    /** API-RUL-47 {channel, code, externalUserId} → {userId, organizationId} */
    @Transactional
    public Map<String, Object> confirmLink(JsonNode body) {
        String channel = text(body, "channel").toUpperCase(Locale.ROOT);
        String code = text(body, "code").toUpperCase(Locale.ROOT);
        String external = text(body, "externalUserId");
        CodeRow row = prefs.lockCodeAnyOrganization(Tokens.sha256Hex(code)).filter(c -> c.channel().equals(channel))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        organizations.resolve(row.organizationId());
        Instant now = clock.instant();
        if (row.usedAt() != null || !row.expiresAt().isAfter(now)) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        prefs.updateCodeUsed(row.organizationId(), row.id(), now);
        prefs.upsertLink(row.organizationId(), row.userId(), channel, external, now);
        publisher.configChanged(ConfigChangedMessage.EntityType.NOTIFY_PREFERENCE, row.userId(), 0, row.organizationId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", Long.toString(row.userId()));
        out.put("organizationId", row.organizationId());
        return out;
    }

    /** API-RUL-48 메신저 [확인] → {ok, alreadyAcked} */
    @Transactional
    public Map<String, Object> ack(long orgId, long userId, long alarmId) {
        organizations.resolve(orgId);
        HandleResult r = handling.ackAs(orgId, userId, alarmId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("alreadyAcked", Boolean.TRUE.equals(r.alreadyAcked()));
        out.put("status", r.status());
        return out;
    }

    /** API-RUL-49 메신저 [30분 무음] {minutes(1~1440, 기본 30)} → {silenceId} */
    @Transactional
    public Map<String, Object> mute(long orgId, long userId, long alarmId, JsonNode body) {
        organizations.resolve(orgId);
        int minutes = body == null ? 30 : body.path("minutes").asInt(30);
        if (minutes < 1 || minutes > 1440) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("minutes", "Range", null)));
        }
        handling.requireHandleAs(orgId, userId, alarmId);
        ObjectNode req = json.createObjectNode();
        req.put("kind", "ONE_TIME");
        req.putObject("target").put("type", "ALARM").put("id", Long.toString(alarmId));
        Instant now = clock.instant();
        req.put("startsAt", now.toString());
        req.put("endsAt", now.plus(Duration.ofMinutes(minutes)).toString());
        req.put("reason", "메신저 " + minutes + "분 무음");
        Silence s = silences.create(orgId, userId, req, body == null ? "MESSENGER" : body.path("via").asString("MESSENGER"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("silenceId", s.silenceId());
        out.put("endsAt", s.endsAt());
        return out;
    }

    static String text(JsonNode body, String field) {
        String v = body == null ? "" : body.path(field).asString("").strip();
        if (v.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "NotBlank", null)));
        }
        return v;
    }
}
