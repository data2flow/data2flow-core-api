package net.java21.data2flow.core.config;

import net.java21.data2flow.contracts.web.ErrorMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** IAM-01.01 최초 관리자 Job(웹 아님)에서도 컨트롤러가 받는 ErrorMessages 빈이 있다 */
class CoreConfigTest {

    @Test
    @DisplayName("IAM-01.01 웹이 아닌 실행(부트스트랩 Job)에는 ErrorMessages 대체 빈을 만든다")
    void nonWebHasErrorMessages() {
        new ApplicationContextRunner().withUserConfiguration(CoreConfig.class)
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ErrorMessages.class));
    }

    @Test
    @DisplayName("IAM-01.01 서블릿 웹 앱에서는 대체 빈을 만들지 않는다(contracts 자동 구성이 만든다)")
    void webLeavesItToContracts() {
        new WebApplicationContextRunner().withUserConfiguration(CoreConfig.class)
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean("nonWebErrorMessages"));
    }
}
