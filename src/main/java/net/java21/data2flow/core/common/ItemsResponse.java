package net.java21.data2flow.core.common;

import net.java21.data2flow.contracts.web.ApiHeader;

import java.util.List;

/**
 * 페이징하지 않는 작은 목록(api-rules §3.3): {@code {header, responses, totalCount}}. 예: 버전 목록(API-FLW-04, 최대 100개),
 * 노드 카탈로그(API-FLW-30), 템플릿(API-FLW-20), 가상 환경 프로필·공간(API-SIM-08·10).
 */
public record ItemsResponse<T>(ApiHeader header, List<T> responses, long totalCount) {

    public static <T> ItemsResponse<T> of(List<T> items) {
        List<T> copy = items == null ? List.of() : List.copyOf(items);
        return new ItemsResponse<>(ApiHeader.success(), copy, copy.size());
    }
}
