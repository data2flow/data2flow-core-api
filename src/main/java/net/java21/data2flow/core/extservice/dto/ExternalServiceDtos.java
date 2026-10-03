package net.java21.data2flow.core.extservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import net.java21.data2flow.contracts.secret.Secret;

import java.time.Instant;
import java.util.Map;

/** 외부 서비스 설정 DTO(API-OPS-41·42). 비밀값은 쓰기 전용 {@link Secret}이라 응답·로그에 나가지 않는다(NFR-03.02) */
public final class ExternalServiceDtos {

    private ExternalServiceDtos() {
    }

    /**
     * @param secret 비밀번호·API 키. 비우거나 생략하면 기존 값을 유지한다(BR-OPS-05)
     */
    public record UpdateExternalServiceRequest(@NotBlank @Size(max = 40) String provider, Map<String, Object> settings,
                                               Secret secret, @NotNull Boolean enabled, @PositiveOrZero Integer baseVersion) {
    }

    /** 연결 테스트. 값이 없으면 저장된 설정으로 한다 */
    public record TestExternalServiceRequest(String provider, Map<String, Object> settings, Secret secret) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExternalServiceResponse(String kind, String provider, Map<String, Object> settings, boolean secretConfigured,
                                          boolean enabled, Instant lastTestAt, String lastTestResult, int version) {
    }

    public record TestResultResponse(boolean ok, String detail) {
    }
}
