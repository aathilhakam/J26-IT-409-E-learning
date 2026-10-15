package lk.smartplay.adaptive.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import lk.smartplay.adaptive.model.ContentAsset;
import lk.smartplay.adaptive.service.ContentAssetService;
import lk.smartplay.adaptive.service.TeacherAccountService;

@WebMvcTest(ContentAssetController.class)
class ContentAssetControllerTest {
    private static final String TEACHER_EMAIL = "teacher@example.test";
    private static final String TEACHER_ID = "teacher-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentAssetService contentService;

    @MockitoBean
    private TeacherAccountService accountService;

    @BeforeEach
    void setUp() {
        when(accountService.accountIdForEmail(TEACHER_EMAIL)).thenReturn(TEACHER_ID);
    }

    @Test
    void contentLibraryRouteIsAvailableForSignedInTeachers() throws Exception {
        when(contentService.findAll(TEACHER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/content").with(user(TEACHER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void uploadRouteAcceptsMultipartFilesForSignedInTeachers() throws Exception {
        byte[] pdf = "%PDF-1.7 mock".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        MockMultipartFile file = new MockMultipartFile("file", "worksheet.pdf", MediaType.APPLICATION_PDF_VALUE, pdf);
        ContentAsset saved = new ContentAsset(
                TEACHER_ID, "Worksheet", "Worksheet", "", "worksheet.pdf", "stored.pdf",
                MediaType.APPLICATION_PDF_VALUE, pdf.length);
        when(contentService.store(TEACHER_ID, "Worksheet", "Worksheet", "", file)).thenReturn(saved);

        mockMvc.perform(multipart("/api/content")
                        .file(file)
                        .param("title", "Worksheet")
                        .param("category", "Worksheet")
                        .param("description", "")
                        .with(user(TEACHER_EMAIL))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Worksheet"))
                .andExpect(jsonPath("$.originalFileName").value("worksheet.pdf"));

        verify(contentService).store(TEACHER_ID, "Worksheet", "Worksheet", "", file);
    }
}
