package net.java21.data2flow.core.output.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.output.domain.OutputConnectionRules;
import net.java21.data2flow.core.output.domain.OutputSample;
import net.java21.data2flow.core.output.dto.OutputDtos.DeviceContext;
import net.java21.data2flow.core.output.dto.OutputDtos.DeviceContextsResponse;
import net.java21.data2flow.core.output.dto.OutputDtos.RuntimeConnection;
import net.java21.data2flow.core.output.dto.OutputDtos.RuntimeResponse;
import net.java21.data2flow.core.output.repository.OutputConnectionRepository;
import net.java21.data2flow.core.output.repository.OutputConnectionRepository.DeviceContextRow;
import net.java21.data2flow.core.output.repository.OutputConnectionRepository.OutputRow;
import net.java21.data2flow.core.output.repository.OutputConnectionRepository.SecretRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * action이 부르는 출력 연결 내부 API(출력 계약): API-DSC-73 실행 설정(복호화한 비밀값 포함), API-DSC-74 기기 맥락(필터·토픽 변수),
 * API-DSC-75 1분 발송 지표. 범위는 이 배포가 맡는 조직(ADR-030, {@link InternalOrganizations#deploymentOrganizations()}).
 */
@Service
public class OutputInternalService {

    static final int MAX_DEVICE_IDS = 500;
    static final Duration STATS_RETENTION = Duration.ofDays(7);

    private final OutputConnectionRepository outputs;
    private final OutputConnectionService connections;
    private final InternalOrganizations organizations;
    private final JsonMapper json;
    private final Clock clock;

    public OutputInternalService(OutputConnectionRepository outputs, OutputConnectionService connections, InternalOrganizations organizations,
                                 JsonMapper json, Clock clock) {
        this.outputs = outputs;
        this.connections = connections;
        this.organizations = organizations;
        this.json = json;
        this.clock = clock;
    }

    /** API-DSC-73. {@code sinceVersion}과 같으면 빈 값(204) */
    @Transactional(readOnly = true)
    public Optional<RuntimeResponse> runtime(Long sinceVersion) {
        List<Long> orgs = organizations.deploymentOrganizations();
        if (orgs.isEmpty()) {
            return sinceVersion != null && sinceVersion == 0L ? Optional.empty() : Optional.of(new RuntimeResponse(0, List.of()));
        }
        long version = outputs.versionSum(orgs);
        if (sinceVersion != null && sinceVersion == version) {
            return Optional.empty();
        }
        List<OutputRow> rows = outputs.listForRuntime(orgs);
        List<SecretRow> secrets = outputs.secrets(rows.stream().map(OutputRow::id).toList());
        List<RuntimeConnection> list = new ArrayList<>();
        for (OutputRow r : rows) {
            JsonNode filter = OutputConnectionRules.storedFilter(json.readTree(r.filter())).toJson(true);
            list.add(new RuntimeConnection(Long.toString(r.id()), Long.toString(r.organizationId()), r.name(), r.type(), json.readTree(r.target()),
                    filter, r.format(), r.template(), connections.decrypt(r.id(), secrets), r.enabled(), r.version()));
        }
        return Optional.of(new RuntimeResponse(version, list));
    }

    /** API-DSC-74 {@code deviceIds=1,2,3}(≤500). 없는 ID는 뺀다 */
    @Transactional(readOnly = true)
    public DeviceContextsResponse deviceContexts(String rawIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (rawIds != null && !rawIds.isBlank()) {
            for (String part : Arrays.stream(rawIds.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList()) {
                if (!part.matches("\\d{1,18}")) {
                    throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("deviceIds", "Type", null)));
                }
                ids.add(Long.parseLong(part));
            }
        }
        if (ids.size() > MAX_DEVICE_IDS) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("deviceIds", "Size", null)));
        }
        if (ids.isEmpty()) {
            return new DeviceContextsResponse(List.of());
        }
        return new DeviceContextsResponse(outputs.deviceContexts(organizations.deploymentOrganizations(), ids).stream()
                .map(OutputInternalService::toContext).toList());
    }

    /** API-DSC-75 1분 지표 더하기. 모르는 연결·배포 밖 조직·7일 지난 분은 건너뛴다. 반영한 항목 수 */
    @Transactional
    public int addStats(JsonNode body) {
        JsonNode items = body == null ? null : body.get("items");
        if (items == null || !items.isArray() || items.size() > 10_000) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("items", "INVALID", null)));
        }
        Set<Long> allowed = Set.copyOf(organizations.deploymentOrganizations());
        Instant oldest = clock.instant().minus(STATS_RETENTION);
        int applied = 0;
        for (JsonNode item : items) {
            Long outputId = id(item.get("outputId"));
            Long orgId = id(item.get("organizationId"));
            Instant minute = minute(item.path("minute").asString(null));
            if (outputId == null || orgId == null || minute == null || !allowed.contains(orgId) || minute.isBefore(oldest)) {
                continue;
            }
            Integer lag = item.hasNonNull("lagMs") && item.get("lagMs").isIntegralNumber() ? Math.max(0, item.get("lagMs").asInt()) : null;
            applied += outputs.addStats(orgId, outputId, minute, nonNegative(item, "sent"), nonNegative(item, "failed"),
                    nonNegative(item, "retried"), lag);
        }
        return applied;
    }

    static DeviceContext toContext(DeviceContextRow r) {
        return new DeviceContext(Long.toString(r.deviceId()), Long.toString(r.organizationId()), r.deviceName(),
                r.spaceId() == null ? null : Long.toString(r.spaceId()), r.spaceCode(),
                OutputSample.pathIds(r.spacePath()).stream().map(String::valueOf).collect(Collectors.toList()),
                r.groupIds().stream().map(String::valueOf).toList());
    }

    private static int nonNegative(JsonNode item, String field) {
        JsonNode n = item.get(field);
        return n == null || !n.isIntegralNumber() ? 0 : Math.max(0, n.asInt());
    }

    private static Long id(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        String s = n.asString("").strip();
        return s.matches("\\d{1,18}") ? Long.parseLong(s) : null;
    }

    private static Instant minute(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw).truncatedTo(ChronoUnit.MINUTES);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
