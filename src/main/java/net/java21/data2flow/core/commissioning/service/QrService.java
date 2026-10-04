package net.java21.data2flow.core.commissioning.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.commissioning.domain.QrLabelPdf;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.QrResolveResponse;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.QrResponse;
import net.java21.data2flow.core.commissioning.repository.CommissioningRepository;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.service.DeviceAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 기기 QR 라벨과 모바일 딥링크(DEV-09.04, API-DEV-23·24·26). 내용은 {@code {웹}/d/{qr_token}}, 토큰은 추측할 수 없는 24자(18바이트 난수
 * Base64URL). 재발급하면 이전 토큰은 더 이상 해석되지 않아 이전 QR은 404다(AT-DEV-21.2). 권한 밖 기기의 토큰도 404 {@code DEVICE_NOT_FOUND}.
 */
@Service
public class QrService {

    static final String AUDIT_REISSUED = "DEVICE_QR_REISSUED";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RoleChecker roleChecker;
    private final DeviceAccess access;
    private final CommissioningRepository repository;
    private final Audits audits;
    private final String webBaseUrl;

    public QrService(RoleChecker roleChecker, DeviceAccess access, CommissioningRepository repository, Audits audits,
                     CoreProperties properties) {
        this.roleChecker = roleChecker;
        this.access = access;
        this.repository = repository;
        this.audits = audits;
        this.webBaseUrl = properties.webBaseUrl();
    }

    /** 24자 토큰 */
    static String newToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String url(String token) {
        return webBaseUrl + "/d/" + token;
    }

    /** 기기 QR(없으면 처음 만든다) — DEV_READ */
    @Transactional
    public QrResponse get(long deviceId) {
        roleChecker.require(Permission.DEV_READ);
        Device d = access.device(deviceId, Permission.DEV_READ);
        String token = ensure(d.organizationId(), deviceId);
        return new QrResponse(Long.toString(deviceId), token, url(token));
    }

    /** 재발급 — DEV_PLACE. 이전 QR은 404가 된다 */
    @Transactional
    public QrResponse reissue(long deviceId) {
        roleChecker.require(Permission.DEV_PLACE);
        CurrentUser user = roleChecker.currentUser();
        Device d = access.device(deviceId, Permission.DEV_PLACE);
        String token = newToken();
        repository.updateQrToken(d.organizationId(), deviceId, token);
        audits.record(audits.event(d.organizationId(), AUDIT_REISSUED).actor(user).target("DEVICE", Long.toString(deviceId)));
        return new QrResponse(Long.toString(deviceId), token, url(token));
    }

    /** API-DEV-24 라벨 PDF(최대 200대, A4 3×8) — DEV_PLACE. 토큰이 없는 기기는 만든다. 권한 밖·없는 기기는 404 */
    @Transactional
    public byte[] labels(List<String> deviceIds, String layout) {
        roleChecker.require(Permission.DEV_PLACE);
        if (layout != null && !layout.isBlank() && !QrLabelPdf.LAYOUT_A4_3X8.equalsIgnoreCase(layout.strip())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("layout", "Invalid", QrLabelPdf.LAYOUT_A4_3X8)));
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String raw : deviceIds) {
            ids.add(DeviceAccess.id(raw, "deviceIds"));
        }
        List<QrLabelPdf.Label> labels = new ArrayList<>();
        for (long id : ids) {
            Device d = access.device(id, Permission.DEV_PLACE);
            labels.add(new QrLabelPdf.Label(url(ensure(d.organizationId(), id)), d.externalId(), d.name()));
        }
        return QrLabelPdf.render(labels);
    }

    /** API-DEV-26 토큰 해석 — DEV_READ. 없거나 권한 밖이면 404 DEVICE_NOT_FOUND */
    @Transactional(readOnly = true)
    public QrResolveResponse resolve(String token) {
        roleChecker.require(Permission.DEV_READ);
        long org = roleChecker.currentUser().organizationId();
        if (token == null || !token.matches("[A-Za-z0-9_-]{24}")) {
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        }
        long deviceId = repository.findDeviceByQrToken(org, token).orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        access.device(deviceId, Permission.DEV_READ);
        return new QrResolveResponse(Long.toString(deviceId));
    }

    private String ensure(long org, long deviceId) {
        return repository.findQrToken(org, deviceId).orElseGet(() -> {
            String token = newToken();
            repository.updateQrToken(org, deviceId, token);
            return token;
        });
    }
}
