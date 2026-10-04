package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.dataexchange.domain.ExportFormat;
import net.java21.data2flow.core.dataexchange.domain.RelativePeriod;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ExportScheduleResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.TestTargetRequest;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.TestTargetResponse;
import net.java21.data2flow.core.dataexchange.repository.ExportScheduleRepository;
import net.java21.data2flow.core.dataexchange.repository.ExportScheduleRepository.ScheduleRow;
import net.java21.data2flow.core.dataexchange.repository.ExportScheduleRepository.ScheduleValues;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.QueryRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 정기 내보내기 일정 API(TSD-04.03·07.02, API-TSD-23·56, BR-TSD-28). TS_EXPORT(ANALYST 이상) — 본인 일정, ADMIN은 조직 전체(UC-TSD-12).
 * cron은 조직 시간대 기준(5필드 {@code 분 시 일 월 요일} 또는 6필드), 기간은 PREVIOUS_DAY·PREVIOUS_WEEK·PREVIOUS_MONTH(전날·지난주·지난달).
 * 전달은 EMAIL(수신자에게 내려받기 링크 메일) 또는 STORAGE(S3·SFTP에 파일과 같은 판의 데이터 사전). 자격은 쓰기 전용(암호화 저장).
 */
@Service
public class ExportScheduleService {

