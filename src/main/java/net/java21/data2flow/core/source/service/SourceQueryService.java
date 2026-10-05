package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.source.domain.ConnectionStates;
import net.java21.data2flow.core.source.domain.ConnectionStates.Representative;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import net.java21.data2flow.core.source.domain.SourceModels;
import net.java21.data2flow.core.source.domain.SourceModels.DataSource;
import net.java21.data2flow.core.source.domain.SourceModels.RuntimeRow;
import net.java21.data2flow.core.source.domain.SourceModels.SecretMeta;
import net.java21.data2flow.core.source.domain.SourceModels.StatSummary;
import net.java21.data2flow.core.source.domain.SourceModels.StateRow;
import net.java21.data2flow.core.source.dto.SourceDtos.IgnoreEntryResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.RuntimeInstance;
import net.java21.data2flow.core.source.dto.SourceDtos.RuntimeResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.SourceDetailResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.SourceSummaryResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.StatPoint;
import net.java21.data2flow.core.source.dto.SourceDtos.StateDetail;
import net.java21.data2flow.core.source.dto.SourceDtos.TopicDto;
import net.java21.data2flow.core.source.dto.SourceDtos.UsageResponse;
import net.java21.data2flow.core.source.repository.DataSourceRepository;
import net.java21.data2flow.core.source.repository.SourceHealthRepository;
import net.java21.data2flow.core.source.repository.SourceReferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 데이터 소스 조회(SRC_READ, OPERATOR 이상): 목록·상세(API-DSC-01·03), 인스턴스별 상태(API-DSC-14), 지표(API-DSC-09),
 * 사용처(API-DSC-11), 자동 등록 무시 목록(API-DSC-13). 소스는 공간에 속하지 않는 조직 단위 자원이라 공간 범위 필터는 없다.
 */
@Service
public class SourceQueryService {

    static final Duration MAX_STATS_RANGE = Duration.ofDays(7);
    private static final Set<String> STATES = Set.of(ConnectionStates.CONNECTED, ConnectionStates.CONNECTING,
            ConnectionStates.DISCONNECTED, ConnectionStates.ERROR, ConnectionStates.DISABLED);
    private static final List<String> DEFAULT_LIFECYCLES = List.of(SourceModels.DRAFT, SourceModels.ACTIVE, SourceModels.PAUSED);

    private final DataSourceRepository sources;
    private final SourceHealthRepository health;
    private final SourceReferenceRepository references;
    private final RoleChecker roleChecker;
    private final Clock clock;
    private final String webhookBaseUrl;

    public SourceQueryService(DataSourceRepository sources, SourceHealthRepository health, SourceReferenceRepository references,
                              RoleChecker roleChecker, Clock clock,
                              @org.springframework.beans.factory.annotation.Value("${data2flow.core.source.webhook-base-url:https://data2flow-hook.java21.net}")
                              String webhookBaseUrl) {
        this.sources = sources;
        this.health = health;
        this.references = references;
        this.roleChecker = roleChecker;
        this.clock = clock;
        this.webhookBaseUrl = webhookBaseUrl.endsWith("/") ? webhookBaseUrl.substring(0, webhookBaseUrl.length() - 1) : webhookBaseUrl;
    }

    /** Webhook 수신 주소(DSC-01.03, API-DSC-54): {@code {base}/ingest/webhook/{sourceKey}}. Webhook이 아니면 null */
    public String webhookUrl(DataSource s) {
        if (!net.java21.data2flow.contracts.message.SourceTypes.WEBHOOK.equals(s.type()) || s.connection() == null
                || !s.connection().path("sourceKey").isString()) {
            return null;
        }
        return webhookBaseUrl + "/ingest/webhook/" + s.connection().get("sourceKey").asString();
    }

