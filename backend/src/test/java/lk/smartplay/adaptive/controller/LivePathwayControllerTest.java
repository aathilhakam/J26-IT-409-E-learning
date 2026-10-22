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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import lk.smartplay.adaptive.config.SecurityConfig;
import lk.smartplay.adaptive.repository.TeacherAccountRepository;
import lk.smartplay.adaptive.service.*;

@WebMvcTest(LivePathwayController.class)
@Import(SecurityConfig.class)
class LivePathwayControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean LivePathwayService pathways;
    @MockitoBean TeacherAccountService accounts;
    @MockitoBean TeacherAccountRepository accountRepository;

    @Test void liveRouteUsesAuthenticatedOwnerAndValidatesRequiredProfile() throws Exception {
        when(accounts.accountIdForEmail("teacher@test")).thenReturn("owner");
        when(pathways.generateStored("s1", "owner"))
                .thenReturn(new LivePathwayService.Result("No suitable validated activity", "Waiting", List.of()));
        mvc.perform(post("/api/recommendations/student/s1/pathway").with(user("teacher@test").roles("TEACHER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                  {}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.predictedOutcome").value("Waiting"))
                .andExpect(jsonPath("$.recommendations").isEmpty());
        verify(pathways).generateStored("s1", "owner");
        mvc.perform(post("/api/recommendations/student/s1/pathway").with(user("teacher@test").roles("TEACHER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"features\":{\"injected\":1}}"))
                .andExpect(status().isBadRequest());
    }
    @Test void bothMutationRoutesRequireLoginAndCsrf() throws Exception {
        for (String path : List.of("/api/recommendations/student/s1/pathway", "/api/recommendations/r1/assign")) {
            mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post(path).with(user("teacher@test").roles("TEACHER"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(pathways);
    }
}
