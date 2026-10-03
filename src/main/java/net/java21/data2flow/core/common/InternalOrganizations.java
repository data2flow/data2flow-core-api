package net.java21.data2flow.core.common;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.core.organization.domain.OrganizationModels.Organization;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 내부 API의 조직 정하기(ADR-021·030): 쿼리 {@code organizationId} → 호출자가 넘긴 {@code X-ORG-ID} → 이 배포의 조직 순서.
 * 배포 범위 밖 조직이면 404(staging이 운영 조직을 읽지 않게).
 */
@Component
public class InternalOrganizations {

    private final DeploymentOrganization deployment;
    private final net.java21.data2flow.core.organization.repository.OrganizationRepository organizations;

    public InternalOrganizations(DeploymentOrganization deployment,
                                 net.java21.data2flow.core.organization.repository.OrganizationRepository organizations) {
        this.deployment = deployment;
        this.organizations = organizations;
    }

    /** 이 배포가 맡는 ACTIVE 조직들(조직 코드를 정했으면 그 조직, 아니면 ACTIVE 조직 전체) */
    public java.util.List<Long> deploymentOrganizations() {
        var restriction = deployment.restriction();
        if (restriction.isPresent()) {
            return java.util.List.of(restriction.getAsLong());
        }
        return organizations.listActiveIds();
    }

    public long resolve(Long organizationId) {
        Long org = organizationId;
        if (org == null) {
            org = CurrentUserHolder.find().map(u -> u.organizationId()).orElse(null);
        }
        if (org == null) {
            org = deployment.current().map(Organization::id)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST,
                            List.of(new FieldErrorDetail("organizationId", "NotNull", null))));
        }
        if (!deployment.includes(org)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return org;
    }
}