    /** API-DSC-01 */
    @Transactional(readOnly = true)
    public ListApiResponse<SourceSummaryResponse> list(String q, List<String> types, List<String> lifecycles, String state, Integer page,
                                                       Integer size) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        List<String> lc = split(lifecycles);
        for (String l : lc) {
            if (!SourceModels.LIFECYCLES.contains(l)) {
                throw invalid("lifecycle");
            }
        }
        String st = state == null || state.isBlank() ? null : state.strip().toUpperCase(Locale.ROOT);
        if (st != null && !STATES.contains(st)) {
            throw invalid("state");
        }
        var filter = new DataSourceRepository.Filter(orgId, PageParams.keyword(q), split(types), lc.isEmpty() ? DEFAULT_LIFECYCLES : lc, st);
        List<DataSource> rows = sources.search(filter, params.size(), params.offset());
        List<Long> ids = rows.stream().map(DataSource::id).toList();
        Map<Long, StateRow> states = health.findStatesOf(orgId, ids);
        Map<Long, List<RuntimeRow>> runtimes = health.findRuntimesOf(orgId, ids);
        Map<Long, Long> devices = sources.countDevicesOf(orgId, ids);
        Instant now = clock.instant();
        Map<Long, Map<Instant, long[]>> minutes = health.findRecentMinutes(orgId, ids, minuteFloor(now).minus(Duration.ofMinutes(59)));
        List<SourceSummaryResponse> items = new ArrayList<>();
        for (DataSource s : rows) {
            StateRow stRow = states.get(s.id());
            Representative rep = representative(s, stRow, runtimes.getOrDefault(s.id(), List.of()), now);
            StatSummary stats = summary(stRow, minutes.getOrDefault(s.id(), Map.of()), now);
            items.add(new SourceSummaryResponse(Long.toString(s.id()), s.code(), s.name(), s.type(), s.connectorKey(), s.lifecycle(),
                    rep.state(), stateDetail(rep, runtimes.getOrDefault(s.id(), List.of())), stats.lastReceivedAt(), stats.ratePerMin(),
                    stats.decodeErrorRate1h(), devices.getOrDefault(s.id(), 0L), stats.rateSeries(), s.version()));
        }
        return ListApiResponse.of(params, items, sources.count(filter));
    }

    /** API-DSC-03 */
    @Transactional(readOnly = true)
    public SourceDetailResponse get(long sourceId) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        return detail(find(orgId, sourceId));
    }

    DataSource find(long orgId, long sourceId) {
        return sources.findById(orgId, sourceId).orElseThrow(() -> new BusinessException(SourceErrorCode.SOURCE_NOT_FOUND));
    }

    /** 상세 응답(생성·수정·복제 응답에도 쓴다) */
    public SourceDetailResponse detail(DataSource s) {
        return detail(s, null);
    }

    /** 상세. {@code issued}는 생성 응답에서만(서버가 만든 비밀값 1회 표시) */
    public SourceDetailResponse detail(DataSource s, net.java21.data2flow.core.source.dto.SourceDtos.IssuedSecret issued) {
        long orgId = s.organizationId();
        Instant now = clock.instant();
        List<SecretMeta> metas = sources.findSecretMeta(orgId, s.id());
        List<RuntimeRow> runtimes = health.findRuntimes(orgId, s.id());
        StateRow state = health.findState(orgId, s.id()).orElse(null);
        Representative rep = representative(s, state, runtimes, now);
        StatSummary stats = summary(state, health.findRecentMinutes(orgId, List.of(s.id()), minuteFloor(now).minus(Duration.ofMinutes(59)))
                .getOrDefault(s.id(), Map.of()), now);
        List<String> clientIds = runtimes.stream().map(RuntimeRow::clientId).filter(Objects::nonNull).distinct().toList();
        return new SourceDetailResponse(Long.toString(s.id()), s.code(), s.name(), s.type(), s.connectorKey(), s.connectorVersion(),
                s.lifecycle(), s.connection(), s.tls(), s.payload(), s.topicTemplate(), s.isDev(),
                sources.findTopics(orgId, s.id()).stream().map(t -> new TopicDto(t.topic(), t.qos())).toList(),
                SourceSecrets.primary(s.type(), s.auth(), metas), metas.stream().map(SourceSecrets::info).toList(), s.decoderKey(),
                s.decoderConfig(), id(s.decodeScriptId()), s.unknownDevicePolicy(), id(s.defaultModelId()), id(s.defaultSpaceId()),
                s.autoregLimitPerHour(), s.noDataAlarmAfterSec(), id(s.siteId()), webhookUrl(s), s.clientIdBase(), clientIds,
                runtimes.stream().map(r -> instance(r, now)).toList(), rep.state(), stateDetail(rep, runtimes), stats.lastReceivedAt(),
                stats.ratePerMin(), stats.decodeErrorRate1h(), s.archivedAt(), s.version(), s.createdAt(), s.updatedAt(), issued);
    }

    /** API-DSC-14 */
    @Transactional(readOnly = true)
    public RuntimeResponse runtime(long sourceId) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        DataSource s = find(orgId, sourceId);
        Instant now = clock.instant();
        List<RuntimeRow> runtimes = health.findRuntimes(orgId, sourceId);
        Representative rep = representative(s, health.findState(orgId, sourceId).orElse(null), runtimes, now);
        return new RuntimeResponse(Long.toString(sourceId), rep.state(), stateDetail(rep, runtimes),
                runtimes.stream().map(r -> instance(r, now)).toList());
    }

    /**
     * API-DSC-09 지표. 기간은 최대 7일(보관 기간), 기본 최근 24시간. bucket은 1m·5m·1h(기본: 2시간 이하 1m, 2일 이하 5m, 그 밖 1h)
     */
    @Transactional(readOnly = true)
    public List<StatPoint> stats(long sourceId, Instant from, Instant to, String bucket) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        find(orgId, sourceId);
        Instant end = to == null ? minuteFloor(clock.instant()).plus(Duration.ofMinutes(1)) : to;
        Instant start = from == null ? end.minus(Duration.ofHours(24)) : from;
        if (!start.isBefore(end)) {
            throw invalid("from");
        }
        if (Duration.between(start, end).compareTo(MAX_STATS_RANGE) > 0) {
            throw invalid("to");
        }
        Duration step = bucket(bucket, Duration.between(start, end));
        return health.findStats(orgId, sourceId, start, end, step).stream()
                .map(b -> new StatPoint(b.t(), b.received(), b.accepted(), b.decodeErrors(), b.scriptErrors(), b.rejectedUnknown(),
                        b.invalid(), b.dup(), b.bytes(), b.reconnects())).toList();
    }

    static Duration bucket(String raw, Duration range) {
        if (raw == null || raw.isBlank()) {
            return range.compareTo(Duration.ofHours(2)) <= 0 ? Duration.ofMinutes(1)
                    : range.compareTo(Duration.ofDays(2)) <= 0 ? Duration.ofMinutes(5) : Duration.ofHours(1);
        }
        return switch (raw.strip()) {
            case "1m" -> Duration.ofMinutes(1);
            case "5m" -> Duration.ofMinutes(5);
            case "1h" -> Duration.ofHours(1);
            default -> throw invalid("bucket");
        };
    }

    /** API-DSC-11 사용처(DSC-07.06 일부): 기기 수, 7일 수신량. 플로우 사용처는 FLW(M3) 뒤에 채운다 */
    @Transactional(readOnly = true)
    public UsageResponse usage(long sourceId) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        find(orgId, sourceId);
        Instant from = clock.instant().truncatedTo(ChronoUnit.DAYS).minus(Duration.ofDays(6));
        return new UsageResponse(sources.countDevices(orgId, sourceId), List.of(), health.findDailyVolume(orgId, sourceId, from));
    }

    /** API-DSC-13 무시 목록 */
    @Transactional(readOnly = true)
    public ListApiResponse<IgnoreEntryResponse> ignoreList(long sourceId, Integer page, Integer size) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        find(orgId, sourceId);
        PageParams params = PageParams.of(page, size);
        List<IgnoreEntryResponse> items = references.findIgnoreEntries(orgId, sourceId, params.size(), params.offset()).stream()
                .map(m -> new IgnoreEntryResponse((String) m.get("externalId"), (String) m.get("reason"), (String) m.get("createdBy"),
                        (Instant) m.get("createdAt"))).toList();
        return ListApiResponse.of(params, items, references.countIgnoreEntries(orgId, sourceId));
    }

    // ---- 계산 도우미 ----

    /**
     * 화면 대표 상태. 저장된 상태(source_states, 보고·1분 점검으로 갱신)를 우선 쓰고, 아직 없으면 지금 계산한다.
     */
    static Representative representative(DataSource s, StateRow state, List<RuntimeRow> runtimes, Instant now) {
        Representative computed = ConnectionStates.representative(s.lifecycle(), runtimes, state == null ? null : state.activatedAt(), now);
        if (state == null || !SourceModels.ACTIVE.equals(s.lifecycle())) {
            return computed;
        }
        return new Representative(state.connectionState(), state.errorKind(), computed.totalInstances(), computed.connectedInstances());
    }

    static StateDetail stateDetail(Representative rep, List<RuntimeRow> runtimes) {
        RuntimeRow latestError = runtimes.stream().filter(r -> r.errorKind() != null)
                .max(Comparator.comparing(RuntimeRow::reportedAt)).orElse(null);
        return new StateDetail(rep.connectedInstances(), rep.totalInstances(),
                rep.errorKind() != null ? rep.errorKind() : latestError == null ? null : latestError.errorKind(),
                latestError == null ? null : latestError.errorMessage());
    }

    static RuntimeInstance instance(RuntimeRow r, Instant now) {
        return new RuntimeInstance(r.instanceId(), r.state(), r.errorKind(), r.errorMessage(), r.clientId(), r.connectedSince(),
                r.reconnects24h(), r.reportedAt(), !ConnectionStates.fresh(r, now));
    }

    /**
     * 목록 지표 요약: 분당 수신(최근 5분 평균, 지금 진행 중인 분 제외), 디코딩 실패율(최근 1시간, 0~1), 최근 1시간 분별 수신(60개).
     */
    static StatSummary summary(StateRow state, Map<Instant, long[]> minutes, Instant now) {
        Instant current = minuteFloor(now);
        long[] series = new long[60];
        long received1h = 0;
        long errors1h = 0;
        long received5 = 0;
        for (Map.Entry<Instant, long[]> e : minutes.entrySet()) {
            long ago = Duration.between(e.getKey(), current).toMinutes();
            if (ago < 0 || ago > 59) {
                continue;
            }
            series[59 - (int) ago] += e.getValue()[0];
            received1h += e.getValue()[0];
            errors1h += e.getValue()[1];
            if (ago >= 1 && ago <= 5) {
                received5 += e.getValue()[0];
            }
        }
        Double rate = received5 / 5.0;
        Double errorRate = received1h == 0 ? null : Math.min(1.0, (double) errors1h / received1h);
        Instant last = state == null ? null : state.lastReceivedAt();
        return new StatSummary(last, rate, errorRate, Arrays.stream(series).boxed().toList());
    }

    static Instant minuteFloor(Instant t) {
        return t.truncatedTo(ChronoUnit.MINUTES);
    }

    /** 쉼표로 나눈 값과 반복 파라미터를 합친다(대문자) */
    static List<String> split(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String r : raw) {
                if (r == null) {
                    continue;
                }
                for (String part : r.split(",")) {
                    if (!part.isBlank()) {
                        out.add(part.strip().toUpperCase(Locale.ROOT));
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    static String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
