package net.java21.data2flow.core.space.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.analytics.service.SiteComfortScores;
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
 * 열린 알람 수는 알람 칸 회귀(M7) 전까지 0이다. 쾌적도 점수는 그 사이트의 쾌적도 분석 최근 성공 결과(DEV-10.02, M6, {@link SiteComfortScores}),
 * 없으면 null이다.
 */
@Service
public class SiteService {

    private final SpaceRepository spaces;
    private final RoleChecker roleChecker;
    private final SiteComfortScores comfort;

    public SiteService(SpaceRepository spaces, RoleChecker roleChecker, SiteComfortScores comfort) {
        this.spaces = spaces;
        this.roleChecker = roleChecker;
        this.comfort = comfort;
    }

    @Transactional(readOnly = true)
    public List<SiteSummaryResponse> summary() {
        roleChecker.require(Permission.DEV_READ);
        long org = roleChecker.currentUser().organizationId();
        SpaceScope scope = roleChecker.spaceScope();
        List<Space> all = spaces.listActive(org);
        List<Space> accessible = all.stream().filter(s -> scope.unrestricted() || scope.allowedSpaceIds().contains(s.id())).toList();
        Map<Long, DeviceCount> counts = spaces.countDevicesBySpace(org);
        Map<Long, List<Long>> siteSpaces = new java.util.LinkedHashMap<>();
        for (Space site : all) {
            if (site.type() == SpaceType.SITE) {
                siteSpaces.put(site.id(), all.stream().filter(site::isAncestorOrSelfOf).map(Space::id).toList());
            }
        }
        Map<Long, Integer> scores = comfort.scores(org, scope, siteSpaces);
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
                    offline, 0, scores.get(site.id())));
        }
        return result;
    }
}
