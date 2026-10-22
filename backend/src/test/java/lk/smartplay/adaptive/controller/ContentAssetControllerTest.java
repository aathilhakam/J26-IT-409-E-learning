package lk.smartplay.adaptive.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import lk.smartplay.adaptive.config.SecurityConfig;
import lk.smartplay.adaptive.config.WebConfig;
import lk.smartplay.adaptive.dto.ContentMetadataRequest;
import lk.smartplay.adaptive.model.ContentAsset;
import lk.smartplay.adaptive.repository.TeacherAccountRepository;
import lk.smartplay.adaptive.service.ContentAssetService;
import lk.smartplay.adaptive.service.TeacherAccountService;

@WebMvcTest(ContentAssetController.class)
@Import({SecurityConfig.class, WebConfig.class})
class ContentAssetControllerTest {
    private static final String TEACHER_EMAIL = "teacher@example.test";
    private static final String TEACHER_ID = "teacher-1";
    @Autowired private MockMvc mockMvc;
    @MockitoBean private ContentAssetService contentService;
    @MockitoBean private TeacherAccountService accountService;
    @MockitoBean private TeacherAccountRepository accountRepository;
    @TempDir Path directory;

    @BeforeEach
    void setUp() { when(accountService.accountIdForEmail(TEACHER_EMAIL)).thenReturn(TEACHER_ID); }

    private ContentAsset asset() {
        return new ContentAsset(TEACHER_ID, "Worksheet", "Worksheet", "", "worksheet.pdf", "private.pdf", "application/pdf", 8);
    }

    @Test
    void contentLibraryRouteIsAvailableForSignedInTeachers() throws Exception {
        when(contentService.findAll(TEACHER_ID)).thenReturn(List.of());
        mockMvc.perform(get("/api/content").with(user(TEACHER_EMAIL)))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test
    void legacyUploadStillWorksAndDoesNotExposeStorageOrOwner() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "worksheet.pdf", "application/pdf", "%PDF-1.7".getBytes());
        ContentMetadataRequest metadata = new ContentMetadataRequest("Worksheet", "Worksheet", "", null, null, null, null, null);
        when(contentService.store(TEACHER_ID, metadata, file)).thenReturn(asset());
        mockMvc.perform(multipart("/api/content").file(file).param("title", "Worksheet")
                        .param("category", "Worksheet").param("description", "")
                        .param("ownerId", "another-teacher").with(user(TEACHER_EMAIL)).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("Worksheet"))
                .andExpect(jsonPath("$.originalFileName").value("worksheet.pdf"))
                .andExpect(jsonPath("$.storageFileName").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.visibility").value("TEACHER_ONLY"));
        verify(contentService).store(TEACHER_ID, metadata, file);
    }

    @Test
    void materialUploadAcceptsMetadataAndUsesAuthenticatedOwner() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "worksheet.pdf", "application/pdf", "%PDF-1.7".getBytes());
        ContentMetadataRequest metadata = new ContentMetadataRequest("Worksheet", "Worksheet", "Practice", "Math", "Grade 1", "Worksheet", List.of("counting", "numbers"), "TEACHER_ONLY");
        ContentAsset saved = asset();
        saved.updateMetadata(metadata.title(), metadata.category(), metadata.description(), metadata.subject(), metadata.grade(), metadata.materialType(), metadata.tags(), metadata.visibility());
        when(contentService.store(TEACHER_ID, metadata, file)).thenReturn(saved);
        mockMvc.perform(multipart("/api/content").file(file).param("title", "Worksheet").param("category", "Worksheet")
                        .param("description", "Practice").param("subject", "Math").param("grade", "Grade 1")
                        .param("materialType", "Worksheet").param("tags", "counting", "numbers")
                        .param("visibility", "TEACHER_ONLY").with(user(TEACHER_EMAIL)).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subject").value("Math"))
                .andExpect(jsonPath("$.tags[1]").value("numbers"));
        verify(contentService).store(TEACHER_ID, metadata, file);
    }

    @Test
    void downloadChecksOwnerAndReturnsAttachment() throws Exception {
        ContentAsset saved = asset();
        Path file = directory.resolve("worksheet.pdf");
        Files.writeString(file, "%PDF-1.7");
        when(contentService.findOwned("asset-1", TEACHER_ID)).thenReturn(saved);
        when(contentService.fileFor(saved)).thenReturn(file);
        mockMvc.perform(get("/api/content/asset-1/download").with(user(TEACHER_EMAIL)))
                .andExpect(status().isOk()).andExpect(content().bytes("%PDF-1.7".getBytes()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    void metadataEditAndDeleteUseAuthenticatedOwner() throws Exception {
        ContentMetadataRequest metadata = new ContentMetadataRequest("Updated", "Lesson", "", "English", "Grade 2", "Lesson Plan", List.of(), "TEACHER_ONLY");
        when(contentService.update("asset-1", TEACHER_ID, metadata)).thenReturn(asset());
        mockMvc.perform(put("/api/content/asset-1").header("Origin", "http://localhost:5173").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                          {"title":"Updated","category":"Lesson","description":"","subject":"English","grade":"Grade 2",
                           "materialType":"Lesson Plan","tags":[],"visibility":"TEACHER_ONLY","ownerId":"forged"}
                        """).with(user(TEACHER_EMAIL)).with(csrf()))
                .andExpect(status().isOk());
        verify(contentService).update("asset-1", TEACHER_ID, metadata);
        mockMvc.perform(delete("/api/content/asset-1").header("Origin", "http://localhost:5173").with(user(TEACHER_EMAIL)).with(csrf()))
                .andExpect(status().isOk());
        verify(contentService).delete("asset-1", TEACHER_ID);
    }

    @Test
    void unauthorizedAndCsrfRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/content")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/content/asset-1/download")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/content/asset-1").with(user(TEACHER_EMAIL))).andExpect(status().isForbidden());
    }

    @Test
    void serviceValidationHasAUserFriendlyMessage() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        when(contentService.store(org.mockito.ArgumentMatchers.eq(TEACHER_ID), org.mockito.ArgumentMatchers.any(ContentMetadataRequest.class), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a file to upload"));
        mockMvc.perform(multipart("/api/content").file(file).param("title", "Worksheet").param("category", "Worksheet")
                        .with(user(TEACHER_EMAIL)).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Choose a file to upload"));
    }

    @Test
    void missingUploadFieldsAndInvalidMetadataHaveClearErrors() throws Exception {
        mockMvc.perform(multipart("/api/content").param("category", "Worksheet")
                        .with(user(TEACHER_EMAIL)).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Provide valid material metadata and a file for uploads."));
        mockMvc.perform(put("/api/content/asset-1").contentType(MediaType.APPLICATION_JSON).content("{invalid")
                        .with(user(TEACHER_EMAIL)).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Provide valid material metadata and a file for uploads."));
    }
}
