package net.java21.data2flow.core.apitoken.service;

import net.java21.data2flow.core.apitoken.domain.TokenUsability;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.VerifyResponse;
import net.java21.data2flow.core.apitoken.repository.ApiTokenRepository;
import net.java21.data2flow.core.apitoken.repository.ApiTokenRepository.TokenRow;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 장기 토큰 검증(API-IAM-46 {@code POST /internal/core/api-tokens/verify}, 호출: auth introspection). auth는 {@code data2flow_}로 시작하는
 * Bearer를 받으면 원문의 SHA-256 hex만 보낸다. 쓸 수 없으면(없음·폐기·만료·유예 끝·거절·대기·소유자 비활성·이 배포 밖 조직) 200
 * {@code active=false}. 쓸 수 있으면 소유자·범위·공간 범위·분당 한도({@code rateLimitPerMin}, gateway 호출 한도 IAM-05.04)를 주고
 * 마지막 사용 시각(1분 단위)·IP(넘긴 경우)를 남긴다(IAM-05.03).
 */
@Service
public class ApiTokenVerifyService {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final ApiTokenRepository tokens;
    private final DeploymentOrganization deployment;
    private final Clock clock;

    public ApiTokenVerifyService(ApiTokenRepository tokens, DeploymentOrganization deployment, Clock clock) {
        this.tokens = tokens;
        this.deployment = deployment;
        this.clock = clock;
    }

    @Transactional
    public VerifyResponse verify(String tokenHash, String ip) {
        if (tokenHash == null || !HASH.matcher(tokenHash).matches()) {
            return VerifyResponse.inactive();
        }
        TokenRow t = tokens.findByHash(tokenHash).orElse(null);
        Instant now = clock.instant();
        if (t == null || !deployment.includes(t.organizationId()) || !TokenUsability.usable(t, now)) {
            return VerifyResponse.inactive();
        }
        tokens.touch(t.organizationId(), t.id(), Pg.inetOrNull(ip), now);
        String owner = Long.toString(t.ownerId());
        return new VerifyResponse(true, t.ownerType(), owner, t.serviceAccount() ? null : owner, Long.toString(t.organizationId()),
                t.scopes(), t.spaceScope().stream().map(String::valueOf).toList(), t.kind(), Long.toString(t.id()), t.rateLimitPerMin());
    }
}
