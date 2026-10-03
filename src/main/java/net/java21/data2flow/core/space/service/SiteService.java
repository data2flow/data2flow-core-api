package net.java21.data2flow.core.space.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceType;
import net.java21.data2flow.core.space.dto.SpaceDtos.SiteSummaryResponse;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import net.java21.data2flow.core.space.repository.SpaceRepository.DeviceCount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 사이트 요약(API-DEV-25, DEV-10.03 사이트 단위 권한). 공간 범위가 있는 사용자는 범위 안 공간이 하나라도 있는 사이트만 보고,
 * 기기·오프라인 수에도 범위 안 공간의 기기만 더한다(AT-DEV-22.1: 합계에 사이트 2 수치 미포함, IAM-04.06).
 * 열린 알람 수는 알람 기능(RUL, M4) 전까지 0, 쾌적도 점수는 분석 기능(ANA) 전까지 null이다.
 */
@Service
public class SiteService {

    private final SpaceRepository spaces;
    private final RoleChecker roleChecker;

    public SiteService(SpaceRepository spaces, RoleChecker roleChecker) {
        this.spaces = spaces;
        this.roleChecker = roleChecker;
    }

    @Transactional(readOnly = true)
    public List<SiteSummaryResponse> summary() {
        roleChecker.require(Permission.DEV_READ);
        long org = roleChecker.currentUser().organizationId();
        SpaceScope scope = roleChecker.spaceScope();
        List<Space> all = spaces.listActive(org);
        List<Space> accessible = all.stream().filter(s -> scope.unrestricted() || scope.allowedSpaceIds().contains(s.id())).toList();
        Map<Long, DeviceCount> counts = spaces.countDevicesBySpace(org);
        List<SiteSummaryResponse> result = new ArrayList<>();
        for (Space site : all) {
            if (site.type() != SpaceType.SITE) {
                continue;
            }
            List<Space> inSite = accessible.stream().filter(site::isAncestorOrSelfOf).toList();
            if (inSite.isEmpty()) {
                continue;
            }
            long devices = 0;
            long offline = 0;
            for (Space s : inSite) {
                DeviceCount c = counts.get(s.id());
                if (c != null) {
                    devices += c.devices();
                    offline += c.offline();
                }
            }
            result.add(new SiteSummaryResponse(Long.toString(site.id()), site.name(), site.latitude(), site.longitude(), devices,
                    offline, 0, null));
        }
        return result;
    }
}
