package net.java21.data2flow.core.notify.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.notify.dto.NotifyDtos.LinkStart;
import net.java21.data2flow.core.notify.dto.NotifyDtos.MessengerLink;
import net.java21.data2flow.core.notify.dto.NotifyDtos.NotifyPreferences;
import net.java21.data2flow.core.notify.repository.ChannelRepository;
import net.java21.data2flow.core.notify.repository.NotifyPrefRepository;
import net.java21.data2flow.core.notify.repository.NotifyPrefRepository.PrefRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 사용자 알림 수신 설정(OPS-06.05, RUL-05.04, API-RUL-30): 받을 채널, 최소 심각도, 방해 금지 시간(CRITICAL 예외), 언어와 메신저 계정 연결.
 * 로그인한 본인만(다른 사람 것은 바꿀 수 없음). 설정을 바꾸면 설정 변경 NOTIFY_PREFERENCE(id = 사용자 ID)를 내서 action이 다시 읽는다.
 * 메신저 연결은 10분 유효 일회용 코드를 만들고(원문은 응답에만, 저장은 해시), 사용자가 봇에 {@code /start {code}}를 보내면 action이 core 내부
 * API(API-RUL-45)로 연결을 확정한다(ADR-033).
 */
@Service
public class PreferenceService {

    static final Set<String> SEVERITIES = Set.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO");
    static final Set<String> LOCALES = Set.of("ko", "en", "ja", "zh");
    static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final NotifyPrefRepository prefs;
    private final ChannelRepository channels;
    private final NotificationActionClient action;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final JsonMapper json;
    private final Clock clock;

