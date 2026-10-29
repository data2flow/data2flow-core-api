package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.domain.EnergyRules;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.BillRequest;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.BillView;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.UserRef;
import net.java21.data2flow.core.energy.repository.EnergyBillRepository;
import net.java21.data2flow.core.energy.repository.EnergyBillRepository.BillRow;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.SpaceRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

import static net.java21.data2flow.core.energy.service.EnergyAccess.invalid;

/**
 * 전기 고지서 API-ENE-05(ENE-01.01, UC-ENE-02, AT-ENE-02.2).
 *
 * <ul>
 *   <li>조회 ENE_READ, 입력·삭제 ENE_ADMIN. 범위는 사이트·건물이고 범위 밖 공간은 404 SPACE_NOT_FOUND</li>
 *   <li>같은 달이 이미 있으면 {@code overwrite=true}일 때만 덮어쓴다(아니면 409 ENE_BILL_DUPLICATE)</li>
 *   <li>사용량 kWh 필수(0 초과, 소수 첫째 자리), 금액 선택(0 이상 원 단위)</li>
 *   <li>같은 달 측정 합계(meteredKwh)와 차이(diffRatio)는 일 집계(analytics energy_daily, ENE-01.02)가 생긴 뒤 채운다. 지금은 null이다</li>
 * </ul>
 */
@Service
public class EnergyBillService {

    private static final BigDecimal MAX_KWH = new BigDecimal("99999999999.9");
    private static final BigDecimal MAX_KRW = new BigDecimal("99999999999999");

    private final EnergyBillRepository bills;
    private final EnergyConfigRepository config;
    private final EnergyAccess access;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public EnergyBillService(EnergyBillRepository bills, EnergyConfigRepository config, EnergyAccess access, RoleChecker roleChecker, Audits audits,
                             Clock clock) {
        this.bills = bills;
        this.config = config;
        this.access = access;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    /** 한 해 고지서(공간·달 순). 해가 없으면 조직 시간대의 올해 */
    @Transactional(readOnly = true)
    public ListApiResponse<BillView> list(String spaceId, Integer year, Integer page, Integer size) {
        roleChecker.require(Permission.ENE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Long space = EnergyAccess.id(spaceId, EnergyErrorCode.SPACE_NOT_FOUND);
        if (space != null) {
            access.space(Permission.ENE_READ, orgId, space);
        }
        int y = year != null ? year : LocalDate.now(clock.withZone(ZoneId.of(config.timezone(orgId)))).getYear();
        if (y < 2000 || y > 2100) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "year", "Range");
        }
        var scope = roleChecker.spaceScope();
        List<BillRow> rows = bills.list(orgId, space, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 1)).stream().filter(b -> scope.includes(b.spaceId()))
                .toList();
        PageParams params = PageParams.of(page, size);
        int start = (int) Math.min(params.offset(), rows.size());
        return ListApiResponse.of(params, rows.subList(start, Math.min(start + params.size(), rows.size())).stream().map(EnergyBillService::view).toList(),
                rows.size());
    }

    /** 입력하거나 덮어쓴다 */
    @Transactional
    public BillView put(long spaceId, String month, BillRequest r) {
        roleChecker.require(Permission.ENE_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        SpaceRow space = access.space(Permission.ENE_ADMIN, orgId, spaceId);
        if (!EnergyRules.BILL_SPACE_TYPES.contains(space.type())) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "spaceId", "SiteOrBuilding");
        }
        LocalDate first = month(month);
        if (r == null || r.kwh() == null || r.kwh().signum() <= 0 || r.kwh().stripTrailingZeros().scale() > 1 || r.kwh().compareTo(MAX_KWH) > 0) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "kwh", r == null || r.kwh() == null ? "NotNull" : "Range");
        }
        if (r.amountKrw() != null && (r.amountKrw().signum() < 0 || r.amountKrw().stripTrailingZeros().scale() > 0 || r.amountKrw().compareTo(MAX_KRW) > 0)) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "amountKrw", "Range");
        }
        String attachment = r.attachmentObjectKey() == null || r.attachmentObjectKey().isBlank() ? null : r.attachmentObjectKey().strip();
        if (attachment != null && attachment.length() > 300) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "attachmentObjectKey", "Size");
        }
        boolean exists = bills.find(orgId, spaceId, first).isPresent();
        if (exists && !Boolean.TRUE.equals(r.overwrite())) {
            throw new BusinessException(EnergyErrorCode.ENE_BILL_DUPLICATE);
        }
        bills.upsert(orgId, spaceId, first, r.kwh(), r.amountKrw(), attachment, user.userId(), clock.instant());
        audit(user, spaceId, first, exists ? "UPDATED" : "CREATED");
        return view(bills.find(orgId, spaceId, first).orElseThrow());
    }

    @Transactional
    public void delete(long spaceId, String month) {
        roleChecker.require(Permission.ENE_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        access.space(Permission.ENE_ADMIN, orgId, spaceId);
        LocalDate first = month(month);
        if (bills.delete(orgId, spaceId, first) == 0) {
            throw new BusinessException(EnergyErrorCode.ENE_BILL_NOT_FOUND);
        }
        audit(user, spaceId, first, "DELETED");
    }

    private void audit(CurrentUser user, long spaceId, LocalDate month, String change) {
        audits.record(audits.event(user.organizationId(), EnergyAuditCodes.ENE_BILL_CHANGED).actor(user)
                .target("ENERGY_BILL", spaceId + "/" + YearMonth.from(month)).detail("change", change));
    }

    /** 경로의 달 {@code YYYY-MM} → 그 달 1일 */
    static LocalDate month(String raw) {
        try {
            return YearMonth.parse(raw == null ? "" : raw.strip()).atDay(1);
        } catch (DateTimeParseException ex) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "month", "Pattern");
        }
    }

    static BillView view(BillRow b) {
        BigDecimal metered = null;
        return new BillView(Long.toString(b.id()), Long.toString(b.spaceId()), YearMonth.from(b.month()).toString(), b.kwh(), b.amountKrw(),
                b.attachmentObjectKey(), metered, EnergyRules.diffRatio(b.kwh(), metered), new UserRef(Long.toString(b.createdBy()), b.createdByName()),
                b.updatedAt());
    }
}
