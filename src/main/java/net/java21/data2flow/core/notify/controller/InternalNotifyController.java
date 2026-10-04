package net.java21.data2flow.core.notify.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.common.ItemsResponse;
import net.java21.data2flow.core.notify.service.MessengerService;
import net.java21.data2flow.core.notify.service.NotifyInternalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * action notification 패키지용 core 내부 API(RUL-api §5.1 API-RUL-40~50, OPS-api API-OPS-35, ADR-049). 토큰 없음·ClusterIP,
 * X-CALLER-SERVICE(ADR-021). 조직은 쿼리 {@code organizationId} → 호출자 X-ORG-ID → 배포 조직 순서(배포 범위 밖이면 404).
 */
@RestController
public class InternalNotifyController {

    private final NotifyInternalService service;
    private final MessengerService messenger;
    private final InternalOrganizations organizations;

    public InternalNotifyController(NotifyInternalService service, MessengerService messenger, InternalOrganizations organizations) {
        this.service = service;
        this.messenger = messenger;
        this.organizations = organizations;
    }

    /** API-RUL-40 */
    @GetMapping("/internal/core/notification-policies/{policy-id}")
    public ApiResponse<Map<String, Object>> policy(@PathVariable("policy-id") long id) {
        return ApiResponse.success(service.policy(id));
    }

    /** API-RUL-41 */
    @GetMapping("/internal/core/alarms/{alarm-id}")
    public ApiResponse<JsonNode> alarm(@PathVariable("alarm-id") long alarmId) {
        return ApiResponse.success(service.alarm(alarmId));
    }

    /** API-RUL-42 */
    @GetMapping("/internal/core/organizations/{organization-id}/notify-recipients")
    public ItemsResponse<Map<String, Object>> recipients(@PathVariable("organization-id") long organizationId,
                                                         @RequestParam(required = false) List<String> userIds,
                                                         @RequestParam(required = false) List<String> roles) {
        List<Long> ids = new ArrayList<>();
        if (userIds != null) {
            for (String raw : userIds) {
                for (String part : raw.split(",")) {
                    if (!part.isBlank()) {
                        ids.add(Long.parseLong(part.strip()));
                    }
                }
            }
        }
        List<String> roleList = new ArrayList<>();
        if (roles != null) {
            for (String raw : roles) {
                for (String part : raw.split(",")) {
                    if (!part.isBlank()) {
                        roleList.add(part.strip());
                    }
                }
            }
        }
        return ItemsResponse.of(service.recipients(organizations.resolve(organizationId), ids, roleList));
    }

    /** API-RUL-43 */
    @GetMapping("/internal/core/silences")
    public ItemsResponse<Map<String, Object>> silences(@RequestParam(required = false) Long organizationId,
                                                       @RequestParam(required = false, defaultValue = "true") boolean active) {
        return ItemsResponse.of(service.silences(organizations.resolve(organizationId), active));
    }

    /** API-RUL-44 */
    @GetMapping("/internal/core/on-call")
    public ApiResponse<Map<String, Object>> onCall(@RequestParam(required = false) Long organizationId) {
        return ApiResponse.success(service.onCall(organizations.resolve(organizationId)));
    }

    /** API-RUL-45 */
    @GetMapping("/internal/core/notification-templates/resolve")
    public ApiResponse<Map<String, Object>> template(@RequestParam(required = false) Long organizationId, @RequestParam String key,
                                                     @RequestParam(required = false) String channel, @RequestParam(required = false) String locale) {
        return ApiResponse.success(service.template(organizations.resolve(organizationId), key, channel, locale));
    }

    /** API-RUL-46 */
    @GetMapping("/internal/core/messenger-links")
    public ApiResponse<Map<String, Object>> link(@RequestParam String channel, @RequestParam String externalUserId) {
        return ApiResponse.success(service.messengerLink(channel, externalUserId));
    }

    /** API-RUL-47 */
    @PostMapping("/internal/core/messenger-links/confirm")
    public ApiResponse<Map<String, Object>> confirm(@RequestBody JsonNode body) {
        return ApiResponse.success(messenger.confirmLink(body));
    }

    /** API-RUL-48 메신저 [확인](X-USER-ID·X-ORG-ID = 연결된 사용자) */
    @PostMapping("/internal/core/alarms/{alarm-id}/ack")
    public ApiResponse<Map<String, Object>> ack(@PathVariable("alarm-id") long alarmId, @RequestHeader("X-USER-ID") long userId,
                                                @RequestHeader("X-ORG-ID") long orgId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(messenger.ack(orgId, userId, alarmId));
    }

    /** API-RUL-49 메신저 [30분 무음] */
    @PostMapping("/internal/core/alarms/{alarm-id}/mute")
    public ApiResponse<Map<String, Object>> mute(@PathVariable("alarm-id") long alarmId, @RequestHeader("X-USER-ID") long userId,
                                                 @RequestHeader("X-ORG-ID") long orgId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(messenger.mute(orgId, userId, alarmId, body));
    }

    /** API-RUL-50 에스컬레이션 기록 → 202 */
    @PostMapping("/internal/core/alarms/{alarm-id}/events")
    public ResponseEntity<Void> timeline(@PathVariable("alarm-id") long alarmId, @RequestBody JsonNode body) {
        service.timeline(alarmId, body);
        return ResponseEntity.accepted().build();
    }

    /** API-OPS-35 */
    @GetMapping("/internal/core/notification-channels")
    public ItemsResponse<Map<String, Object>> channels(@RequestParam(required = false) Long organizationId,
                                                       @RequestParam(required = false) String type) {
        return ItemsResponse.of(service.channels(organizations.resolve(organizationId), type));
    }

    @GetMapping("/internal/core/notification-channels/{notification-channel-id}")
    public ApiResponse<Map<String, Object>> channel(@PathVariable("notification-channel-id") long id,
                                                    @RequestParam(required = false) Long organizationId) {
        return ApiResponse.success(service.channel(organizations.resolve(organizationId), id));
    }
}
