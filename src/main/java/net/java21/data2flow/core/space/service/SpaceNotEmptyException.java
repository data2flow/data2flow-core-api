package net.java21.data2flow.core.space.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.space.domain.SpaceErrorCode;
import net.java21.data2flow.core.space.dto.SpaceDtos.Blockers;

/** 409 SPACE_NOT_EMPTY + 응답 {@code response.blockers}(API-DEV-05, AT-DEV-01.4) */
public class SpaceNotEmptyException extends BusinessException {

    private final transient Blockers blockers;

    public SpaceNotEmptyException(Blockers blockers) {
        super(SpaceErrorCode.SPACE_NOT_EMPTY);
        this.blockers = blockers;
    }

    public Blockers blockers() {
        return blockers;
    }
}
