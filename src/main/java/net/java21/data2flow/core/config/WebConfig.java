package net.java21.data2flow.core.config;

import net.java21.data2flow.core.common.AccountGateInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 웹 설정: 계정 관문(BR-IAM-06·25) */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final AccountGateInterceptor accountGate;

    public WebConfig(AccountGateInterceptor accountGate) {
        this.accountGate = accountGate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accountGate).addPathPatterns("/core/**").order(-100);
    }
}
