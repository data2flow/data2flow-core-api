package net.java21.data2flow.core.live.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.dashboard.domain.DashboardErrorCode;
import net.java21.data2flow.core.live.domain.LiveTopic;
import net.java21.data2flow.core.live.domain.Subscription;
import net.java21.data2flow.core.live.dto.LiveDtos.Ready;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * 실시간 구독 열기(API-DSH-20 {@code GET /core/stream/live?topics=…}, DSH-05.01).
 *
 * <ul>
 *   <li>토픽 1~{@value LiveTopic#MAX_TOPICS}개. 형식이 틀리거나 넘치면 400 INVALID_REQUEST(errors[].field = topics)</li>
 *   <li>모든 토픽이 권한 부족이면 403 PERMISSION_DENIED. 일부만이면 그 토픽만 이벤트가 없다({@code ready.rejected})</li>
 *   <li>{@code ingest-messages}의 sourceId·deviceId가 다른 조직·범위 밖이면 404 SOURCE_NOT_FOUND·RESOURCE_NOT_FOUND(TC-DSH-022)</li>
 *   <li>연결은 {@code data2flow.core.live.emitter-timeout}(15분) 뒤 서버가 닫는다. BFF·브라우저가 새 Access 토큰으로 다시 연결해
 *       gateway가 토큰을 다시 확인한다(auth.md §8)</li>
 *   <li>{@code Last-Event-ID}는 받지만 M2에는 재생할 이벤트가 없다(알림은 M5). 홈 토픽은 연결 직후 전체 요약을 보낸다</li>
 * </ul>
 */
@Service
public class LiveStreamService {

    private final RoleChecker roleChecker;
    private final LiveSubscriptions subscriptions;
    private final LiveHub hub;
    private final Clock clock;
    private final CoreProperties.Live settings;

    public LiveStreamService(RoleChecker roleChecker, LiveSubscriptions subscriptions, LiveHub hub, Clock clock,
                             CoreProperties properties) {
        this.roleChecker = roleChecker;
        this.subscriptions = subscriptions;
        this.hub = hub;
        this.clock = clock;
        this.settings = properties.live();
    }

    public SseEmitter open(String topics, String sessionId, String lastEventId) {
        AccessGrant grant = roleChecker.grant();
        CurrentUser user = roleChecker.currentUser();
        LiveTopic.Parsed parsed = LiveTopic.parse(topics);
        if (parsed.count() == 0 || parsed.count() > LiveTopic.MAX_TOPICS || !parsed.invalid().isEmpty()) {
            String detail = parsed.count() > LiveTopic.MAX_TOPICS ? "1~" + LiveTopic.MAX_TOPICS
                    : parsed.invalid().isEmpty() ? "1~" + LiveTopic.MAX_TOPICS : String.join(",", parsed.invalid());
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("topics", "INVALID", detail)));
        }
        long orgId = user.organizationId();
        subscriptions.firstMissingMessageTarget(orgId, grant, parsed.valid()).ifPresent(m -> {
            throw new BusinessException(m.sourceId() != null && !subscriptions.sourceExists(orgId, m.sourceId())
                    ? DashboardErrorCode.SOURCE_NOT_FOUND : CommonErrorCode.RESOURCE_NOT_FOUND);
        });
        Subscription subscription = subscriptions.resolve(orgId, grant, parsed.valid());
        Permission missing = subscription.accepted().isEmpty() ? firstMissingPermission(parsed.valid(), grant) : null;
        if (missing != null) {
            roleChecker.require(missing);
        }
        SseEmitter emitter = new SseEmitter(settings.emitterTimeout().toMillis());
        LiveConnection connection = new LiveConnection(orgId, user.userId(), parseSession(sessionId), parsed.valid(), subscription,
                emitter, settings.queueCapacity(), hub::remove);
        connection.send("ready", hub.write(new Ready(subscription.accepted(), subscription.rejected(), clock.instant())));
        hub.register(connection);
        hub.sendInitialHome(connection);
        return emitter;
    }

    /** 모두 거부됐을 때 403을 낼 권한(감사 ACCESS_DENIED에 남는다). 권한은 있고 범위 밖일 뿐이면 null: 연결은 열고 이벤트만 없다(TC-DSH-051) */
    static Permission firstMissingPermission(List<LiveTopic> topics, AccessGrant grant) {
        for (LiveTopic t : topics) {
            Permission needed = switch (t) {
                case LiveTopic.Ingest i -> Permission.INGEST_READ;
                case LiveTopic.IngestMessages m -> Permission.INGEST_READ;
                case LiveTopic.Telemetry tm -> Permission.TS_READ;
                default -> Permission.DASHBOARD_READ;
            };
            if (!grant.has(needed)) {
                return needed;
            }
        }
        return null;
    }

    static UUID parseSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(sessionId.strip());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
