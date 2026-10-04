package net.java21.data2flow.core.bim;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.bim.controller.BuildingModelController;
import net.java21.data2flow.core.bim.dto.BimDtos.ModelCreated;
import net.java21.data2flow.core.bim.service.BuildingModelService;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSH-12.04 IFC 올리기 요청 검사(API-DSH-24) — TC-DSH-110 */
@WebMvcTest(BuildingModelController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class BuildingModelControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    BuildingModelService service;
    @MockitoBean
    UserRepository users;

    /** 크기만 300MB로 보이는 파일(메모리에 300MB를 만들지 않는다) */
    static final class HugeFile extends MockMultipartFile {
        HugeFile() {
            super("file", "big.ifc", "application/octet-stream", "ISO-10303-21;".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public long getSize() {
            return 300L * 1024 * 1024;
        }
    }

    @Test
    @DisplayName("[DSH-12.04][AT-DSH-13.3][TC-DSH-110] 300MB → 413 MODEL_FILE_INVALID '200MB 이하', 서비스 호출 없음")
    void tooLarge() throws Exception {
        mvc.perform(multipart("/core/buildings/1/models").file(new HugeFile()).header("X-USER-ID", "1").header("X-ORG-ID", "1")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.header.resultCode").value("MODEL_FILE_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value(org.hamcrest.Matchers.containsString("200MB 이하")));
        verify(service, never()).upload(anyLong(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("[DSH-12.04][TC-DSH-110] 정상 업로드 201 + Location, 권한 없는 사용자 403(서비스 판정)")
    void created() throws Exception {
        given(service.upload(anyLong(), any(), any(), anyLong(), any())).willReturn(new ModelCreated("5", "lab.ifc", 13, "READY", 2));
        MockMultipartFile file = new MockMultipartFile("file", "lab.ifc", "application/octet-stream", "ISO-10303-21;".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/core/buildings/1/models").file(file).header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/core/buildings/1/models/5"))
                .andExpect(jsonPath("$.response.status").value("READY"));
        given(service.upload(anyLong(), any(), any(), anyLong(), any())).willThrow(new BusinessException(CommonErrorCode.PERMISSION_DENIED));
        mvc.perform(multipart("/core/buildings/1/models").file(file).header("X-USER-ID", "2").header("X-ORG-ID", "1"))
                .andExpect(status().isForbidden());
    }
}
