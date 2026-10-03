package net.java21.data2flow.core.account.service;

import net.java21.data2flow.core.config.CoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 부트스트랩 Job 진입점(IAM-01.02). k8s Job이 Flyway 이후 한 번
 * {@code --data2flow.core.bootstrap.enabled=true --spring.main.web-application-type=none}으로 실행한다.
 */
@Component
@ConditionalOnProperty(prefix = "data2flow.core.bootstrap", name = "enabled", havingValue = "true")
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final BootstrapService service;
    private final CoreProperties properties;
    private final ConfigurableApplicationContext context;

    public BootstrapRunner(BootstrapService service, CoreProperties properties, ConfigurableApplicationContext context) {
        this.service = service;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        BootstrapService.Result result = service.bootstrap(properties.bootstrap());
        log.info("부트스트랩 결과: {} (조직 {})", result, properties.bootstrap().organizationCode());
        if (properties.bootstrap().exitAfterRun()) {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }
}
