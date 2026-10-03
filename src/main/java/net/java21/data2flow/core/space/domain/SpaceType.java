package net.java21.data2flow.core.space.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * 공간 종류와 순서(BR-DEV-01·02). 큰 단위부터 SITE → BUILDING → FLOOR → ROOM → ZONE.
 * 하위는 상위보다 큰 단위일 수 없고(같은 단위·중간 생략은 허용), SITE는 최상위에만 둔다.
 */
public enum SpaceType {
    SITE, BUILDING, FLOOR, ROOM, ZONE;

    /** 이 종류를 {@code parent} 아래(null이면 최상위)에 둘 수 있는가 */
    public boolean allowedUnder(SpaceType parent) {
        if (parent == null) {
            return this == SITE;
        }
        return this != SITE && ordinal() >= parent.ordinal();
    }

    public static Optional<SpaceType> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
