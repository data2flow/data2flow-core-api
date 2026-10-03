package net.java21.data2flow.core.devicecredential.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.domain.References.SourceRef;
import net.java21.data2flow.core.device.service.DeviceAccess;
import net.java21.data2flow.core.devicecredential.dto.DeviceCredentialDtos.CredentialResponse;
import net.java21.data2flow.core.devicecredential.dto.DeviceCredentialDtos.IssueCredentialRequest;
import net.java21.data2flow.core.devicecredential.dto.DeviceCredentialDtos.IssuedCredentialResponse;
import net.java21.data2flow.core.devicecredential.repository.DeviceCredentialRepository;
import net.java21.data2flow.core.devicecredential.repository.DeviceCredentialRepository.CredentialRow;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 플랫폼 브로커 기기 자격(DSC-03.02·03.05, ADR-029·031): 공용 브로커 {@code iot-data.java21.net}의 nginx Basic 계정과 기기별
 * payload 서명 키(HMAC-SHA256). 비밀번호는 Argon2id 해시, 서명 키는 SHA-256 해시만 저장하고 원문은 발급 응답에서 한 번만 준다.
 * 기기당 ACTIVE 자격은 하나다(새로 발급하면 이전 것을 폐기). 바뀌면 설정 변경(CREDENTIAL)을 보내 ingress가 1분 안에 캐시를
 * 다시 읽는다(BR-DSC-14). 브로커·nginx 설정에는 아무것도 쓰지 않는다(CLAUDE.md §5).
 */
@Service
public class DeviceCredentialService {

    private final RoleChecker roleChecker;
    private final DeviceAccess access;
    private final DeviceCredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;
    private final CoreEventPublisher publisher;
    private final Audits audits;
    private final Clock clock;

    public DeviceCredentialService(RoleChecker roleChecker, DeviceAccess access, DeviceCredentialRepository credentials,
                                   PasswordEncoder passwordEncoder, CoreEventPublisher publisher, Audits audits, Clock clock) {
        this.roleChecker = roleChecker;
        this.access = access;
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.publisher = publisher;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-DSC-21 */
    @Transactional(readOnly = true)
    public List<CredentialResponse> list(long deviceId) {
        roleChecker.require(Permission.SRC_READ);
        Device device = access.device(deviceId, Permission.SRC_READ);
        return credentials.findByDevice(device.organizationId(), device.id()).stream().map(DeviceCredentialService::toResponse).toList();
    }

    /** API-DSC-20 발급. 플랫폼 브로커 소스의 기기가 아니면 409 SOURCE_STATE_CONFLICT */
    @Transactional
    public IssuedCredentialResponse issue(long deviceId, IssueCredentialRequest req) {
        roleChecker.require(Permission.SRC_ADMIN);
        Device device = access.device(deviceId, Permission.SRC_ADMIN);
        SourceRef source = access.source(device.sourceId());
        if (!source.platformBroker()) {
            throw new BusinessException(DeviceErrorCode.SOURCE_STATE_CONFLICT);
        }
        Instant now = clock.instant();
        Instant expiresAt = req == null ? null : req.expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("expiresAt", "PAST", null)));
        }
        CurrentUser user = roleChecker.currentUser();
        String password = Tokens.newToken();
        String signingKey = Tokens.newToken();
        long id = store(device, passwordEncoder.encode(password), signingKey, expiresAt, user.userId(), now);
        CredentialRow row = credentials.findById(device.organizationId(), device.id(), id).orElseThrow();
        audits.record(audits.event(device.organizationId(), "DEVICE_CREDENTIAL_ISSUED").actor(user)
                .target("DEVICE_CREDENTIAL", Long.toString(id)).detail("deviceId", device.id()).detail("username", row.username()));
        return new IssuedCredentialResponse(Long.toString(id), row.username(), password, signingKey, expiresAt);
    }

    /**
     * 승인할 때 서명 키를 한 번 발급한다(DSC-03.05). 접속 비밀번호는 없다(관리자가 필요하면 API-DSC-20으로 발급).
     * 호출자 트랜잭션 안에서 실행되고 원문 키를 돌려준다.
     */
    public String issueOnApproval(Device device, long approvedBy) {
        String signingKey = Tokens.newToken();
        store(device, null, signingKey, null, approvedBy, clock.instant());
        return signingKey;
    }

    /** API-DSC-22 폐기 */
    @Transactional
    public CredentialResponse revoke(long deviceId, long credentialId) {
        roleChecker.require(Permission.SRC_ADMIN);
        Device device = access.device(deviceId, Permission.SRC_ADMIN);
        CredentialRow row = credentials.findById(device.organizationId(), device.id(), credentialId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.CREDENTIAL_NOT_FOUND));
        if (!"ACTIVE".equals(row.status()) || credentials.updateRevoked(device.organizationId(), device.id(), credentialId,
                clock.instant()) == 0) {
            throw new BusinessException(DeviceErrorCode.CREDENTIAL_REVOKED);
        }
        publisher.configDeleted(EntityType.CREDENTIAL, credentialId, 1, device.organizationId());
        CurrentUser user = roleChecker.currentUser();
        audits.record(audits.event(device.organizationId(), "DEVICE_CREDENTIAL_REVOKED").actor(user)
                .target("DEVICE_CREDENTIAL", Long.toString(credentialId)).detail("deviceId", device.id()).detail("username", row.username()));
        return toResponse(credentials.findById(device.organizationId(), device.id(), credentialId).orElseThrow());
    }

    private long store(Device device, String passwordHash, String signingKey, Instant expiresAt, long createdBy, Instant now) {
        long org = device.organizationId();
        for (Long revoked : credentials.updateRevokedByDevice(org, device.id(), now)) {
            publisher.configDeleted(EntityType.CREDENTIAL, revoked, 1, org);
        }
        String username = device.externalId();
        if (credentials.existsActiveUsername(org, username, device.id())) {
            username = device.externalId() + "-" + device.id();
        }
        long id = credentials.insert(org, device.id(), username, passwordHash, Tokens.sha256Hex(signingKey), expiresAt, createdBy, now);
        publisher.configChanged(EntityType.CREDENTIAL, id, 0, org);
        return id;
    }

    static CredentialResponse toResponse(CredentialRow r) {
        return new CredentialResponse(Long.toString(r.id()), Long.toString(r.deviceId()), r.type(), r.username(), r.status(), r.expiresAt(),
                r.lastUsedAt(), r.createdAt(), r.revokedAt());
    }
}
