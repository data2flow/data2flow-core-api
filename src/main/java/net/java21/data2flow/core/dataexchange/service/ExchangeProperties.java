package net.java21.data2flow.core.dataexchange.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 내보내기·가져오기 설정({@code data2flow.core.exchange.*}).
 *
 * @param syncMaxRows      동기 내보내기 최대 예상 행 수(BR-TSD-14, 100만)
 * @param syncTimeout      동기로 기다리는 시간(BR-TSD-14, 30초). 넘으면 비동기로 돌린다
 * @param maxActivePerUser 사용자당 진행 중 비동기 작업(UC-TSD-04, 3개)
 * @param fileRetention    결과 파일 보관(BR-TSD-14, 7일)
 * @param linkTtl          다운로드 링크 유효(BR-TSD-14, 1시간)
 * @param workers          비동기 실행 스레드 수(파드당)
 * @param jobsEnabled      비동기·정기·가져오기 주기 작업(테스트는 끄고 직접 부른다)
 * @param scheduleRetries  정기 내보내기 재시도 횟수(BR-TSD-28, 3회)
 * @param retryBase        재시도 간격 시작값(지수 백오프 1분·2분·4분)
 * @param importMaxRows    가져오기 최대 행 수(BR-TSD-15, 5,000만)
 * @param importMaxBytes   가져오기 파일 최대 크기(BR-TSD-15, 2GB)
 * @param importBatch      pipeline 일괄 넣기 한 번 행 수(API-TSD-52, ≤10,000)
 * @param pipelineTimeout  pipeline 내부 API 대기
 * @param targetTimeout    S3·SFTP·InfluxDB 접속 대기
 */
@ConfigurationProperties(prefix = "data2flow.core.exchange")
public record ExchangeProperties(Long syncMaxRows, Duration syncTimeout, Integer maxActivePerUser, Duration fileRetention, Duration linkTtl,
                                 Integer workers, Boolean jobsEnabled, Integer scheduleRetries, Duration retryBase, Long importMaxRows,
                                 Long importMaxBytes, Integer importBatch, Duration pipelineTimeout, Duration targetTimeout) {

    public ExchangeProperties {
        syncMaxRows = syncMaxRows == null ? 1_000_000L : syncMaxRows;
        syncTimeout = syncTimeout == null ? Duration.ofSeconds(30) : syncTimeout;
        maxActivePerUser = maxActivePerUser == null ? 3 : maxActivePerUser;
        fileRetention = fileRetention == null ? Duration.ofDays(7) : fileRetention;
        linkTtl = linkTtl == null ? Duration.ofHours(1) : linkTtl;
        workers = workers == null ? 2 : workers;
        jobsEnabled = jobsEnabled == null || jobsEnabled;
        scheduleRetries = scheduleRetries == null ? 3 : scheduleRetries;
        retryBase = retryBase == null ? Duration.ofMinutes(1) : retryBase;
        importMaxRows = importMaxRows == null ? 50_000_000L : importMaxRows;
        importMaxBytes = importMaxBytes == null ? 2L * 1024 * 1024 * 1024 : importMaxBytes;
        importBatch = importBatch == null ? 10_000 : Math.min(importBatch, 10_000);
        pipelineTimeout = pipelineTimeout == null ? Duration.ofSeconds(30) : pipelineTimeout;
        targetTimeout = targetTimeout == null ? Duration.ofSeconds(15) : targetTimeout;
    }

    @Configuration
    @EnableConfigurationProperties(ExchangeProperties.class)
    static class Config {
    }
}
