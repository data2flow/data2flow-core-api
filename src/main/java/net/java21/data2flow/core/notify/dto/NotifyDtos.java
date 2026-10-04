package net.java21.data2flow.core.notify.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** 알림 정책·템플릿·무음·당직·수신 설정·메신저·채널 API(API-RUL-21~31, API-OPS-30~34) 모양. ID는 문자열 */
public final class NotifyDtos {

    private NotifyDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserRef(String userId, String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Recipient(String type, String id) {
    }

    public record Step(int stepNo, int waitMinutes, List<Recipient> recipients) {
    }

    /** API-RUL-21 정책 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Policy(String notificationPolicyId, String name, String minSeverity, String spaceId, boolean includeChildren,
                         List<String> ruleIds, JsonNode timeWindow, List<Recipient> recipients, List<String> channels, JsonNode templates,
                         int renotifyMinutes, int aggregateWindowSec, boolean notifyOnClear, List<Step> steps, int version,
                         Instant createdAt, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PolicySummary(String notificationPolicyId, String name, String minSeverity, String spaceId, List<String> channels,
                                int recipientCount, int stepCount, Instant updatedAt) {
    }

    /** API-RUL-22 템플릿 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Template(String notificationTemplateId, String key, String channel, String locale, String subject, String body,
                           boolean builtin, boolean customized, int version, Instant updatedAt) {
    }

    public record TemplateWarning(String code, String name) {
    }

    public record TemplateSaved(Template template, List<TemplateWarning> warnings) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Preview(String subject, String body) {
    }

    public record Variable(String name, String description) {
    }

    /** API-RUL-25 무음 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SilenceTarget(String type, String id, String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Silence(String silenceId, String kind, SilenceTarget target, Instant startsAt, Instant endsAt, JsonNode recurrence,
                          String reason, boolean active, UserRef createdBy, Instant createdAt) {
    }

    /** API-RUL-26 당직 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Shift(int dayOfWeek, String from, String to, String userId, String userName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Override(String overrideId, Instant startsAt, Instant endsAt, UserRef originalUser, UserRef substituteUser) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnCall(String scheduleId, String name, String timezone, List<Shift> shifts, List<Override> overrides, int version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnCallCurrent(String userId, String name, Instant until, boolean substitute) {
    }

    /** API-RUL-30·OPS-06.05 사용자 수신 설정 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessengerLink(String channel, String externalUserId, Instant linkedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NotifyPreferences(List<String> channels, String minSeverity, String dndFrom, String dndTo, boolean dndAllowCritical,
                                    String locale, List<MessengerLink> links, int version) {
    }

    public record LinkStart(String code, String deepLink, Instant expiresAt) {
    }

    /** API-OPS-30 채널(비밀값은 가림) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Channel(String id, String name, String type, JsonNode config, boolean secretConfigured, String secret,
                          int rateLimitPerMin, int digestWindowSec, boolean enabled, String status, int version, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChannelTest(boolean ok, Long latencyMs, JsonNode providerResponse) {
    }
}
