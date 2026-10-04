package net.java21.data2flow.core.space.controller;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * 업로드 한도. Spring Boot 기본(파일 1MB)으로는 평면도(≤10MB, API-DEV-09)와 시계열 가져오기 CSV(≤2GB, BR-TSD-15, API-TSD-30)를
 * 받을 수 없어 파일 2GB·요청 2GB+16MB로 올리고, 1MB를 넘는 파일은 메모리가 아니라 임시 디스크에 둔다. 실제 크기 판정은 기능마다 한다
 * (평면도 10MB → 400 FLOORPLAN_IMAGE_INVALID, 기기 CSV 10MB → 413, 시계열 가져오기 2GB → 400 IMPORT_LIMIT_EXCEEDED).
 * 다른 기능이 같은 빈을 먼저 정의하면 그것을 쓴다(공통 설정으로 옮길 때 이 클래스를 지운다).
 */
@Configuration(proxyBeanMethods = false)
public class FloorplanMultipartConfig {

    @Bean
    @ConditionalOnMissingBean(MultipartConfigElement.class)
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofGigabytes(2));
        factory.setMaxRequestSize(DataSize.ofMegabytes(2048 + 16));
        factory.setFileSizeThreshold(DataSize.ofMegabytes(1));
        return factory.createMultipartConfig();
    }
}
