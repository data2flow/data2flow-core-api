package net.java21.data2flow.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** data2flow-core-api: 회원·조직·권한·기기·공간·소스·스크립트·플로우 정의·규칙·알람·대시보드·조회·운영 설정, 실시간 화면(SSE) */
@SpringBootApplication
public class CoreApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApiApplication.class, args);
    }
}
