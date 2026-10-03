package net.java21.data2flow.core.catalog.service;

import net.java21.data2flow.core.catalog.repository.DeviceModelRepository;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 시작할 때 이 배포가 맡는 ACTIVE 조직마다 기본 카탈로그를 채운다(DEV-03.02). 배포 조직이 정해져 있으면 그 조직만(ADR-030:
 * staging 파드가 prod 조직에 쓰지 않게). 멱등이라 여러 파드가 함께 떠도 된다. 실패해도 기동은 막지 않는다(다음 기동·조직 생성 때 다시 채움).
 * {@code data2flow.core.catalog.seed-on-startup=false}면 끈다(로컬 프로필: 공용 DB에 쓰지 않음).
 */
@Component
@ConditionalOnProperty(prefix = "data2flow.core.catalog", name = "seed-on-startup", havingValue = "true", matchIfMissing = true)
public class BuiltinCatalogStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltinCatalogStartupRunner.class);

    private final BuiltinCatalogSeeder seeder;
    private final DeviceModelRepository models;
    private final DeploymentOrganization deployment;

    public BuiltinCatalogStartupRunner(BuiltinCatalogSeeder seeder, DeviceModelRepository models, DeploymentOrganization deployment) {
        this.seeder = seeder;
        this.models = models;
        this.deployment = deployment;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (long orgId : models.listActiveOrganizationIds(deployment.restriction())) {
                BuiltinCatalogSeeder.SeedResult result = seeder.seed(orgId);
                if (result.metricsCreated() > 0 || result.modelsCreated() > 0) {
                    log.info("기본 카탈로그를 채웠습니다 org={} metrics={} models={}", orgId, result.metricsCreated(), result.modelsCreated());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("기본 카탈로그 시드 실패(다음 기동 때 다시 시도)", ex);
        }
    }
}
