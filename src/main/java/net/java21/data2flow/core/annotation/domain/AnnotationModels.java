package net.java21.data2flow.core.annotation.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 시계열 주석(TSD-01.04, BR-TSD-23, spec/detail/TSD/domain-model.md §2.8). 저장 종류는 DDL 값(MANUAL 포함)이고, API는 수동 주석을
 * {@code USER}로 쓴다(design/api/TSD-api.md 응답, 웹 explore 화면). 그래서 저장 MANUAL ↔ API USER로 바꾼다.
 */
public final class AnnotationModels {

    /** 시스템이 원천 이벤트로 만드는 종류(사람이 지울 수 없음) */
    public static final Set<String> SYSTEM_TYPES = Set.of("ALARM", "OFFLINE", "SCRIPT_ERROR", "ANOMALY", "WORK_ORDER", "REPLACEMENT",
            "CALENDAR");
    public static final String MANUAL = "MANUAL";
    public static final String API_MANUAL = "USER";
    /** 끝 시각이 없으면 "아직 진행 중"인 구간 종류(원천이 해제되면 time_to를 채운다). 그 밖의 종류는 time_to가 없으면 시점 주석 */
    public static final Set<String> OPEN_ENDED_TYPES = Set.of("ALARM", "OFFLINE", "SCRIPT_ERROR");
    public static final int MAX_TITLE = 150;

    private AnnotationModels() {
    }

    /** API 값(USER 포함, 대소문자 무시) → 저장 값. 모르는 값이면 빈 값 */
    public static Optional<String> storedType(String apiType) {
        if (apiType == null) {
            return Optional.empty();
        }
        String t = apiType.strip().toUpperCase(Locale.ROOT);
        if (API_MANUAL.equals(t) || MANUAL.equals(t)) {
            return Optional.of(MANUAL);
        }
        return SYSTEM_TYPES.contains(t) ? Optional.of(t) : Optional.empty();
    }

    /** 저장 값 → API 값 */
    public static String apiType(String storedType) {
        return MANUAL.equals(storedType) ? API_MANUAL : storedType;
    }

    /** 주석 오류 코드(TSD domain-model 오류 표). 문구는 {@code i18n/annotation*.properties} */
    public enum AnnotationErrorCode implements ErrorCode {
        ANNOTATION_NOT_FOUND(404),
        ANNOTATION_FORBIDDEN(403);

        private final int httpStatus;

        AnnotationErrorCode(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        @Override
        public String code() {
            return name();
        }

        @Override
        public int httpStatus() {
            return httpStatus;
        }
    }
}
