package net.java21.data2flow.core.annotation;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.annotation.domain.AnnotationModels;
import net.java21.data2flow.core.annotation.domain.AnnotationModels.AnnotationErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TSD-01.04 주석 종류 변환(저장 MANUAL ↔ API USER)과 조회 도우미 */
class AnnotationModelsTest {

    @Test
    @DisplayName("[TSD-01.04] API 종류 USER·MANUAL → 저장 MANUAL, 시스템 종류는 그대로, 모르는 값은 빈 값. 오류 코드 HTTP 상태")
    void types() {
        assertThat(AnnotationModels.storedType("user")).contains("MANUAL");
        assertThat(AnnotationModels.storedType("MANUAL")).contains("MANUAL");
        assertThat(AnnotationModels.storedType(" offline ")).contains("OFFLINE");
        assertThat(AnnotationModels.storedType("PARTY")).isEmpty();
        assertThat(AnnotationModels.storedType(null)).isEmpty();
        assertThat(AnnotationModels.apiType("MANUAL")).isEqualTo("USER");
        assertThat(AnnotationModels.apiType("ALARM")).isEqualTo("ALARM");
        assertThat(AnnotationErrorCode.ANNOTATION_FORBIDDEN.httpStatus()).isEqualTo(403);
        assertThat(AnnotationErrorCode.ANNOTATION_NOT_FOUND.code()).isEqualTo("ANNOTATION_NOT_FOUND");
    }

    @Test
    @DisplayName("[TSD-01.04] types 쉼표·반복 목록 합치기, 빈 목록은 전체, 공간 경로 /1/4/9/ → [1,4,9]")
    @SuppressWarnings("unchecked")
    void helpers() throws Exception {
        Class<?> service = Class.forName("net.java21.data2flow.core.annotation.service.AnnotationService");
        Method types = service.getDeclaredMethod("types", List.class);
        types.setAccessible(true);
        assertThat((List<String>) types.invoke(null, List.of("ALARM,USER", "ALARM"))).containsExactly("ALARM", "MANUAL");
        assertThat(types.invoke(null, List.of(" , "))).isNull();
        assertThat(types.invoke(null, (Object) null)).isNull();
        assertThatThrownBy(() -> types.invoke(null, List.of("NOPE"))).hasCauseInstanceOf(BusinessException.class);
        Method pathIds = service.getDeclaredMethod("pathIds", String.class);
        pathIds.setAccessible(true);
        assertThat((List<Long>) pathIds.invoke(null, "/1/4/9/")).containsExactly(1L, 4L, 9L);
        assertThat((List<Long>) pathIds.invoke(null, (Object) null)).isEmpty();
    }
}
