package net.java21.data2flow.core.support;

import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.core.account.domain.UserStatus;
import net.java21.data2flow.core.account.repository.PasswordHistoryRepository;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.account.repository.UserRepository.NewUser;
import net.java21.data2flow.core.extservice.repository.ExternalServiceRepository;
import net.java21.data2flow.core.mail.service.MailService;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import net.java21.data2flow.core.role.repository.RoleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.List;

/**
 * 테스트 데이터 빌더(design/testing/backend.md §3 "TestOrganizations, UserFixtures"). 조직·사용자·역할·메일 설정을 DB에 바로 만든다.
 * 같은 비밀번호의 해시는 한 번만 계산해 다시 쓴다(Argon2 비용 절약).
 */
public class Fixtures {

    /** 정책을 지키는 기본 비밀번호 */
    public static final String PASSWORD = "Vivid-Orbit-73!Lamp";

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordHistoryRepository history;
    private final ExternalServiceRepository externalServices;
    private final SecretCipher cipher;
    private final PasswordEncoder encoder;
    private final JdbcClient jdbc;
    private final Clock clock;
    private String defaultHash;

    public Fixtures(OrganizationRepository organizations, UserRepository users, RoleRepository roles,
                    PasswordHistoryRepository history, ExternalServiceRepository externalServices, SecretCipher cipher,
                    PasswordEncoder encoder, JdbcClient jdbc, Clock clock) {
        this.organizations = organizations;
        this.users = users;
        this.roles = roles;
        this.history = history;
        this.externalServices = externalServices;
        this.cipher = cipher;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 조직 + 기본 설정 + 보안 정책 */
    public long organization(String code) {
        long id = organizations.insert("org-" + code, "학교 " + code, "Asia/Seoul", "ko");
        organizations.insertSettingsIfAbsent(id, "학교 " + code, "Asia/Seoul", "ko");
        organizations.insertPolicyIfAbsent(id);
        return id;
    }

    /** ACTIVE 사용자 + 역할(전체 공간) */
    public long user(long orgId, String loginId, String role) {
        return user(orgId, loginId, role, PASSWORD, false);
    }

    public long user(long orgId, String loginId, String role, String password, boolean mustChange) {
        String hash = PASSWORD.equals(password) ? defaultHash() : encoder.encode(password);
        long id = users.insert(new NewUser(orgId, loginId, loginId.replace(".", "_") + "@school.ac.kr", "이름 " + loginId,
                null, "ko", "Asia/Seoul", UserStatus.ACTIVE, hash, mustChange, clock.instant()));
        history.push(orgId, id, hash, clock.instant());
        if (role != null) {
            roles.upsertUserRole(orgId, id, role, null, List.of(), id, clock.instant());
        }
        return id;
    }

    /** 사용자 정의 역할 지정 */
    public void assignCustomRole(long orgId, long userId, long customRoleId, long grantedBy) {
        roles.upsertUserRole(orgId, userId, "CUSTOM", customRoleId, List.of(), grantedBy, clock.instant());
    }

    /** 메일 서버(OPS-07.02 MAIL)를 테스트 SMTP로 */
    public void mail(long orgId, int smtpPort) {
        String settings = "{\"host\":\"127.0.0.1\",\"port\":" + smtpPort + ",\"from\":\"no-reply@data2flow.test\"}";
        byte[] secret = cipher.encrypt(Secret.of("smtp-password"), MailService.secretContext(orgId));
        externalServices.insert(orgId, "MAIL", "smtp", settings, secret, true, 0, clock.instant());
    }

    /** 보안 정책 열 하나를 바꾼다 */
    public void policy(long orgId, String column, Object value) {
        if (!column.matches("[a-z_]+")) {
            throw new IllegalArgumentException(column);
        }
        String expr = value instanceof List<?> ? "CAST(:v AS varchar(253)[])" : ":v";
        Object v = value instanceof List<?> list ? net.java21.data2flow.core.common.Pg.textArray(list.stream().map(String::valueOf).toList()) : value;
        jdbc.sql("UPDATE data2flow_core.org_security_policies SET " + column + " = " + expr + " WHERE organization_id = :org")
                .param("v", v).param("org", orgId).update();
    }

    public String defaultHash() {
        if (defaultHash == null) {
            defaultHash = encoder.encode(PASSWORD);
        }
        return defaultHash;
    }
}
