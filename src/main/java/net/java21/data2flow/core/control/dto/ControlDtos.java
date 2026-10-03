package net.java21.data2flow.core.control.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.contracts.capability.CapabilityAttribute;
import net.java21.data2flow.contracts.capability.CapabilityCommand;
import net.java21.data2flow.contracts.capability.ExpectedEffect;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 제어(ACT) 외부·내부 API 응답 모양(ACT-api 부록 A). ID는 문자열 */
public final class ControlDtos {

    private ControlDtos() {
    }

    /** API-ACT-25 목록 항목 */
    public record CapabilitySummary(String name, int version, boolean standard, String matterCluster, int attributeCount,
                                    int commandCount) {
    }

    /** API-ACT-25 상세 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CapabilityResponse(String name, int version, boolean standard, String matterCluster,
                                     List<CapabilityAttribute> attributes, List<CapabilityCommand> commands,
                                     List<ExpectedEffect> expectedEffects, Instant updatedAt) {
    }

    public record UserRef(String userId, String name) {
    }

    /** API-ACT-17 조직 제어 설정 */
    public record ControlSettingsResponse(JsonNode absoluteLimits, int manualOverrideMinutes, int minIntervalSec, JsonNode oscillation,
                                          int defaultValiditySec, boolean scheduleRespectsManualOverride,
                                          boolean requireApprovalForControlNodes, int version, UserRef updatedBy, Instant updatedAt) {
    }

    /** API-ACT-30 목록 항목 */
    public record DriverSummary(String driverId, String name, String type, String status, long deviceCount, Instant updatedAt) {
    }

    /** API-ACT-30 상세(비밀값은 hasSecret만) */
    public record DriverResponse(String driverId, String name, String type, JsonNode config, boolean hasSecret, int pollingSec,
                                 int ackTimeoutSec, int applyTimeoutSec, JsonNode retry, JsonNode circuit, String status,
                                 List<String> capabilities, int version, Instant createdAt, Instant updatedAt) {
    }

    /** API-ACT-31 모델 연결 결과 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelDriverResponse(String modelId, String driverId, String encoderScriptId, List<String> warnings) {
    }

    /**
     * API-ACT-40 제어 프로필(ACT-api §5.4, ADR-043). action 제어 창구가 30초까지 캐시하고 {@code data2flow.config}로 지운다.
     * {@code capabilities}는 기능 이름 → {constraints, protection?, reapplyOnReconnect, classADownlink}. 문서 필드 밖으로
     * {@code sandbox}(기기 공간 트리가 샌드박스)와 {@code controllable}을 더 준다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ControlProfileResponse(String deviceId, String organizationId, String spaceId, String name, String externalId,
                                         boolean virtual, String status, String modelId, Map<String, ProfileCapability> capabilities,
                                         ProfileDriver driver, ProfileSettings settings, boolean sandbox, boolean controllable) {
    }

    /** 기능 하나의 모델 제약(속성 → {min,max,enum})·보호·재연결 시 재적용·Class A 다운링크 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileCapability(Map<String, Object> constraints, JsonNode protection, boolean reapplyOnReconnect,
                                    boolean classADownlink) {
    }

    /** 연결된 드라이버(비밀값 제외) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileDriver(String driverId, String type, JsonNode config, int ackTimeoutSec, int applyTimeoutSec, JsonNode retry) {
    }

    /** 조직 제어 설정(절대 한계 등) */
    public record ProfileSettings(JsonNode absoluteLimits, int manualOverrideMinutes, int minIntervalSec, JsonNode oscillation,
                                  int defaultValiditySec, boolean scheduleRespectsManualOverride) {
    }

    /** API-ACT-42 조직 제어 설정(action 캐시) */
    public record InternalControlSettings(String organizationId, JsonNode absoluteLimits, int manualOverrideMinutes, int minIntervalSec,
                                          JsonNode oscillation, int defaultValiditySec, boolean scheduleRespectsManualOverride,
                                          int version) {
    }

    /** API-ACT-41 샌드박스 공간(하위 펼침, 배포 조직 범위) */
    public record SandboxSpaces(List<String> spaceIds) {
    }

    /** API-TSD-05 상태 구간 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StateInterval(Instant from, Instant to, Object value, String label, String capability, String attribute,
                                Map<String, Object> source) {
    }
}
