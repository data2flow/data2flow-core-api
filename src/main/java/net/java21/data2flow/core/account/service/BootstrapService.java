package net.java21.data2flow.core.account.service;

import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.core.account.domain.UserStatus;
import net.java21.data2flow.core.account.event.IamEventPublisher;
import net.java21.data2flow.core.account.repository.PasswordHistoryRepository;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.account.repository.UserRepository.NewUser;
import net.java21.data2flow.core.audit.service.AuditCodes;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.catalog.service.BuiltinCatalogSeeder;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.config.CoreProperties.Bootstrap;
import net.java21.data2flow.core.organization.domain.OrganizationModels.Organization;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import net.java21.data2flow.core.role.repository.RoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * 최초 설치(IAM-01.02, design/auth.md §3.4): 조직(v1 단일, ADR-004)과 첫 ADMIN을 만든다. 관리자 0명일 때 첫 로그인 사용자를
 * 관리자로 만드는 crowfoot 방식은 쓰지 않는다. 초기 비밀번호는 일회용이고 첫 로그인에서 변경을 강제한다(must_change_password).
 * 이미 ADMIN이 있으면 아무것도 하지 않는다(멱등, AT-IAM-01.4).
 *
 * <p>초기 비밀번호: Secret에 미리 넣은 값(DATA2FLOW_BOOTSTRAP_ADMIN_INITIAL_PASSWORD)을 쓰거나, 없으면 생성해 지정한 파일(권한 600)에
 * 쓴다. 로그에는 남기지 않는다(NFR-03.02).
 */
@Service
public class BootstrapService {

    /** 실행 결과 */
    public enum Result { CREATED, ALREADY_INITIALIZED }

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordHistoryRepository history;
    private final PasswordEncoder encoder;
    private final Audits audits;
    private final IamEventPublisher events;
    private final BuiltinCatalogSeeder catalogSeeder;
    private final Clock clock;

    public BootstrapService(OrganizationRepository organizations, UserRepository users, RoleRepository roles,
                            PasswordHistoryRepository history, PasswordEncoder encoder, Audits audits, IamEventPublisher events,
                            BuiltinCatalogSeeder catalogSeeder, Clock clock) {
        this.organizations = organizations;
        this.users = users;
        this.roles = roles;
        this.history = history;
        this.encoder = encoder;
        this.audits = audits;
        this.events = events;
        this.catalogSeeder = catalogSeeder;
        this.clock = clock;
    }

    @Transactional
    public Result bootstrap(Bootstrap config) {
        Instant now = clock.instant();
        Organization org = organizations.findByCode(config.organizationCode()).orElseGet(() -> {
            long id = organizations.insert(config.organizationCode(), config.organizationName(), "Asia/Seoul", "ko");
            return organizations.findById(id).orElseThrow();
        });
        organizations.insertSettingsIfAbsent(org.id(), org.name(), org.timezone(), org.locale());
        organizations.insertPolicyIfAbsent(org.id());
        // DEV-03.02: 새 조직에는 기본 모델 6종과 측정 항목이 있다(멱등)
        catalogSeeder.seed(org.id());
        if (users.existsAdmin(org.id())) {
            return Result.ALREADY_INITIALIZED;
        }
        if (config.adminLoginId() == null || config.adminEmail() == null) {
            throw new IllegalStateException("DATA2FLOW_BOOTSTRAP_ADMIN_LOGIN_ID·DATA2FLOW_BOOTSTRAP_ADMIN_EMAIL이 필요합니다");
        }
        String loginId = LoginIdRules.normalize(config.adminLoginId());
        boolean generated = config.adminInitialPassword() == null || config.adminInitialPassword().isBlank();
        String password = generated ? Tokens.newPassword(20) : config.adminInitialPassword();
        if (generated) {
            writePassword(config.passwordOutputFile(), password);
        }
        String hash = encoder.encode(password);
        long userId = users.insert(new NewUser(org.id(), loginId, config.adminEmail().strip(), config.adminName(), null,
                org.locale(), org.timezone(), UserStatus.ACTIVE, hash, true, now));
        history.push(org.id(), userId, hash, now);
        roles.upsertUserRole(org.id(), userId, "ADMIN", null, java.util.List.of(), userId, now);
        audits.record(audits.event(org.id(), AuditCodes.USER_CREATED).actor(AuditActorType.SYSTEM, "bootstrap", "bootstrap-job")
                .target("USER", Long.toString(userId)).detail("loginId", loginId).detail("role", "ADMIN").detail("method", "BOOTSTRAP"));
        events.securityAlert(org.id(), "ADMIN_CREATED", userId, null, Map.of("method", "BOOTSTRAP"));
        return Result.CREATED;
    }

    private static void writePassword(String file, String password) {
        if (file == null || file.isBlank()) {
            throw new IllegalStateException("초기 비밀번호를 생성하려면 data2flow.core.bootstrap.password-output-file이 필요합니다");
        }
        try {
            Path path = Path.of(file);
            Files.writeString(path, password + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // POSIX가 아닌 파일 시스템
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("초기 비밀번호 파일을 쓰지 못했습니다", ex);
        }
    }
}
