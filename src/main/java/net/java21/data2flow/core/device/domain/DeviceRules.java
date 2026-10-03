package net.java21.data2flow.core.device.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 기기 업무 규칙(spec/detail/DEV/domain-model.md §4): 외부 ID 정규화(BR-DEV-06), 태그(BR-DEV-11), 오프라인 기준 상속(BR-DEV-08),
 * ACTIVE 필수값(BR-DEV-05), 온보딩 판정(BR-DEV-26의 M2 근사).
 */
public final class DeviceRules {

    public static final String PENDING = "PENDING";
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
    public static final String DELETED = "DELETED";
    public static final Set<String> STATUSES = Set.of(PENDING, ACTIVE, INACTIVE);
    public static final Set<String> KINDS = Set.of("SENSOR", "ACTUATOR", "GATEWAY", "HYBRID");
    public static final Set<String> CONNECTIVITIES = Set.of("ONLINE", "OFFLINE", "UNKNOWN");

    /** BR-DEV-11 */
    public static final int MAX_TAGS = 20;
    public static final int MAX_TAG_LENGTH = 40;
    private static final Pattern TAG = Pattern.compile("^[\\p{L}\\p{N}_.:-]{1,40}$");

    /** BR-DEV-08 시스템 기본값: 주기 300초, 배수 3 */
    public static final int SYSTEM_INTERVAL_SEC = 300;
    public static final BigDecimal SYSTEM_MULTIPLIER = new BigDecimal("3.0");

    public static final int MAX_EXTERNAL_ID = 128;
    public static final int MAX_NAME = 100;

    private DeviceRules() {
    }

    /** BR-DEV-06: 소문자, 공백 제거 */
    public static String normalizeExternalId(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public static boolean validTag(String tag) {
        return tag != null && TAG.matcher(tag).matches();
    }

    /**
     * 태그 입력 정리: 앞뒤 공백 제거, 형식 검사(틀리면 400 INVALID_REQUEST, field=태그 경로), 대소문자 무시 중복 제거(처음 표기 유지).
     */
    public static List<String> cleanTags(Collection<String> raw, String field) {
        if (raw == null) {
            return List.of();
        }
        Map<String, String> byLower = new LinkedHashMap<>();
        List<FieldErrorDetail> errors = new ArrayList<>();
        int i = 0;
        for (String value : raw) {
            String tag = value == null ? "" : value.strip();
            if (!validTag(tag)) {
                errors.add(new FieldErrorDetail(field + "[" + i + "]", tag.length() > MAX_TAG_LENGTH ? "TAG_TOO_LONG" : "TAG_INVALID", null));
            } else {
                byLower.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
            }
            i++;
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        return List.copyOf(byLower.values());
    }

    /**
     * 기존 태그에 더하고 뺀 결과(BR-DEV-11). 대소문자만 다른 태그는 같은 태그이고 이미 있는 표기를 유지한다.
     * 결과가 20개를 넘으면 null(호출자가 DEVICE_TAG_LIMIT으로 처리).
     */
    public static List<String> mergeTags(List<String> existing, Collection<String> add, Collection<String> remove) {
        Map<String, String> byLower = new LinkedHashMap<>();
        for (String tag : existing) {
            byLower.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
        }
        if (remove != null) {
            for (String tag : remove) {
                byLower.remove(tag.toLowerCase(Locale.ROOT));
            }
        }
        if (add != null) {
            for (String tag : add) {
                byLower.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
            }
        }
        if (byLower.size() > MAX_TAGS) {
            return null;
        }
        return List.copyOf(byLower.values());
    }

    /**
     * BR-DEV-08 유효 오프라인 기준. 주기와 배수는 각각 기기 → 모델 → 시스템 순서로 찾는다.
     * {@code inheritedFrom}은 주기 값을 가져온 곳이다.
     */
    public static Effective effective(Integer deviceInterval, BigDecimal deviceMultiplier, Integer modelInterval,
                                      BigDecimal modelMultiplier) {
        int interval;
        String from;
        if (deviceInterval != null) {
            interval = deviceInterval;
            from = "DEVICE";
        } else if (modelInterval != null) {
            interval = modelInterval;
            from = "MODEL";
        } else {
            interval = SYSTEM_INTERVAL_SEC;
            from = "SYSTEM";
        }
        BigDecimal multiplier = deviceMultiplier != null ? deviceMultiplier
                : modelMultiplier != null ? modelMultiplier : SYSTEM_MULTIPLIER;
        return new Effective(interval, multiplier, from);
    }

    /** 유효 오프라인 기준 */
    public record Effective(int expectedIntervalSec, BigDecimal offlineMultiplier, String inheritedFrom) {

        /** 오프라인으로 보는 무수신 시간(초) = 주기 × 배수 */
        public long offlineAfterSec() {
            return offlineMultiplier.multiply(BigDecimal.valueOf(expectedIntervalSec)).longValue();
        }
    }

    /**
     * 온보딩 체크리스트(BR-DEV-26). M2 근사: 디코딩 정상은 첫 수신 여부로 보고(디코딩 실패율은 M4 DEV-09.01),
     * 규칙은 아직 없으므로(RUL M4) 규칙 적용은 false다. 센서만 규칙 적용이 완료 조건이다.
     */
    public static Onboarding onboarding(boolean firstData, boolean model, boolean space, String kind) {
        boolean decodeOk = firstData;
        boolean rulesApplied = false;
        boolean complete = firstData && model && space && decodeOk && (!"SENSOR".equals(kind) || rulesApplied);
        return new Onboarding(firstData, model, space, decodeOk, rulesApplied, complete);
    }

    public record Onboarding(boolean firstData, boolean model, boolean space, boolean decodeOk, boolean rulesApplied,
                             boolean complete) {
    }

    /** 설치 공간에서 자동으로 생기는 관계(DEV-01.05): 센서 MEASURES, 액추에이터 CONTROLS, 하이브리드 둘 다 */
    public static List<String> autoRelations(String kind) {
        return switch (kind == null ? "" : kind) {
            case "SENSOR" -> List.of("MEASURES");
            case "ACTUATOR" -> List.of("CONTROLS");
            case "HYBRID" -> List.of("MEASURES", "CONTROLS");
            default -> List.of();
        };
    }
}
