package net.java21.data2flow.core.common;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;

/**
 * 공개 경로와 관리자 경로가 같은 URL을 쓰는 곳(예: {@code /core/signup-requests}의 공개 POST와 관리자 GET)에서
 * 신원 필터가 선택 모드로 통과시킨 요청에 신원이 꼭 있어야 할 때 쓴다. 없으면 401 AUTH_TOKEN_INVALID.
 */
public final class Identity {

    private Identity() {
    }

    public static CurrentUser require() {
        return CurrentUserHolder.find().orElseThrow(() -> new BusinessException(CommonErrorCode.AUTH_TOKEN_INVALID)
                .withHeader("WWW-Authenticate", "Bearer error=\"invalid_token\""));
    }
}