    public PreferenceService(NotifyPrefRepository prefs, ChannelRepository channels, NotificationActionClient action,
                             CoreEventPublisher publisher, RoleChecker roleChecker, JsonMapper json, Clock clock) {
        this.action = action;
        this.prefs = prefs;
        this.channels = channels;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotifyPreferences mine() {
        CurrentUser user = roleChecker.currentUser();
        return view(user.organizationId(), user.userId());
    }

    /** 저장 {channels?, minSeverity?, dndFrom?, dndTo?, dndAllowCritical?, locale?, baseVersion?} — 보내지 않은 값은 그대로 */
    @Transactional
    public NotifyPreferences update(JsonNode body) {
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (body == null || !body.isObject()) {
            throw invalid("body", "NotNull");
        }
        NotifyPreferences current = view(orgId, user.userId());
        List<String> chs = current.channels();
        if (body.has("channels")) {
            chs = new ArrayList<>();
            for (JsonNode c : body.path("channels").values()) {
                String ch = c.asString("").strip().toUpperCase(Locale.ROOT);
                if (!ch.matches("[A-Z][A-Z0-9_]{1,19}")) {
                    throw invalid("channels", "Pattern");
                }
                if (!chs.contains(ch)) {
                    chs.add(ch);
                }
            }
        }
        String min = body.has("minSeverity") ? body.path("minSeverity").asString("").toUpperCase(Locale.ROOT) : current.minSeverity();
        if (!SEVERITIES.contains(min)) {
            throw invalid("minSeverity", "Pattern");
        }
        String from = body.has("dndFrom") ? textOrNull(body.get("dndFrom")) : current.dndFrom();
        String to = body.has("dndTo") ? textOrNull(body.get("dndTo")) : current.dndTo();
        LocalTime dndFrom;
        LocalTime dndTo;
        try {
            dndFrom = from == null ? null : net.java21.data2flow.core.notify.domain.TimeWindow.time(from);
            dndTo = to == null ? null : net.java21.data2flow.core.notify.domain.TimeWindow.time(to);
        } catch (IllegalArgumentException ex) {
            throw invalid("dndFrom", "Pattern");
        }
        if ((dndFrom == null) != (dndTo == null)) {
            throw invalid(dndFrom == null ? "dndFrom" : "dndTo", "NotNull");
        }
        boolean critical = body.has("dndAllowCritical") ? body.get("dndAllowCritical").asBoolean(true) : current.dndAllowCritical();
        String locale = body.has("locale") ? body.path("locale").asString("").toLowerCase(Locale.ROOT) : current.locale();
        if (!LOCALES.contains(locale)) {
            throw invalid("locale", "Pattern");
        }
        Integer base = body.hasNonNull("baseVersion") ? body.get("baseVersion").asInt() : null;
        if (base != null && base != current.version()) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        if (prefs.upsertPref(orgId, user.userId(), base, chs, min, dndFrom, dndTo, critical, locale, clock.instant()) == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        publisher.configChanged(ConfigChangedMessage.EntityType.NOTIFY_PREFERENCE, user.userId(), current.version() + 1L, orgId);
        return view(orgId, user.userId());
    }

    /** 메신저 연결 시작 {channel} → {code, deepLink, expiresAt} */
    @Transactional
    public LinkStart startLink(JsonNode body) {
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String channel = body == null ? "" : body.path("channel").asString("").strip().toUpperCase(Locale.ROOT);
        var configured = channels.list(orgId).stream().filter(c -> c.type().equals(channel) && c.enabled()).findFirst();
        if (configured.isEmpty()) {
            throw new BusinessException(net.java21.data2flow.core.alarm.domain.AlarmErrorCode.CHANNEL_NOT_CONFIGURED,
                    List.of(new FieldErrorDetail("channel", "NOT_CONFIGURED", channel)), channel);
        }
        String code = code();
        Instant now = clock.instant();
        Instant expires = now.plus(CODE_TTL);
        prefs.insertCode(orgId, user.userId(), channel, Tokens.sha256Hex(code), expires, now);
        String deepLink = null;
        try {
            java.util.Map<String, Object> req = new java.util.LinkedHashMap<>();
            req.put("channel", channel);
            req.put("organizationId", orgId);
            req.put("userId", Long.toString(user.userId()));
            req.put("code", code);
            req.put("expiresAt", expires.toString());
            JsonNode r = action.link(req);
            deepLink = r == null ? null : r.path("deepLink").asString(null);
        } catch (BusinessException | net.java21.data2flow.core.common.RelayedErrorException ex) {
            // action이 응답하지 않으면 채널 설정의 봇 이름으로 만든다
        }
        if (deepLink == null) {
            String bot = json.readTree(configured.get().config()).path("botUsername").asString(null);
            deepLink = "TELEGRAM".equals(channel) && bot != null && !bot.isBlank() ? "https://t.me/" + bot + "?start=" + code : null;
        }
        return new LinkStart(code, deepLink, expires);
    }

    @Transactional
    public void unlink(String channel) {
        CurrentUser user = roleChecker.currentUser();
        String ch = channel == null ? "" : channel.strip().toUpperCase(Locale.ROOT);
        if (prefs.deleteLink(user.organizationId(), user.userId(), ch) == 0) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        publisher.configChanged(ConfigChangedMessage.EntityType.NOTIFY_PREFERENCE, user.userId(), 0, user.organizationId());
    }

    public NotifyPreferences view(long orgId, long userId) {
        PrefRow p = prefs.findPref(orgId, userId).orElse(null);
        List<MessengerLink> links = prefs.listLinks(orgId, userId).stream()
                .map(l -> new MessengerLink(l.channel(), mask(l.externalUserId()), l.linkedAt())).toList();
        if (p == null) {
            return new NotifyPreferences(List.of("WEB", "TELEGRAM"), "INFO", null, null, true, "ko", links, 0);
        }
        return new NotifyPreferences(p.channels(), p.minSeverity(), p.dndFrom() == null ? null : p.dndFrom().toString(),
                p.dndTo() == null ? null : p.dndTo().toString(), p.dndAllowCritical(), p.locale(), links, p.version());
    }

    /** 외부 계정 ID는 끝 4자리만 보인다 */
    static String mask(String externalUserId) {
        if (externalUserId == null || externalUserId.length() <= 4) {
            return "****";
        }
        return "****" + externalUserId.substring(externalUserId.length() - 4);
    }

    static String code() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    static String textOrNull(JsonNode v) {
        return v == null || v.isNull() || v.asString("").isBlank() ? null : v.asString("").strip();
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
