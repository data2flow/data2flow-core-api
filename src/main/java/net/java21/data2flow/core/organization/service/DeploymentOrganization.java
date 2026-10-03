package net.java21.data2flow.core.organization.service;

import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.organization.domain.OrganizationModels.Organization;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * 이 배포가 맡는 조직(ADR-004 v1 단일 조직 + ADR-030 staging 전용 조직).
 *
 * <p>staging과 prod는 DB {@code data2flow} 하나를 함께 쓰고 staging은 전용 조직을 쓴다(ADR-030). 그래서 "조직이 하나"라는 가정으로
 * 조직을 찾으면 둘이 나와 아무것도 정하지 못한다. 배포 설정 {@code data2flow.core.organization-code}
 * ({@code DATA2FLOW_ORGANIZATION_CODE})가 있으면 그 코드의 ACTIVE 조직을 쓰고, 없으면 ACTIVE 조직이 정확히 하나일 때 그 조직을 쓴다.
 *
 * <ul>
 *   <li>{@link #current()}: 요청에 조직이 없는 공개 경로(가입 신청·재설정·로그인 실패 기록)의 조직</li>
 *   <li>{@link #restriction()}: 모든 조직을 도는 작업(아웃박스 릴레이, 조직 전체 내부 조회)을 이 배포의 조직으로 좁히는 조건.
 *       설정이 없으면 빈 값(모든 조직)이고, 설정한 코드의 조직이 없으면 어떤 행에도 맞지 않는 {@code -1}이다</li>
 * </ul>
 */
@Component
public class DeploymentOrganization {

    /** 설정한 조직이 아직 없을 때의 조건 값(어떤 조직 ID와도 같지 않다) */
    public static final long NO_ORGANIZATION = -1L;

    private final OrganizationRepository organizations;
    private final String code;

    public DeploymentOrganization(OrganizationRepository organizations, CoreProperties properties) {
        this.organizations = organizations;
        this.code = properties.organizationCode();
    }

    /** 배포 설정으로 조직을 정했는가 */
    public boolean configured() {
        return code != null;
    }

    /** 설정한 조직 코드. 없으면 null */
    public String code() {
        return code;
    }

    /** 이 배포의 조직. 설정이 있으면 그 코드의 ACTIVE 조직, 없으면 ACTIVE 조직이 하나일 때만 */
    public Optional<Organization> current() {
        if (code == null) {
            return organizations.findSingleActive();
        }
        return organizations.findActiveByCode(code);
    }

    /** 모든 조직을 도는 작업의 조직 조건. 설정이 없으면 빈 값 */
    public OptionalLong restriction() {
        if (code == null) {
            return OptionalLong.empty();
        }
        // 캐시하지 않는다: 조직 상태가 바뀌면(비활성) 바로 반영한다. 기본 키 조회 하나라 비용이 작다
        return OptionalLong.of(organizations.findActiveByCode(code).map(Organization::id).orElse(NO_ORGANIZATION));
    }

    /** 이 조직이 이 배포의 범위 안인가(설정이 없으면 모든 조직) */
    public boolean includes(long organizationId) {
        OptionalLong r = restriction();
        return r.isEmpty() || r.getAsLong() == organizationId;
    }
}
