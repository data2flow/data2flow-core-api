package net.java21.data2flow.core.device.service;

import net.java21.data2flow.core.device.domain.References.SpaceRef;
import net.java21.data2flow.core.device.repository.DeviceReferenceRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * ChirpStack tags로 공간 추천(ING-03.03, AT-ING-03.5, TC-ING-052). {@code tags.location}과 이름이 같은 공간(앞뒤 공백·대소문자 무시)이
 * 정확히 하나면 추천하고, 둘 이상이면 모호하므로 추천하지 않는다. {@code tags.point}가 그 공간 바로 아래 공간 이름과 같으면 그 하위 공간을
 * 추천한다. 추천일 뿐 기기 공간(space_id)은 바꾸지 않는다(확정은 관리자).
 */
@Component
public class SpaceSuggestion {

    private final DeviceReferenceRepository refs;

    public SpaceSuggestion(DeviceReferenceRepository refs) {
        this.refs = refs;
    }

    public Long suggest(long organizationId, JsonNode sourceMeta) {
        JsonNode tags = sourceMeta == null ? null : sourceMeta.get("tags");
        if (tags == null || !tags.isObject()) {
            return null;
        }
        String location = text(tags, "location");
        if (location == null) {
            return null;
        }
        List<SpaceRef> matches = refs.findSpacesByName(organizationId, location);
        if (matches.size() != 1) {
            return null;
        }
        SpaceRef space = matches.get(0);
        String point = text(tags, "point");
        if (point != null) {
            List<SpaceRef> children = refs.findChildrenByName(organizationId, space.id(), point);
            if (children.size() == 1) {
                return children.get(0).id();
            }
        }
        return space.id();
    }

    private static String text(JsonNode tags, String field) {
        JsonNode v = tags.get(field);
        if (v == null || !v.isString() || v.stringValue().isBlank()) {
            return null;
        }
        return v.stringValue().strip();
    }
}
