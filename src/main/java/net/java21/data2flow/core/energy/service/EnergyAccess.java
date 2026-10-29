package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.ErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.SpaceRow;
import org.springframework.stereotype.Component;

import java.util.List;

/** 에너지 설정 서비스가 함께 쓰는 권한·공간·입력 검사 */
@Component
public class EnergyAccess {

    private final RoleChecker roleChecker;
    private final EnergyConfigRepository config;

    public EnergyAccess(RoleChecker roleChecker, EnergyConfigRepository config) {
        this.roleChecker = roleChecker;
        this.config = config;
    }

    /**
     * 조직 전체 설정(조직 단가·배출계수) 변경: ENE_ADMIN에 더해 공간 범위 제한이 없어야 한다. 범위가 제한된 관리자가 범위 밖 공간의 비용·탄소를 바꾸지
     * 못하게 한다(403)
     */
    public void requireOrgWide(Permission permission) {
        roleChecker.require(permission);
        if (!roleChecker.spaceScope().unrestricted()) {
            throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
        }
    }

    /** 조직의 ACTIVE 공간이고 범위 안이며 권한이 있다. 아니면 404 SPACE_NOT_FOUND 또는 403 */
    public SpaceRow space(Permission permission, long orgId, long spaceId) {
        SpaceRow space = config.space(orgId, spaceId).orElseThrow(() -> new BusinessException(EnergyErrorCode.SPACE_NOT_FOUND));
        roleChecker.require(permission, spaceId, EnergyErrorCode.SPACE_NOT_FOUND);
        return space;
    }

    /** 문자열 ID. 비었으면 null, 숫자가 아니면 {@code notFound} */
    public static Long id(String raw, ErrorCode notFound) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException ex) {
            throw new BusinessException(notFound);
        }
    }

    public static BusinessException invalid(ErrorCode code, String field, String reason) {
        return new BusinessException(code, List.of(new FieldErrorDetail(field, reason, null)));
    }
}
