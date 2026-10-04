package net.java21.data2flow.core.notify.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.core.alarm.domain.AlarmErrorCode;
import net.java21.data2flow.core.alarm.repository.AlarmRepository;
import net.java21.data2flow.core.alarm.service.AlarmNotifications;
import net.java21.data2flow.core.alarm.service.AlarmQueryService;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.notify.domain.TemplateVariables;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Preview;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Template;
import net.java21.data2flow.core.notify.dto.NotifyDtos.TemplateSaved;
import net.java21.data2flow.core.notify.dto.NotifyDtos.TemplateWarning;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Variable;
import net.java21.data2flow.core.notify.repository.TemplateRepository;
import net.java21.data2flow.core.notify.repository.TemplateRepository.TemplateRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 알림 템플릿(RUL-03.04·05.01, API-RUL-22). 조회 RULE_READ, 수정·미리 보기·되돌리기 NOTIFY_POLICY_WRITE. 시스템 기본(4개 언어 × WEB·TELEGRAM)은
 * 바꾸지 않고 조직 행을 따로 만든다. 알 수 없는 변수가 있어도 저장하고 경고(TEMPLATE_VARIABLE_UNKNOWN, 200)를 돌려준다. 메신저 본문은 4,000자까지.
 */
@Service
public class TemplateService {

    static final Set<String> LOCALES = Set.of("ko", "en", "ja", "zh");

    private final TemplateRepository templates;
    private final AlarmQueryService alarmQuery;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final Clock clock;
    private final String webBaseUrl;

    public TemplateService(TemplateRepository templates, AlarmQueryService alarmQuery, CoreEventPublisher publisher,
                           RoleChecker roleChecker, Clock clock, CoreProperties properties) {
        this.templates = templates;
        this.alarmQuery = alarmQuery;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.clock = clock;
        this.webBaseUrl = properties.webBaseUrl();
    }

    @Transactional(readOnly = true)
    public List<Template> list(String channel, String locale) {
        roleChecker.require(Permission.RULE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        String ch = channel == null || channel.isBlank() ? null : channel.strip().toUpperCase(Locale.ROOT);
        String lc = locale == null || locale.isBlank() ? null : locale.strip().toLowerCase(Locale.ROOT);
        return templates.listEffective(orgId, ch, lc).stream().map(TemplateService::view).toList();
    }

    public List<Variable> variables() {
        roleChecker.require(Permission.RULE_READ);
        return TemplateVariables.KNOWN.stream().map(v -> new Variable(v, null)).toList();
    }

    /** 수정 {subject?, body, baseVersion?}. 기본 행이면 조직 행을 만든다 */
    @Transactional
    public TemplateSaved update(long id, JsonNode body) {
        roleChecker.require(Permission.NOTIFY_POLICY_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        TemplateRow current = templates.findVisible(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        String text = body == null ? "" : body.path("body").asString("");
        String subject = body == null || !body.hasNonNull("subject") ? null : body.get("subject").asString("");
        if (text.isBlank() || (!"WEB".equals(current.channel()) && text.length() > 4000)) {
            throw invalid("body", "Size");
        }
        if (subject != null && subject.length() > 200) {
            throw invalid("subject", "Size");
        }
        Integer base = body.hasNonNull("baseVersion") ? body.get("baseVersion").asInt() : null;
        long savedId;
        if (current.organizationId() == 0) {
            TemplateRow existing = templates.findOrgRow(orgId, current.templateKey(), current.channel(), current.locale()).orElse(null);
            if (existing == null) {
                savedId = templates.insertOrgRow(orgId, current.templateKey(), current.channel(), current.locale(), subject, text,
                        user.userId(), clock.instant());
            } else {
                savedId = existing.id();
                templates.updateOrgRow(orgId, savedId, null, subject, text, user.userId(), clock.instant());
            }
        } else {
            savedId = id;
            if (templates.updateOrgRow(orgId, id, base, subject, text, user.userId(), clock.instant()) == 0) {
                throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
            }
        }
        publisher.configChanged(ConfigChangedMessage.EntityType.NOTIFICATION_TEMPLATE, savedId, 0, orgId);
        List<TemplateWarning> warnings = TemplateVariables.unknown(subject, text).stream()
                .map(n -> new TemplateWarning(AlarmErrorCode.TEMPLATE_VARIABLE_UNKNOWN.code(), n)).toList();
        return new TemplateSaved(view(templates.findVisible(orgId, savedId).orElseThrow()), warnings);
    }

    /** 미리 보기 {alarmId}: 실제 알람 값으로 치환 */
    @Transactional(readOnly = true)
    public Preview preview(long id, JsonNode body) {
        roleChecker.require(Permission.NOTIFY_POLICY_WRITE);
        long orgId = roleChecker.currentUser().organizationId();
        TemplateRow t = templates.findVisible(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        long alarmId = body == null ? 0 : body.path("alarmId").asLong(0);
        if (alarmId < 1) {
            throw invalid("alarmId", "NotNull");
        }
        AlarmRepository.AlarmRow alarm = alarmQuery.visible(orgId, alarmId);
        Map<String, Object> values = AlarmNotifications.variables(alarm, webBaseUrl + "/alarms/" + alarm.id());
        return new Preview(TemplateVariables.render(t.subject(), values), TemplateVariables.render(t.body(), values));
    }

    /** 기본값으로 되돌리기: 조직 행을 지운다 */
    @Transactional
    public Template reset(long id) {
        roleChecker.require(Permission.NOTIFY_POLICY_WRITE);
        long orgId = roleChecker.currentUser().organizationId();
        TemplateRow t = templates.findVisible(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (t.organizationId() != 0) {
            templates.deleteOrgRow(orgId, id);
            publisher.configChanged(ConfigChangedMessage.EntityType.NOTIFICATION_TEMPLATE, id, 0, orgId);
        }
        return view(templates.findBuiltin(t.templateKey(), t.channel(), t.locale(), orgId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND)));
    }

    static Template view(TemplateRow t) {
        return new Template(Long.toString(t.id()), t.templateKey(), t.channel(), t.locale(), t.subject(), t.body(), t.builtin(),
                t.organizationId() != 0, t.version(), t.updatedAt());
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
