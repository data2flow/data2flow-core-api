package net.java21.data2flow.core.space.controller;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * 업로드 한도. Spring Boot 기본(파일 1MB)으로는 평면도(≤10MB, API-DEV-09)를 받을 수 없어 파일 16MB·요청 20MB로 올린다.
 * 실제 크기 판정(10MB)은 평면도 검사가 하고 그 사이 크기는 400 FLOORPLAN_IMAGE_INVALID다.
 * 다른 기능이 같은 빈을 먼저 정의하면 그것을 쓴다(공통 설정으로 옮길 때 이 클래스를 지운다).
 */
@Configuration(proxyBeanMethods = false)
public class FloorplanMultipartConfig {

    @Bean
    @ConditionalOnMissingBean(MultipartConfigElement.class)
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(16));
        factory.setMaxRequestSize(DataSize.ofMegabytes(20));
        return factory.createMultipartConfig();
    }
}
