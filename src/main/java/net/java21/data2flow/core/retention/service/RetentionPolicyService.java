package net.java21.data2flow.core.retention.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.retention.domain.DataClass;
import net.java21.data2flow.core.retention.domain.RetentionErrorCode;
import net.java21.data2flow.core.retention.domain.RetentionRules;
import net.java21.data2flow.core.retention.domain.RetentionRules.Effective;
import net.java21.data2flow.core.retention.domain.RetentionRules.Policy;
import net.java21.data2flow.core.retention.domain.RetentionRules.Scope;
import net.java21.data2flow.core.retention.dto.RetentionDtos.EffectivePolicy;
import net.java21.data2flow.core.retention.dto.RetentionDtos.InternalPoliciesResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.OrganizationPolicies;
import net.java21.data2flow.core.retention.dto.RetentionDtos.PoliciesResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.PolicyItem;
import net.java21.data2flow.core.retention.dto.RetentionDtos.PreviewItem;
import net.java21.data2flow.core.retention.dto.RetentionDtos.PreviewRequest;
import net.java21.data2flow.core.retention.dto.RetentionDtos.PreviewResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.SaveRequest;
import net.java21.data2flow.core.retention.dto.RetentionDtos.SaveResponse;
import net.java21.data2flow.core.retention.repository.RetentionPolicyRepository;
import net.java21.data2flow.core.retention.repository.RetentionPolicyRepository.StoredPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 보관 정책(TSD-02.01·05.01·05.03, BR-TSD-02·03·17, NFR-04.03): 조회 API-TSD-40, 미리 보기 API-TSD-41(pipeline API-TSD-62 중계 + 확인
 * 토큰), 저장 API-TSD-42(줄이면 확인 토큰 필수 → 저장 뒤 pipeline API-TSD-53 통지), 내부 전체 조회 API-TSD-60.
 * 실제 삭제는 pipeline 야간 작업(매일 02:00 UTC)이 한다. 저장 즉시 지우지 않는다.
 */
@Service
public class RetentionPolicyService {

