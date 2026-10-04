package net.java21.data2flow.core.retention.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.retention.domain.RetentionErrorCode;
import net.java21.data2flow.core.retention.dto.RetentionDtos.PreviewResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.StorageStatsResponse;
import net.java21.data2flow.core.retention.service.ArchiveFileService;
import net.java21.data2flow.core.retention.service.RetentionPolicyService;
import net.java21.data2flow.core.retention.service.StorageStatsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API-TSD-33·41·42·43 슬라이스: 공통 응답, 오류 코드 → HTTP 상태·4개 언어 문구 — TC-TSD-029·030·122·128·133·134 */
@WebMvcTest(RetentionController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class RetentionPolicyControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    RetentionPolicyService policies;
    @MockitoBean
    StorageStatsService stats;
    @MockitoBean
    ArchiveFileService archives;
    @MockitoBean
    UserRepository users;

    @Test
    @DisplayName("[TSD-05.01][TSD-05.02][TSD-05.03] 미리 보기 200, 저장 409 RETENTION_CONFIRM_REQUIRED·400 RETENTION_INVALID(errors), 보관 파일 404, 저장 현황 200")
    void mapping() throws Exception {
        given(policies.preview(any())).willReturn(new PreviewResponse(10, 20, List.of(), true, "1.ab", Instant.parse("2026-10-03T00:10:00Z")));
        mvc.perform(post("/core/retention-policies/preview").header("X-USER-ID", "1").header("X-ORG-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.header.resultCode").value("SUCCESS"))
                .andExpect(jsonPath("$.response.confirmToken").value("1.ab"));
        given(policies.save(any())).willThrow(new BusinessException(RetentionErrorCode.RETENTION_CONFIRM_REQUIRED));
        mvc.perform(put("/core/retention-policies").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("RETENTION_CONFIRM_REQUIRED"))
                .andExpect(jsonPath("$.header.resultMessage").value(
                        "Shortening the retention period deletes data. Review the preview and confirm before saving"));
        willThrow(new BusinessException(RetentionErrorCode.RETENTION_INVALID,
                List.of(new FieldErrorDetail("items[0].retainDays", "Range", "30~3650")))).given(policies).save(any());
        mvc.perform(put("/core/retention-policies").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "ja")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("items[0].retainDays"))
                .andExpect(jsonPath("$.header.resultMessage").value("保存期間が許容範囲外です"));
        given(archives.get(anyLong())).willThrow(new BusinessException(RetentionErrorCode.ARCHIVE_NOT_FOUND));
        mvc.perform(get("/core/archives/9").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "zh"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultMessage").value("未找到归档文件"));
        given(stats.stats()).willReturn(new StorageStatsResponse(List.of(), 0));
        mvc.perform(get("/core/storage-stats").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.totalBytes").value(0));
        mvc.perform(get("/core/retention-policies")).andExpect(status().isUnauthorized());
    }
}
