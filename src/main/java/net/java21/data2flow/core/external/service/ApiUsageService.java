package net.java21.data2flow.core.external.service;

import net.java21.data2flow.contracts.alarm.AlarmClearReason;
import net.java21.data2flow.contracts.alarm.AlarmKeys;
import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import net.java21.data2flow.contracts.alarm.AlarmSourceType;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.alarm.service.AlarmService;
import net.java21.data2flow.core.external.domain.ExternalErrorCode;
import net.java21.data2flow.core.external.repository.ContextSourceRepository;
import net.java21.data2flow.core.external.repository.ContextSourceRepository.ContextSource;
import net.java21.data2flow.core.external.repository.ContextSourceRepository.UsageRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 공공 API 호출량과 일일 한도(DSC-06.05, BR-DSC-17, AT-DSC-09.4).
 * <ul>
 *   <li>날짜 경계는 KST(설정 {@code data2flow.core.external.quota-zone}). 한도는 소스 설정 {@code dailyQuota} 또는 유형 기본값</li>
 *   <li>그날 호출이 한도의 80%에 처음 닿으면 WARNING 시스템 알람 {@code system:API_QUOTA:{sourceId}} 1건(하루 1회, {@code warned_at})</li>
 *   <li>100%에 닿으면 다음 날 00:00(KST)까지 호출하지 않는다: {@link #requireAllowed}가 429 {@code EXTERNAL_API_QUOTA_EXCEEDED} + Retry-After</li>
 *   <li>다음 날 첫 기록에서 전날 경고 알람을 자동 해제한다</li>
 * </ul>
 * 기록은 core가 직접 부른 호출(공휴일 어댑터·[지금 갱신] 확인)과 ingress가 알려 주는 호출(내부 API, 기상청·에어코리아 정기 수집)을 함께 센다.
 */
@Service
public class ApiUsageService {

    public static final String QUOTA_ALARM = "API_QUOTA";

    private final ContextSourceRepository repository;
    private final AlarmService alarms;
    private final ExternalProperties properties;
    private final Clock clock;

    public ApiUsageService(ContextSourceRepository repository, AlarmService alarms, ExternalProperties properties, Clock clock) {
        this.repository = repository;
        this.alarms = alarms;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 오늘의 사용량.
     *
     * @param warning   한도의 80% 이상
     * @param exhausted 한도에 닿음(다음 날까지 호출 중지)
     * @param resumeAt  exhausted면 다시 호출할 수 있는 시각(다음 날 00:00 KST)
     */
    public record Usage(LocalDate day, int calls, int failures, Integer quota, boolean warning, boolean exhausted, Instant resumeAt) {
    }

    /** 소스의 일일 한도(설정 dailyQuota → 유형 기본값). 한도가 없는 유형(iCal)은 null */
    public Integer quotaOf(ContextSource s) {
        if (s.connection().path("dailyQuota").isIntegralNumber()) {
            return s.connection().get("dailyQuota").asInt();
        }
        return switch (s.type()) {
            case "KMA_WEATHER" -> properties.kmaDailyQuota();
            case "AIRKOREA" -> properties.airkoreaDailyQuota();
            case "HOLIDAY" -> properties.holidayDailyQuota();
            default -> null;
        };
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), properties.zone());
    }

    /** 호출 결과를 더한다. 80%를 처음 넘으면 경고 알람 1건 */
    @Transactional
    public Usage record(ContextSource s, int calls, int failures) {
        Instant now = clock.instant();
        LocalDate day = today();
        Integer quota = quotaOf(s);
        boolean firstOfDay = repository.findUsage(s.organizationId(), s.id(), day).isEmpty();
        UsageRow row = repository.addUsage(s.organizationId(), s.id(), day, Math.max(0, calls), Math.max(0, failures), quota);
        String key = AlarmKeys.system(QUOTA_ALARM, Long.toString(s.id()));
        if (firstOfDay) {
            alarms.clearByKey(s.organizationId(), key, null, now, AlarmClearReason.AUTO, "SYSTEM", null);
        }
        Usage usage = toUsage(row, quota, day);
        if (usage.warning() && repository.markWarned(s.organizationId(), s.id(), day, now)) {
            alarms.raise(new AlarmService.Raise(s.organizationId(), key, AlarmSourceType.SYSTEM, null, null, null, AlarmSeverity.WARNING,
                    "외부 API 호출량 80% 도달: " + s.name() + " (" + row.calls() + "/" + quota + ")", null, s.siteId(), null,
                    (double) row.calls(), quota * 0.8, null, now, "SYSTEM", false, false));
        }
        return usage;
    }

    @Transactional(readOnly = true)
    public Usage current(ContextSource s) {
        LocalDate day = today();
        Integer quota = quotaOf(s);
        return repository.findUsage(s.organizationId(), s.id(), day).map(r -> toUsage(r, quota, day))
                .orElseGet(() -> new Usage(day, 0, 0, quota, false, false, null));
    }

    /** 한도에 닿았으면 429(Retry-After: 다음 날 00:00 KST까지 초) */
    public void requireAllowed(ContextSource s) {
        Usage u = current(s);
        if (u.exhausted()) {
            long seconds = Math.max(1, Duration.between(clock.instant(), u.resumeAt()).toSeconds());
            throw new BusinessException(ExternalErrorCode.EXTERNAL_API_QUOTA_EXCEEDED).withHeader("Retry-After", Long.toString(seconds));
        }
    }

    Usage toUsage(UsageRow row, Integer quota, LocalDate day) {
        boolean warning = quota != null && quota > 0 && row.calls() * 10L >= quota * 8L;
        boolean exhausted = quota != null && quota > 0 && row.calls() >= quota;
        Instant resume = exhausted ? day.plusDays(1).atStartOfDay(properties.zone()).toInstant() : null;
        return new Usage(day, row.calls(), row.failures(), quota, warning, exhausted, resume);
    }
}
