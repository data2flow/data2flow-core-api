package net.java21.data2flow.core.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.contracts.web.ApiHeader;
import net.java21.data2flow.contracts.web.ListApiResponse;

import java.util.List;
import java.util.Map;

/**
 * 오프셋 목록 응답 + 집계({@code counts}). API-RUL-01(규칙 수·한도)·API-RUL-10(상태·심각도별 수)처럼 목록 옆에 개수를 함께 주는 응답.
 * 모양은 {@link ListApiResponse}와 같고 {@code counts}만 더한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CountedListResponse<T>(ApiHeader header, int page, int size, int totalPages, List<T> responses, long totalCount,
                                     Map<String, ?> counts) {

    public static <T> CountedListResponse<T> of(ListApiResponse<T> list, Map<String, ?> counts) {
        return new CountedListResponse<>(list.header(), list.page(), list.size(), list.totalPages(), list.responses(), list.totalCount(),
                counts);
    }
}