    static final String AUDIT_CHANGED = "RETENTION_POLICY_CHANGED";
    /** 미리 보기 확인 토큰 유효 시간 */
    static final Duration CONFIRM_TTL = Duration.ofMinutes(10);
    /** 변경안 한 번에 받는 줄 수 */
    static final int MAX_ITEMS = 200;
    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyService.class);

    private final RoleChecker roleChecker;
    private final RetentionPolicyRepository policies;
    private final PipelineRetentionClient pipeline;
    private final InternalOrganizations organizations;
    private final Audits audits;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RetentionPolicyService(RoleChecker roleChecker, RetentionPolicyRepository policies, PipelineRetentionClient pipeline,
                                  InternalOrganizations organizations, Audits audits, TransactionTemplate tx, Clock clock) {
        this.roleChecker = roleChecker;
        this.policies = policies;
        this.pipeline = pipeline;
        this.organizations = organizations;
        this.audits = audits;
        this.tx = tx;
        this.clock = clock;
    }

    /** API-TSD-40 유효 정책(ORG 13종 + 재정의) */
    @Transactional(readOnly = true)
    public PoliciesResponse effective() {
        roleChecker.require(Permission.TS_POLICY);
        long org = roleChecker.currentUser().organizationId();
        List<StoredPolicy> stored = policies.list(org);
        return new PoliciesResponse(toEffective(stored), policies.version(org));
    }

    /**
     * API-TSD-41 미리 보기. 변경안을 검증하고 줄어드는 항목만 pipeline에 물어(API-TSD-62) 지워질 행 수·크기를 돌려준다. 줄어드는 항목이
     * 없으면 pipeline을 부르지 않는다. 확인 토큰은 이 변경안 그대로 저장할 때만 맞는다
     */
    public PreviewResponse preview(PreviewRequest request) {
        roleChecker.require(Permission.TS_POLICY);
        long org = roleChecker.currentUser().organizationId();
        List<Policy> changes = validate(org, request == null ? null : request.items());
        List<Policy> before = policies.list(org).stream().map(StoredPolicy::policy).toList();
        List<Policy> after = RetentionRules.merge(before, changes);
        List<Policy> shortened = RetentionRules.shortened(before, after);
        Instant now = clock.instant();
        Instant expires = now.plus(CONFIRM_TTL);
        String token = RetentionRules.confirmToken(org, policies.version(org), after, expires.getEpochSecond());
        if (shortened.isEmpty()) {
            return new PreviewResponse(0, 0, List.of(), false, token, expires);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Policy p : shortened) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scope", p.scope().name());
            item.put("scopeRef", p.scopeRef());
            item.put("dataClass", p.dataClass().name());
            item.put("retainDays", p.retainDays());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", org);
        body.put("items", items);
        JsonNode r = pipeline.preview(body);
        List<PreviewItem> byMetric = new ArrayList<>();
        for (JsonNode i : r.path("byMetric")) {
            byMetric.add(new PreviewItem(text(i, "scope"), text(i, "scopeRef"), text(i, "dataClass"), i.path("rows").asLong(0),
                    i.path("bytes").asLong(0)));
        }
        return new PreviewResponse(r.path("affectedRows").asLong(0), r.path("affectedBytes").asLong(0), byMetric, true, token, expires);
    }

    /**
     * API-TSD-42 저장. 줄어드는 항목이 있으면 미리 보기 토큰이 맞아야 한다(409 RETENTION_CONFIRM_REQUIRED, BR-TSD-03). 저장은 조직 정책
     * 전체를 새 판으로 바꾸고, 커밋 뒤 pipeline에 알린다(API-TSD-53). 통지가 실패해도 저장은 유지되고 pipeline은 5분 주기로 다시 읽는다
     */
    public SaveResponse save(SaveRequest request) {
        roleChecker.require(Permission.TS_POLICY);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        List<Policy> changes = validate(org, request == null ? null : request.items());
        Instant now = clock.instant();
        Long saved = tx.execute(status -> {
            policies.lockOrganization(org);
            long current = policies.version(org);
            List<Policy> before = policies.list(org).stream().map(StoredPolicy::policy).toList();
            List<Policy> after = RetentionRules.merge(before, changes);
            if (RetentionRules.same(before, after)) {
                return current;
            }
            List<Policy> shortened = RetentionRules.shortened(before, after);
            if (!shortened.isEmpty()
                    && !RetentionRules.verify(request.confirmToken(), org, current, after, now.getEpochSecond())) {
                throw new BusinessException(RetentionErrorCode.RETENTION_CONFIRM_REQUIRED);
            }
            int next = (int) current + 1;
            policies.replace(org, after, next, user.userId(), now);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("version", next);
            detail.put("shortened", shortened.stream().map(p -> p.scope() + ":" + (p.scopeRef() == null ? "" : p.scopeRef() + ":")
                    + p.dataClass() + "=" + p.retainDays()).toList());
            detail.put("items", after.size());
            audits.record(audits.event(org, AUDIT_CHANGED).actor(user).target("RETENTION_POLICY", Long.toString(org)).detail(detail));
            return (long) next;
        });
        long version = saved == null ? 0 : saved;
        Instant appliesAt = nextNightly(now);
        try {
            JsonNode r = pipeline.applyPolicy(org, version);
            if (r != null && r.hasNonNull("appliesAt")) {
                appliesAt = Instant.parse(r.get("appliesAt").asString());
            }
        } catch (RuntimeException ex) {
            log.warn("pipeline 보관 정책 통지 실패(org={}, version={}): {} — pipeline 5분 주기 조회로 반영", org, version,
                    ex.getClass().getSimpleName());
        }
        return new SaveResponse(toEffective(policies.list(org)), version, appliesAt);
    }

    /** API-TSD-60 배포 조직 전체의 유효 정책(pipeline 5분 주기·통지 때) */
    @Transactional(readOnly = true)
    public InternalPoliciesResponse internalAll() {
        List<OrganizationPolicies> out = new ArrayList<>();
        for (long org : organizations.deploymentOrganizations()) {
            List<StoredPolicy> stored = policies.list(org);
            out.add(new OrganizationPolicies(Long.toString(org), policies.version(org), toEffective(stored)));
        }
        return new InternalPoliciesResponse(out);
    }

    /** 다음 보관 정리 시각(매일 02:00 UTC, pipeline 통지가 실패했을 때의 추정) */
    static Instant nextNightly(Instant now) {
        ZonedDateTime at = now.atZone(ZoneOffset.UTC).with(LocalTime.of(2, 0));
        if (!at.toInstant().isAfter(now)) {
            at = at.plusDays(1);
        }
        return at.toInstant();
    }

    private static List<EffectivePolicy> toEffective(List<StoredPolicy> stored) {
        Map<String, Integer> versions = new LinkedHashMap<>();
        stored.forEach(s -> versions.put(s.policy().key(), s.version()));
        List<EffectivePolicy> out = new ArrayList<>();
        for (Effective e : RetentionRules.effective(stored.stream().map(StoredPolicy::policy).toList())) {
            Policy p = e.policy();
            out.add(new EffectivePolicy(p.scope().name(), p.scopeRef(), p.dataClass().name(), p.retainDays(), p.compressAfterDays(),
                    p.archiveBeforeDelete(), p.storeMode(), p.dataClass().minDays(), e.inherited(), versions.getOrDefault(p.key(), 0)));
        }
        return out;
    }

    /**
     * 변경안 검증(400 RETENTION_INVALID, errors[{field: items[i].필드}]): 종류·범위 값, 최소·최대 일수(AGG_1D는 무기한 0만), MODEL·METRIC
     * 재정의는 TELEMETRY·AGG_*만, 모델·측정 항목이 조직에 있어야 함, storeMode는 METRIC·TELEMETRY의 상태형 항목만, compressAfterDays는
     * TELEMETRY ORG만(1~보관 일수), 같은 범위·대상·종류 중복 금지. MODEL은 ID로 바꿔 저장한다
     */
    List<Policy> validate(long organizationId, List<PolicyItem> items) {
        if (items == null) {
            throw invalid("items", "NotNull");
        }
        if (items.size() > MAX_ITEMS) {
            throw invalid("items", "Size");
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        List<Policy> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            PolicyItem item = items.get(i);
            String f = "items[" + i + "].";
            if (item == null) {
                errors.add(new FieldErrorDetail("items[" + i + "]", "NotNull", null));
                continue;
            }
            Scope scope = parseScope(item.scope());
            DataClass dc = DataClass.parse(item.dataClass()).orElse(null);
            if (scope == null) {
                errors.add(new FieldErrorDetail(f + "scope", "INVALID", null));
            }
            if (dc == null) {
                errors.add(new FieldErrorDetail(f + "dataClass", "INVALID", null));
            }
            if (scope == null || dc == null) {
                continue;
            }
            if (item.retainDays() == null || !dc.allows(item.retainDays())) {
                errors.add(new FieldErrorDetail(f + "retainDays", "Range",
                        dc == DataClass.AGG_1D ? "0" : dc.minDays() + "~" + dc.maxDays()));
                continue;
            }
            String ref = item.scopeRef() == null || item.scopeRef().isBlank() ? null : item.scopeRef().strip();
            String storeMode = item.storeMode() == null || item.storeMode().isBlank() ? null
                    : item.storeMode().strip().toUpperCase(Locale.ROOT);
            if (scope == Scope.ORG) {
                if (ref != null) {
                    errors.add(new FieldErrorDetail(f + "scopeRef", "MUST_BE_NULL", null));
                    continue;
                }
            } else {
                if (!dc.overridable()) {
                    errors.add(new FieldErrorDetail(f + "scope", "NOT_OVERRIDABLE", dc.name()));
                    continue;
                }
                if (ref == null) {
                    errors.add(new FieldErrorDetail(f + "scopeRef", "NotBlank", null));
                    continue;
                }
                if (scope == Scope.MODEL) {
                    Long model = policies.findModel(organizationId, ref).orElse(null);
                    if (model == null) {
                        errors.add(new FieldErrorDetail(f + "scopeRef", "MODEL_NOT_FOUND", null));
                        continue;
                    }
                    ref = Long.toString(model);
                } else if (policies.metricStateful(organizationId, ref).isEmpty()) {
                    errors.add(new FieldErrorDetail(f + "scopeRef", "METRIC_NOT_FOUND", null));
                    continue;
                }
            }
            if (storeMode != null) {
                if (scope != Scope.METRIC || dc != DataClass.TELEMETRY || !Set.of("ALL", "ON_CHANGE").contains(storeMode)) {
                    errors.add(new FieldErrorDetail(f + "storeMode", "INVALID", null));
                    continue;
                }
                if ("ON_CHANGE".equals(storeMode) && !policies.metricStateful(organizationId, ref).orElse(false)) {
                    errors.add(new FieldErrorDetail(f + "storeMode", "NOT_STATEFUL", ref));
                    continue;
                }
            }
            Integer compress = item.compressAfterDays();
            if (compress != null && (dc != DataClass.TELEMETRY || scope != Scope.ORG || compress < 1 || compress > item.retainDays())) {
                errors.add(new FieldErrorDetail(f + "compressAfterDays", "Range", null));
                continue;
            }
            if (compress == null && dc == DataClass.TELEMETRY && scope == Scope.ORG) {
                compress = DataClass.DEFAULT_COMPRESS_AFTER_DAYS;
            }
            Policy p = new Policy(scope, ref, dc, item.retainDays(), compress, Boolean.TRUE.equals(item.archiveBeforeDelete()), storeMode);
            if (!keys.add(p.key())) {
                errors.add(new FieldErrorDetail(f + "scopeRef", "DUPLICATE", null));
                continue;
            }
            out.add(p);
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(RetentionErrorCode.RETENTION_INVALID, errors);
        }
        return out;
    }

    private static Scope parseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return Scope.ORG;
        }
        try {
            return Scope.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
