package lk.smartplay.adaptive.controller;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import lk.smartplay.adaptive.config.SecurityConfig;
import lk.smartplay.adaptive.repository.TeacherAccountRepository;
import lk.smartplay.adaptive.service.*;
@WebMvcTest(GradeOneBookImportController.class)
@Import(SecurityConfig.class)
class GradeOneBookImportControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean GradeOneBookImportService imports;
    @MockitoBean TeacherAccountService accounts;
    @MockitoBean TeacherAccountRepository repository;
    @Test void requiresTeacherRoleAndCsrf() throws Exception {
        mvc.perform(get("/api/content/import-grade-one")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/content/import-grade-one").with(user("student").roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/content/import-grade-one").with(user("teacher").roles("TEACHER")).contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isForbidden());
        verifyNoInteractions(imports);
    }
    @Test void usesSessionOwnerAndExistingContentNamespace() throws Exception {
        when(accounts.accountIdForEmail("teacher")).thenReturn("owner");
        when(imports.importBooks("owner",List.of())).thenReturn(List.of());
        mvc.perform(post("/api/content/import-grade-one").with(user("teacher").roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isOk());
        verify(imports).importBooks("owner",List.of());
    }
}
