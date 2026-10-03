package net.java21.data2flow.core.sim.service;

import net.java21.data2flow.core.catalog.repository.DeviceModelRepository;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 시작할 때 이 배포의 ACTIVE 조직마다 가상 환경 기본값(SIM 소스·하트비트 기기·가상 드라이버)을 보장한다(멱등, 실패해도 기동은 막지 않음).
 * 하트비트 기기가 있어야 simulator의 카나리({@code DATA2FLOW_SIM_HEARTBEAT_ENABLED})를 켤 수 있다. 로컬 프로필은 끈다.
 */
@Component
@ConditionalOnProperty(prefix = "data2flow.core.loop", name = "seed-on-startup", havingValue = "true", matchIfMissing = true)
public class SimStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimStartupRunner.class);

    private final SimBootstrap bootstrap;
    private final DeviceModelRepository models;
    private final DeploymentOrganization deployment;

    public SimStartupRunner(SimBootstrap bootstrap, DeviceModelRepository models, DeploymentOrganization deployment) {
        this.bootstrap = bootstrap;
        this.models = models;
        this.deployment = deployment;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (long orgId : models.listActiveOrganizationIds(deployment.restriction())) {
                bootstrap.ensure(orgId);
            }
        } catch (RuntimeException ex) {
            log.warn("가상 환경 기본값 보장 실패(다음 기동·가상 기기 생성 때 다시 시도)", ex);
        }
    }
}
