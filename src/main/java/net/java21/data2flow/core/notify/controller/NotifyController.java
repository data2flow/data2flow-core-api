package net.java21.data2flow.core.notify.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiHeader;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ErrorMessages;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.alarm.domain.AlarmErrorCode;
import net.java21.data2flow.core.common.ItemsResponse;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Channel;
import net.java21.data2flow.core.notify.dto.NotifyDtos.ChannelTest;
import net.java21.data2flow.core.notify.dto.NotifyDtos.LinkStart;
import net.java21.data2flow.core.notify.dto.NotifyDtos.NotifyPreferences;
import net.java21.data2flow.core.notify.dto.NotifyDtos.OnCall;
import net.java21.data2flow.core.notify.dto.NotifyDtos.OnCallCurrent;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Policy;
import net.java21.data2flow.core.notify.dto.NotifyDtos.PolicySummary;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Preview;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Silence;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Template;
import net.java21.data2flow.core.notify.dto.NotifyDtos.TemplateSaved;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Variable;
import net.java21.data2flow.core.notify.service.ChannelService;
import net.java21.data2flow.core.notify.service.DeliveryService;
import net.java21.data2flow.core.notify.service.OnCallService;
import net.java21.data2flow.core.notify.service.PolicyService;
import net.java21.data2flow.core.notify.service.PreferenceService;
import net.java21.data2flow.core.notify.service.SilenceService;
import net.java21.data2flow.core.notify.service.TemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/**
 * 알림 정책·템플릿·무음·당직·수신 설정·메신저 연결(API-RUL-21~30)과 알림 채널·발송 이력(API-OPS-30~34). 외부 {@code /api/v1/core/**}
 */
@RestController
public class NotifyController {

    private final PolicyService policies;
    private final TemplateService templates;
    private final SilenceService silences;
    private final OnCallService onCall;
    private final PreferenceService preferences;
    private final ChannelService channels;
    private final DeliveryService deliveries;
    private final ErrorMessages messages;

    public NotifyController(PolicyService policies, TemplateService templates, SilenceService silences, OnCallService onCall,
                            PreferenceService preferences, ChannelService channels, DeliveryService deliveries, ErrorMessages messages) {
        this.policies = policies;
        this.templates = templates;
        this.silences = silences;
        this.onCall = onCall;
        this.preferences = preferences;
        this.channels = channels;
        this.deliveries = deliveries;
        this.messages = messages;
    }

    // ------------------------------------------------------------------ 정책(API-RUL-21)

    @GetMapping("/core/notification-policies")
    public ListApiResponse<PolicySummary> policies(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return policies.list(page, size);
    }

    @PostMapping("/core/notification-policies")
    @Idempotent
    public ResponseEntity<ApiResponse<Policy>> createPolicy(@RequestBody JsonNode body) {
        Policy p = policies.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/notification-policies/" + p.notificationPolicyId())).body(ApiResponse.success(p));
    }

    @GetMapping("/core/notification-policies/{notification-policy-id}")
    public ApiResponse<Policy> policy(@PathVariable("notification-policy-id") long id) {
        return ApiResponse.success(policies.get(id));
    }

