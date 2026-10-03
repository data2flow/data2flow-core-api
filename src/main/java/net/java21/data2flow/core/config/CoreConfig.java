package net.java21.data2flow.core.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

/** core-api 공통 빈: 시계, 비밀번호 해시, Flyway 실행 방식 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoreProperties.class)
public class CoreConfig {

    /** 운영 코드는 이 시계만 쓴다(ArchUnit NO_SYSTEM_CLOCK). 테스트는 MutableClock으로 바꾼다 */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * IAM-02.01: Argon2id(적응형 단방향 해시). Spring Security 5.8 기본값(메모리 16MiB, 반복 2, 병렬 1, 솔트 16바이트, 해시 32바이트).
     * 인코딩 문자열에 매개변수가 들어 있어 값을 올려도 기존 해시는 그대로 검증되고, 로그인 성공 때 다시 해시한다.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * ADR-030: staging과 prod가 DB 하나를 함께 쓰므로 migrate는 staging 배포(Flyway PreSync)와 테스트에서만 한다.
     * 그 밖(prod·local)은 validate만 해서, 배포되지 않은 마이그레이션이 공용 DB에 들어가지 않게 한다.
     */
    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(CoreProperties properties) {
        return (Flyway flyway) -> {
            if ("migrate".equalsIgnoreCase(properties.flywayMode())) {
                flyway.migrate();
            } else {
                flyway.validate();
            }
        };
    }
}
