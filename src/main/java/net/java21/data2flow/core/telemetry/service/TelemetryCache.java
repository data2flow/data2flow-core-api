package net.java21.data2flow.core.telemetry.service;

import net.java21.data2flow.core.common.Tokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 확정된 과거 집계 조회 캐시(TSD-06.04, BR-TSD-20). s4 Redis를 재시작하면 사라져도 되는 캐시로만 쓴다(ADR-022, 키 접두사 {@code data2flow:}).
 *
 * <ul>
 *   <li>값: {@code data2flow:core:tsq:{조직}:{조건 해시}} → 응답 JSON, 10분</li>
 *   <li>무효화 색인: 기기별 {@code data2flow:core:tsq-idx:{조직}:d:{기기}}, 공간 집계 {@code …:space}, 조직 전체 {@code …:all}.
 *       재계산(EVT-TSD-03)이면 그 기기 색인과 공간 색인, 보관 삭제(EVT-TSD-04)면 조직 전체를 지운다</li>
 *   <li>Redis가 없거나 꺼져 있으면 항상 MISS(조회는 그대로 된다). 오류는 캐시 실패로만 다룬다</li>
 * </ul>
 */
@Component
public class TelemetryCache {

    private static final Logger log = LoggerFactory.getLogger(TelemetryCache.class);
    static final String PREFIX = "data2flow:core:tsq:";
    static final String INDEX = "data2flow:core:tsq-idx:";

    private final Settings settings;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public TelemetryCache(Settings settings, ObjectProvider<StringRedisTemplate> redisProvider) {
        this.settings = settings;
        this.redisProvider = redisProvider;
    }

    /**
     * 설정({@code data2flow.core.telemetry-cache.*}).
     *
     * @param enabled 켜기(기본 false — Redis 주소가 있는 배포에서 켠다)
     * @param ttl     값 수명(기본 10분, BR-TSD-20)
     */
    @ConfigurationProperties(prefix = "data2flow.core.telemetry-cache")
    public record Settings(Boolean enabled, Duration ttl) {
        public Settings {
            enabled = enabled != null && enabled;
            ttl = ttl == null ? Duration.ofMinutes(10) : ttl;
        }
    }

    @Configuration
    @EnableConfigurationProperties(Settings.class)
    static class Config {
    }

    public boolean enabled() {
        return settings.enabled() && redisProvider.getIfAvailable() != null;
    }

    /** 캐시 키(조직·조건 해시) */
    public static String key(long organizationId, String condition) {
        return PREFIX + organizationId + ":" + Tokens.sha256Hex(condition);
    }

    public Optional<String> get(String key) {
        StringRedisTemplate redis = redis();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (RuntimeException ex) {
            log.debug("시계열 캐시 읽기 실패: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 값 저장과 색인 등록.
     *
     * @param deviceIds  응답에 들어간 기기(재계산 무효화 대상)
     * @param spaceWide  공간 집계가 들어 있는가(어느 기기가 재계산돼도 지운다)
     */
    public void put(long organizationId, String key, String json, Collection<Long> deviceIds, boolean spaceWide) {
        StringRedisTemplate redis = redis();
        if (redis == null) {
            return;
        }
        try {
            Duration ttl = settings.ttl();
            Duration indexTtl = ttl.plusMinutes(1);
            redis.opsForValue().set(key, json, ttl);
            List<String> indexes = new ArrayList<>();
            indexes.add(INDEX + organizationId + ":all");
            if (spaceWide) {
                indexes.add(INDEX + organizationId + ":space");
            }
            for (Long id : deviceIds) {
                indexes.add(INDEX + organizationId + ":d:" + id);
            }
            for (String index : indexes) {
                redis.opsForSet().add(index, key);
                redis.expire(index, indexTtl);
            }
        } catch (RuntimeException ex) {
            log.debug("시계열 캐시 쓰기 실패: {}", ex.getMessage());
        }
    }

    /** 재계산된 기기(EVT-TSD-03): 그 기기 계열과 공간 집계 캐시를 지운다 */
    public void invalidateDevices(long organizationId, Collection<Long> deviceIds) {
        List<String> indexes = new ArrayList<>();
        indexes.add(INDEX + organizationId + ":space");
        for (Long id : deviceIds) {
            indexes.add(INDEX + organizationId + ":d:" + id);
        }
        evict(indexes);
    }

    /** 조직 전체(보관 삭제 EVT-TSD-04) */
    public void invalidateOrganization(long organizationId) {
        evict(List.of(INDEX + organizationId + ":all"));
    }

    private void evict(List<String> indexes) {
        StringRedisTemplate redis = redis();
        if (redis == null) {
            return;
        }
        try {
            for (String index : indexes) {
                Set<String> keys = redis.opsForSet().members(index);
                if (keys != null && !keys.isEmpty()) {
                    redis.delete(keys);
                }
                redis.delete(index);
            }
        } catch (RuntimeException ex) {
            log.warn("시계열 캐시 무효화 실패(10분 뒤 저절로 사라짐): {}", ex.getMessage());
        }
    }

    private StringRedisTemplate redis() {
        return settings.enabled() ? redisProvider.getIfAvailable() : null;
    }
}