    @PutMapping("/core/notification-policies/{notification-policy-id}")
    public ApiResponse<Policy> updatePolicy(@PathVariable("notification-policy-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(policies.update(id, body));
    }

    @DeleteMapping("/core/notification-policies/{notification-policy-id}")
    public ResponseEntity<Void> deletePolicy(@PathVariable("notification-policy-id") long id) {
        policies.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ 템플릿(API-RUL-22)

    @GetMapping("/core/notification-templates")
    public ItemsResponse<Template> templates(@RequestParam(required = false) String channel, @RequestParam(required = false) String locale) {
        return ItemsResponse.of(templates.list(channel, locale));
    }

    @GetMapping("/core/notification-templates/variables")
    public ItemsResponse<Variable> variables() {
        return ItemsResponse.of(templates.variables());
    }

    /** 알 수 없는 변수가 있으면 200 + resultCode TEMPLATE_VARIABLE_UNKNOWN(isSuccessful=true, TC-RUL-092) */
    @PutMapping("/core/notification-templates/{notification-template-id:\\d+}")
    public ApiResponse<TemplateSaved> updateTemplate(@PathVariable("notification-template-id") long id, @RequestBody JsonNode body) {
        TemplateSaved saved = templates.update(id, body);
        if (saved.warnings().isEmpty()) {
            return ApiResponse.success(saved);
        }
        String names = String.join(", ", saved.warnings().stream().map(w -> w.name()).toList());
        return new ApiResponse<>(new ApiHeader(true, AlarmErrorCode.TEMPLATE_VARIABLE_UNKNOWN.code(),
                messages.resolve(AlarmErrorCode.TEMPLATE_VARIABLE_UNKNOWN, names)), saved);
    }

    @PostMapping("/core/notification-templates/{notification-template-id:\\d+}/preview")
    public ApiResponse<Preview> preview(@PathVariable("notification-template-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(templates.preview(id, body));
    }

    @PostMapping("/core/notification-templates/{notification-template-id:\\d+}/reset")
    public ApiResponse<Template> reset(@PathVariable("notification-template-id") long id) {
        return ApiResponse.success(templates.reset(id));
    }

    // ------------------------------------------------------------------ 무음(API-RUL-25)

    @GetMapping("/core/silences")
    public ListApiResponse<Silence> silences(@RequestParam(required = false) Boolean includeEnded, @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return silences.list(includeEnded, page, size);
    }

    @PostMapping("/core/silences")
    @Idempotent
    public ResponseEntity<ApiResponse<Silence>> createSilence(@RequestBody JsonNode body) {
        Silence s = silences.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/silences/" + s.silenceId())).body(ApiResponse.success(s));
    }

    @DeleteMapping("/core/silences/{silence-id}")
    public ResponseEntity<Void> deleteSilence(@PathVariable("silence-id") long id) {
        silences.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ 당직(API-RUL-26)

    @GetMapping("/core/on-call")
    public ApiResponse<OnCall> onCall() {
        return ApiResponse.success(onCall.get());
    }

    @PutMapping("/core/on-call")
    public ApiResponse<OnCall> putOnCall(@RequestBody JsonNode body) {
        return ApiResponse.success(onCall.put(body));
    }

    @PostMapping("/core/on-call/overrides")
    public ResponseEntity<ApiResponse<OnCall>> addOverride(@RequestBody JsonNode body) {
        return ResponseEntity.status(201).body(ApiResponse.success(onCall.addOverride(body)));
    }

    @DeleteMapping("/core/on-call/overrides/{override-id}")
    public ResponseEntity<Void> deleteOverride(@PathVariable("override-id") long id) {
        onCall.deleteOverride(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/core/on-call/current")
    public ApiResponse<OnCallCurrent> current() {
        return ApiResponse.success(onCall.current());
    }

    // ------------------------------------------------------------------ 내 수신 설정·메신저 연결(API-RUL-30, OPS-06.05)

    @GetMapping("/core/accounts/me/notify-preferences")
    public ApiResponse<NotifyPreferences> myPreferences() {
        return ApiResponse.success(preferences.mine());
    }

    @PutMapping("/core/accounts/me/notify-preferences")
    public ApiResponse<NotifyPreferences> updatePreferences(@RequestBody JsonNode body) {
        return ApiResponse.success(preferences.update(body));
    }

    @PostMapping("/core/accounts/me/messenger-links/start")
    public ApiResponse<LinkStart> startLink(@RequestBody JsonNode body) {
        return ApiResponse.success(preferences.startLink(body));
    }

    @DeleteMapping("/core/accounts/me/messenger-links/{channel}")
    public ResponseEntity<Void> unlink(@PathVariable("channel") String channel) {
        preferences.unlink(channel);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ 알림 채널(API-OPS-30·31·34)

    @GetMapping("/core/notification-channel-types")
    public ItemsResponse<JsonNode> channelTypes() {
        return ItemsResponse.of(channels.types());
    }

    @GetMapping("/core/notification-channels")
    public ItemsResponse<Channel> channels() {
        return ItemsResponse.of(channels.list());
    }

    @PostMapping("/core/notification-channels")
    @Idempotent
    public ResponseEntity<ApiResponse<Channel>> createChannel(@RequestBody JsonNode body) {
        Channel c = channels.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/notification-channels/" + c.id())).body(ApiResponse.success(c));
    }

    @PostMapping("/core/notification-channels/test-draft")
    public ApiResponse<ChannelTest> testDraft(@RequestBody JsonNode body) {
        return ApiResponse.success(channels.testDraft(body));
    }

    @GetMapping("/core/notification-channels/{notification-channel-id:\\d+}")
    public ApiResponse<Channel> channel(@PathVariable("notification-channel-id") long id) {
        return ApiResponse.success(channels.get(id));
    }

    @PutMapping("/core/notification-channels/{notification-channel-id:\\d+}")
    public ApiResponse<Channel> updateChannel(@PathVariable("notification-channel-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(channels.update(id, body));
    }

    @DeleteMapping("/core/notification-channels/{notification-channel-id:\\d+}")
    public ResponseEntity<Void> deleteChannel(@PathVariable("notification-channel-id") long id) {
        channels.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/core/notification-channels/{notification-channel-id:\\d+}/test")
    public ApiResponse<ChannelTest> testChannel(@PathVariable("notification-channel-id") long id) {
        return ApiResponse.success(channels.test(id));
    }

    // ------------------------------------------------------------------ 발송 이력(API-RUL-27 = API-OPS-32)·재발송(API-OPS-33)

    @GetMapping("/core/notification-deliveries")
    public JsonNode deliveries(@RequestParam(required = false) String alarmId, @RequestParam(required = false) String channelId,
                               @RequestParam(required = false) String status, @RequestParam(required = false) String from,
                               @RequestParam(required = false) String to, @RequestParam(required = false) String cursor,
                               @RequestParam(required = false) Integer size) {
        return deliveries.list(alarmId, channelId, status, from, to, cursor, size);
    }

    @PostMapping("/core/notification-deliveries/{notification-delivery-id}/resend")
    public ApiResponse<JsonNode> resend(@PathVariable("notification-delivery-id") String id) {
        return ApiResponse.success(deliveries.resend(id));
    }
}
