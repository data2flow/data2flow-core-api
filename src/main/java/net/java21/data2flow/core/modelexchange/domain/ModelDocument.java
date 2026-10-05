package net.java21.data2flow.core.modelexchange.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 모델 정의 내보내기·가져오기 문서(DEV-03.04, API-DEV-44·45 {@code format=data2flow}). DTDL(v3)과도 이 모양을 거쳐 바꾼다.
 * 스크립트는 코드만 담고, 가져오면 DRAFT로 만든다(AT-DEV-09.4).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelDocument(int formatVersion, String format, Model model, List<MetricDef> metrics, List<Capability> capabilities,
                            JsonNode attributeSchema, List<Script> scripts) {

    public static final int FORMAT_VERSION = 1;

    public ModelDocument {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        scripts = scripts == null ? List.of() : List.copyOf(scripts);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Model(String code, String vendor, String name, String protocol, String kind, Integer defaultIntervalSec,
                        Double defaultOfflineMultiplier, String description) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetricDef(String key, String displayName, String unit, String valueType, Double validMin, Double validMax,
                            Integer precision, String aggDefault, Boolean stateType, Boolean required) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capability(String capability, JsonNode constraints) {
    }

    public record Script(String kind, String name, String code) {
    }

    /** 가져올 때 옮기지 못한 항목(경로, 종류, 이유) */
    public record Unmapped(String path, String type, String reason) {
    }
}
