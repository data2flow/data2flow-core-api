package net.java21.data2flow.core.support;

import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.core.account.repository.PasswordHistoryRepository;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.extservice.repository.ExternalServiceRepository;
import net.java21.data2flow.core.organization.repository.OrganizationRepository;
import net.java21.data2flow.core.role.repository.RoleRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

/** 통합 테스트 빈: 바꿀 수 있는 시계(@Primary)와 테스트 데이터 빌더 */
@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(MutableClock.T0);
    }

    @Bean
    Fixtures fixtures(OrganizationRepository organizations, UserRepository users, RoleRepository roles,
                      PasswordHistoryRepository history, ExternalServiceRepository externalServices, SecretCipher cipher,
                      PasswordEncoder encoder, JdbcClient jdbc, Clock clock) {
        return new Fixtures(organizations, users, roles, history, externalServices, cipher, encoder, jdbc, clock);
    }
}
