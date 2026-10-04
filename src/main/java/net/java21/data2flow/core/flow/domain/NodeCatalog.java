package net.java21.data2flow.core.flow.domain;

import net.java21.data2flow.contracts.flow.FlowNodeType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 노드 카탈로그(API-FLW-30, FLW-01.02). M3 기본 노드 16종 + M4 9종(알람·알림·변화율·무수신·복합·이상·Sink·상태 머신·알람 트리거)를 {@code classpath:flow/node-types.json}에
 * 둔다(contracts {@code flow-node-type.v1.json} 모양). flow-engine 노드 레지스트리는 같은 종류·포트·설정 필드를 구현해야 하고, 저장·적용
 * 검증과 편집기 팔레트·설정 폼이 이 목록을 쓴다.
 */
public final class NodeCatalog {

    public static final String RESOURCE = "flow/node-types.json";

    private final Map<String, FlowNodeType> byType;

    private NodeCatalog(List<FlowNodeType> types) {
        Map<String, FlowNodeType> map = new LinkedHashMap<>();
        types.forEach(t -> map.put(t.type(), t));
        this.byType = map;
    }

    public static NodeCatalog load(JsonMapper json) {
        try (InputStream in = NodeCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " 이 없습니다");
            }
            return new NodeCatalog(Arrays.asList(json.readValue(in, FlowNodeType[].class)));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public List<FlowNodeType> all() {
        return List.copyOf(byType.values());
    }

    public Optional<FlowNodeType> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    /** 제어·장면 노드(FLOW_DEPLOY_CONTROL 필요, BR-FLW-08) */
    public boolean isControl(String type) {
        FlowNodeType t = byType.get(type);
        return "action.control".equals(type) || "action.scene".equals(type)
                || (t != null && t.permissions().contains("FLOW_DEPLOY_CONTROL"));
    }

    public boolean isTrigger(String type) {
        FlowNodeType t = byType.get(type);
        return t != null && "trigger".equals(t.category());
    }
}