    static final String CREDENTIAL_REF = "db:export_schedules.credential_enc";
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s]{1,63}$");

    private final RoleChecker roleChecker;
    private final ExportScheduleRepository schedules;
    private final ExportPlanner planner;
    private final DeliveryTargets targets;
    private final SecretCipher cipher;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public ExportScheduleService(RoleChecker roleChecker, ExportScheduleRepository schedules, ExportPlanner planner, DeliveryTargets targets,
                                 SecretCipher cipher, Audits audits, JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.schedules = schedules;
        this.planner = planner;
        this.targets = targets;
        this.cipher = cipher;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-TSD-23 목록 */
    @Transactional(readOnly = true)
    public ListApiResponse<ExportScheduleResponse> list(Integer page, Integer size) {
        roleChecker.require(Permission.TS_EXPORT);
        CurrentUser user = roleChecker.currentUser();
        Long owner = roleChecker.has(Permission.IAM_MANAGE) ? null : user.userId();
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, schedules.list(user.organizationId(), owner, params.size(), params.offset()).stream()
                .map(this::toResponse).toList(), schedules.count(user.organizationId(), owner));
    }

    @Transactional(readOnly = true)
    public ExportScheduleResponse get(long id) {
        roleChecker.require(Permission.TS_EXPORT);
        return toResponse(load(id));
    }

    /** API-TSD-23 만들기 */
    @Transactional
    public ExportScheduleResponse create(JsonNode body) {
        roleChecker.require(Permission.TS_EXPORT);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        ScheduleValues v = values(org, body, null, null);
        long id = schedules.insert(org, v, user.userId(), clock.instant());
        audits.record(audits.event(org, "EXPORT_SCHEDULE_CREATED").actor(user).target("EXPORT_SCHEDULE", Long.toString(id))
                .detail("name", v.name()).detail("delivery", v.delivery()).detail("targetType", v.targetType()));
        return toResponse(schedules.findById(org, id).orElseThrow());
    }

    /** API-TSD-23 고치기(온 키만, baseVersion) */
    @Transactional
    public ExportScheduleResponse update(long id, JsonNode body) {
        roleChecker.require(Permission.TS_EXPORT);
        ScheduleRow before = load(id);
        long base = VersionCheck.baseVersion(body);
        VersionCheck.require(base, before.version());
        ScheduleValues v = values(before.organizationId(), body, before, id);
        CurrentUser user = roleChecker.currentUser();
        VersionCheck.requireUpdated(schedules.update(before.organizationId(), id, (int) base, v, user.userId(), clock.instant()));
        audits.record(audits.event(before.organizationId(), "EXPORT_SCHEDULE_UPDATED").actor(user).target("EXPORT_SCHEDULE", Long.toString(id))
                .detail("name", v.name()).detail("enabled", v.enabled()));
        return toResponse(schedules.findById(before.organizationId(), id).orElseThrow());
    }

    /** API-TSD-23 지우기 */
    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.TS_EXPORT);
        ScheduleRow before = load(id);
        schedules.delete(before.organizationId(), id);
        audits.record(audits.event(before.organizationId(), "EXPORT_SCHEDULE_DELETED").actor(roleChecker.currentUser())
                .target("EXPORT_SCHEDULE", Long.toString(id)).detail("name", before.name()));
    }

    /** API-TSD-56 대상 연결 테스트(저장하지 않음) */
    public TestTargetResponse testTarget(TestTargetRequest req) {
        roleChecker.require(Permission.TS_EXPORT);
        if (req == null) {
            throw DeliveryTargets.invalid("targetType");
        }
        return targets.test(req.targetType(), req.target(), req.credential());
    }

    /** 자격 복호화(실행기) */
    JsonNode credential(ScheduleRow s) {
        if (s.credentialEnc() == null) {
            return null;
        }
        return json.readTree(cipher.decryptBytes(s.credentialEnc(), context(s.organizationId())));
    }

    static String context(long organizationId) {
        return "data2flow_core.export_schedules.credential_enc:" + organizationId;
    }

    /** cron(5·6필드)을 조직 시간대로 보고 다음 실행 시각 */
    static Instant nextRun(String cron, ZoneId zone, Instant after) {
        ZonedDateTime next = parseCron(cron).next(after.atZone(zone));
        return next == null ? null : next.toInstant();
    }

    static CronExpression parseCron(String cron) {
        String c = cron.strip();
        if (c.split("\\s+").length == 5) {
            c = "0 " + c;
        }
        return CronExpression.parse(c);
    }

    private ScheduleRow load(long id) {
        CurrentUser user = roleChecker.currentUser();
        ScheduleRow s = schedules.findById(user.organizationId(), id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (s.createdBy() != user.userId() && !roleChecker.has(Permission.IAM_MANAGE)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return s;
    }

    /** 요청(만들기 전체·고치기 온 키)을 검사해 저장 값으로 */
    private ScheduleValues values(long org, JsonNode body, ScheduleRow before, Long exceptId) {
        if (body == null || !body.isObject()) {
            throw invalid("body");
        }
        boolean create = before == null;
        String name = has(body, "name", create) ? text(body, "name") : before.name();
        if (name == null || name.isBlank() || name.length() > 100) {
            throw invalid("name");
        }
        if (schedules.existsName(org, name.strip(), exceptId)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "DUPLICATE", null)));
        }
        String queryJson = before == null ? null : before.queryJson();
        if (has(body, "query", create)) {
            JsonNode q = body.get("query");
            if (q == null || !q.isObject()) {
                throw invalid("query");
            }
            QueryRequest query = json.treeToValue(q, QueryRequest.class);
            Instant now = clock.instant();
            planner.plan(query, "LONG", true, null, new Instant[]{now.minus(Duration.ofDays(1)), now});
            queryJson = json.writeValueAsString(q);
        }
        String format = has(body, "format", create) ? upper(text(body, "format")) : before.format();
        if (ExportFormat.parse(format) == null) {
            throw invalid("format");
        }
        String cron = has(body, "cron", create) ? text(body, "cron") : before.cron();
        try {
            parseCron(cron == null ? "" : cron);
        } catch (IllegalArgumentException ex) {
            throw invalid("cron");
        }
        String periodRaw = has(body, "relativePeriod", create) ? text(body, "relativePeriod") : before.relativePeriod();
        RelativePeriod period = RelativePeriod.parse(periodRaw);
        if (period == null) {
            throw invalid("relativePeriod");
        }
        String delivery = has(body, "delivery", create) ? upper(text(body, "delivery")) : before.delivery();
        if (!"EMAIL".equals(delivery) && !"STORAGE".equals(delivery)) {
            throw invalid("delivery");
        }
        List<String> recipients = before == null ? List.of() : before.recipients();
        if (body.has("recipients")) {
            recipients = new ArrayList<>();
            JsonNode r = body.get("recipients");
            if (r != null && !r.isNull()) {
                if (!r.isArray() || r.size() > 20) {
                    throw invalid("recipients");
                }
                for (int i = 0; i < r.size(); i++) {
                    String mail = r.get(i).isString() ? r.get(i).stringValue().strip() : "";
                    if (!EMAIL.matcher(mail).matches()) {
                        throw invalid("recipients[" + i + "]");
                    }
                    recipients.add(mail);
                }
            }
        }
        if ("EMAIL".equals(delivery) && (recipients == null || recipients.isEmpty())) {
            throw invalid("recipients");
        }
        String targetType = before == null ? null : before.targetType();
        String targetJson = before == null ? null : before.targetJson();
        if (body.has("targetType") || body.has("target")) {
            JsonNode target = body.has("target") ? body.get("target") : json.readTree(targetJson == null ? "null" : targetJson);
            String type = body.has("targetType") ? text(body, "targetType") : targetType;
            if (type == null && (target == null || target.isNull())) {
                targetType = null;
                targetJson = null;
            } else {
                targetType = targets.validate(type, target);
                targetJson = json.writeValueAsString(target);
            }
        }
        if ("STORAGE".equals(delivery) && targetType == null) {
            throw invalid("targetType");
        }
        byte[] credentialEnc = before == null ? null : before.credentialEnc();
        String credentialRef = before == null ? null : before.credentialRef();
        if (body.has("credential")) {
            JsonNode c = body.get("credential");
            if (c == null || c.isNull()) {
                credentialEnc = null;
                credentialRef = null;
            } else if (!c.isObject()) {
                throw invalid("credential");
            } else {
                credentialEnc = cipher.encrypt(json.writeValueAsString(c).getBytes(StandardCharsets.UTF_8), context(org));
                credentialRef = CREDENTIAL_REF;
            }
        } else if (body.has("credentialRef") && credentialEnc == null) {
            credentialRef = text(body, "credentialRef");
        }
        boolean enabled = body.has("enabled") ? body.get("enabled").asBoolean(true) : before == null || before.enabled();
        ZoneId zone = ZoneId.of(schedules.findOrganization(org).timezone());
        Instant nextRunAt = enabled ? nextRun(cron, zone, clock.instant()) : null;
        return new ScheduleValues(name.strip(), queryJson, format, cron.strip(), period.name(), delivery, recipients, targetType, targetJson,
                credentialRef, credentialEnc, enabled, nextRunAt);
    }

    ExportScheduleResponse toResponse(ScheduleRow s) {
        return new ExportScheduleResponse(Long.toString(s.id()), s.name(), json.readTree(s.queryJson()), s.format(), s.cron(),
                s.relativePeriod(), s.delivery(), s.recipients(), s.targetType(), s.targetJson() == null ? null : json.readTree(s.targetJson()),
                s.credentialRef(), s.credentialEnc() != null, s.enabled(), s.lastRunAt(), s.lastStatus(), s.lastError(), s.nextRunAt(),
                s.lastVersion(), s.version(), Long.toString(s.createdBy()));
    }

    private static boolean has(JsonNode body, String field, boolean required) {
        if (body.has(field)) {
            return true;
        }
        if (required) {
            throw invalid(field);
        }
        return false;
    }

    private static String text(JsonNode body, String field) {
        return DeliveryTargets.text(body, field);
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    static BusinessException invalid(String field) {
        return DeliveryTargets.invalid(field);
    }
}
