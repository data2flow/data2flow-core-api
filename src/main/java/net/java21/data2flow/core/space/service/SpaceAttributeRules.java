package net.java21.data2flow.core.space.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.space.domain.KmaGrid;
import net.java21.data2flow.core.space.domain.SpaceType;
import net.java21.data2flow.core.space.repository.SpaceRepository.SpaceAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 공간 속성 검사(API-DEV-02·03, DEV-01.02·10.01, BR-DEV-02). 어긋난 필드를 모아 400 INVALID_REQUEST {@code errors[]}로 답한다.
 * <ul>
 *   <li>이름 1~100자(앞뒤 공백 제거), 코드 {@code [A-Za-z0-9_-]{1,50}}</li>
 *   <li>용도 CLASSROOM·OFFICE·MEETING·LAB·CORRIDOR·OTHER, 면적 &gt; 0(소수 2자리), 정원 ≥ 0</li>
 *   <li>시간대(IANA)·주소·위경도는 SITE만, SITE는 시간대 필수. 위경도는 둘 다 넣거나 둘 다 비우고, 있으면 기상청 격자를 계산</li>
 * </ul>
 */
final class SpaceAttributeRules {

    static final Set<String> USAGES = Set.of("CLASSROOM", "OFFICE", "MEETING", "LAB", "CORRIDOR", "OTHER");
    private static final BigDecimal MAX_AREA = new BigDecimal("99999999.99");

    private SpaceAttributeRules() {
    }

    /** 검사 전 값 */
    record Draft(String name, String code, Integer sortOrder, String usage, BigDecimal areaM2, Integer capacity, String timezone,
                 String address, BigDecimal latitude, BigDecimal longitude) {
    }

    static SpaceAttributes validate(SpaceType type, Draft d) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        String name = d.name() == null ? "" : d.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            errors.add(new FieldErrorDetail("name", "Size", "1~100"));
        }
        String code = blankToNull(d.code());
        if (code != null && !code.matches("^[A-Za-z0-9_-]{1,50}$")) {
            errors.add(new FieldErrorDetail("code", "Pattern", null));
        }
        String usage = blankToNull(d.usage());
        if (usage != null) {
            usage = usage.toUpperCase(Locale.ROOT);
            if (!USAGES.contains(usage)) {
                errors.add(new FieldErrorDetail("usage", "INVALID", null));
            }
        }
        BigDecimal area = d.areaM2();
        if (area != null) {
            if (area.signum() <= 0 || area.compareTo(MAX_AREA) > 0) {
                errors.add(new FieldErrorDetail("areaM2", "Range", "0~99999999.99"));
            } else {
                area = area.setScale(2, RoundingMode.HALF_UP);
            }
        }
        if (d.capacity() != null && d.capacity() < 0) {
            errors.add(new FieldErrorDetail("capacity", "Min", "0"));
        }
        boolean site = type == SpaceType.SITE;
        String timezone = blankToNull(d.timezone());
        String address = blankToNull(d.address());
        if (site) {
            if (timezone == null) {
                errors.add(new FieldErrorDetail("timezone", "NotBlank", null));
            } else if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
                errors.add(new FieldErrorDetail("timezone", "INVALID", null));
            }
            if (address != null && address.length() > 300) {
                errors.add(new FieldErrorDetail("address", "Size", "0~300"));
            }
        } else {
            onlySite(errors, "timezone", timezone);
            onlySite(errors, "address", address);
            onlySite(errors, "latitude", d.latitude());
            onlySite(errors, "longitude", d.longitude());
        }
        BigDecimal lat = d.latitude();
        BigDecimal lon = d.longitude();
        if (site && (lat == null) != (lon == null)) {
            errors.add(new FieldErrorDetail(lat == null ? "latitude" : "longitude", "NotNull", null));
        }
        if (lat != null && (lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0)) {
            errors.add(new FieldErrorDetail("latitude", "Range", "-90~90"));
        }
        if (lon != null && (lon.compareTo(BigDecimal.valueOf(-180)) < 0 || lon.compareTo(BigDecimal.valueOf(180)) > 0)) {
            errors.add(new FieldErrorDetail("longitude", "Range", "-180~180"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        Integer nx = null;
        Integer ny = null;
        if (site && lat != null) {
            lat = lat.setScale(6, RoundingMode.HALF_UP);
            lon = lon.setScale(6, RoundingMode.HALF_UP);
            Optional<KmaGrid.Point> grid = KmaGrid.of(lat.doubleValue(), lon.doubleValue());
            nx = grid.map(KmaGrid.Point::nx).orElse(null);
            ny = grid.map(KmaGrid.Point::ny).orElse(null);
        }
        return new SpaceAttributes(name, code, d.sortOrder() == null ? 0 : d.sortOrder(), usage, area, d.capacity(),
                site ? timezone : null, site ? address : null, site ? lat : null, site ? lon : null, nx, ny);
    }

    private static void onlySite(List<FieldErrorDetail> errors, String field, Object value) {
        if (value != null) {
            errors.add(new FieldErrorDetail(field, "SITE_ONLY", null));
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
