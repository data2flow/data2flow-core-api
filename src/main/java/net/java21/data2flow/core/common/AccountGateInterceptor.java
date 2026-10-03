package net.java21.data2flow.core.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.account.repository.UserRepository.GateState;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 계정 관문(core가 확인, design/auth.md §3.2):
 * <ul>
 *   <li>BR-IAM-06: 임시 비밀번호 상태(must_change_password)에서는 내 정보 조회와 비밀번호 변경만 허용, 그 밖은 403 AUTH_PASSWORD_CHANGE_REQUIRED</li>
 *   <li>BR-IAM-25: 정책상 2단계 인증이 필요한 역할인데 아직 설정하지 않았으면 설정 API와 내 정보·비밀번호만 허용, 그 밖은 403 MFA_SETUP_REQUIRED</li>
 * </ul>
 * 사용자 신원이 있는 {@code /core/**} 웹 요청에만 적용한다(공개 경로·내부 API·장기 토큰 요청 제외).
 */
@Component
public class AccountGateInterceptor implements HandlerInterceptor {

    static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of("GET /core/accounts/me", "PUT /core/accounts/me/password");
    static final Set<String> MFA_SETUP_ALLOWED = Set.of("GET /core/accounts/me", "PUT /core/accounts/me/password",
            "POST /core/accounts/me/mfa/setup", "POST /core/accounts/me/mfa/confirm");

    private final UserRepository users;

    public AccountGateInterceptor(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CurrentUser user = CurrentUserHolder.find().orElse(null);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (user == null || user.viaAccessToken() || !path.startsWith("/core/")) {
            return true;
        }
        GateState state = users.findGateState(user.organizationId(), user.userId()).orElse(null);
        if (state == null) {
            return true;
        }
        String route = request.getMethod() + " " + path;
        if (state.mustChangePassword() && !PASSWORD_CHANGE_ALLOWED.contains(route)) {
            throw new BusinessException(CommonErrorCode.AUTH_PASSWORD_CHANGE_REQUIRED);
        }
        if (state.mfaSetupRequired() && !MFA_SETUP_ALLOWED.contains(route)) {
            throw new BusinessException(CoreErrorCode.MFA_SETUP_REQUIRED);
        }
        return true;
    }
}
