package lk.smartplay.adaptive.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import lk.smartplay.adaptive.config.SecurityConfig;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;
import lk.smartplay.adaptive.service.*;

@WebMvcTest(LearningEvidenceController.class)
@Import({SecurityConfig.class,TeacherObservationService.class,HistoricalLessonService.class})
class LearningEvidenceControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean StudentService students;
    @MockitoBean TeacherObservationRepository observations;
    @MockitoBean HistoricalLessonRepository history;
    @MockitoBean TeacherAccountService accounts;
    @MockitoBean TeacherAccountRepository accountRepository;
    final String observation = """
        {"participation":"High","attentionLevel":"Medium","confidence":"Low","collaboration":"Good",
        "learningDifficulty":"None identified","learningStyle":"Visual learner","demonstratedSkills":["Sound awareness"],"ownerId":"forged"}
        """;
    @BeforeEach void setup() {
        when(accounts.accountIdForEmail("teacher@test")).thenReturn("owner");
        when(students.findById("s1","owner")).thenReturn(new Student("Test","Grade 1","owner"));
    }
    @Test void createAndRetrieveUseStudentAndAuthenticatedTeacher() throws Exception {
        when(observations.save(any())).thenAnswer(call -> call.getArgument(0)); when(history.save(any())).thenAnswer(call -> call.getArgument(0));
        mvc.perform(post("/api/students/s1/observations").with(user("teacher@test").roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(observation))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.studentId").value("s1")).andExpect(jsonPath("$.ownerId").doesNotExist());
        mvc.perform(post("/api/students/s1/history").with(user("teacher@test").roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lessonId\":\"LES001\",\"sequenceNumber\":1,\"completionStatus\":\"Completed\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.completedAt").exists());
        for (String route:List.of("observations","history")) mvc.perform(get("/api/students/s1/"+route).with(user("teacher@test").roles("TEACHER"))).andExpect(status().isOk());
        verify(observations).save(argThat(value -> value.getOwnerId().equals("owner")));
        verify(history).findByStudentIdAndOwnerIdOrderByOccurredAtDescIdDesc("s1","owner");
    }
    @Test void invalidMissingAndFutureFieldsReturn400WithoutWrites() throws Exception {
        for (String value:List.of("{}",observation.replace("High","Unknown"),observation.replace("\"ownerId\":\"forged\"","\"observedAt\":\"2999-01-01T00:00:00Z\""))) {
            mvc.perform(post("/api/students/s1/observations").with(user("teacher@test").roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(value)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/students/s1/history").with(user("teacher@test").roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lessonId\":\"\",\"sequenceNumber\":0,\"completionStatus\":\"Completed\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(observations,history);
    }
    @Test void unauthorizedStudentRoleForeignOwnerAndCsrfAreEnforced() throws Exception {
        when(students.findById("foreign","owner")).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        for (String route:List.of("observations","history")) {
            mvc.perform(get("/api/students/s1/"+route)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/students/s1/"+route).with(user("teacher@test").roles("STUDENT"))).andExpect(status().isForbidden());
            mvc.perform(get("/api/students/foreign/"+route).with(user("teacher@test").roles("TEACHER"))).andExpect(status().isNotFound());
            mvc.perform(post("/api/students/s1/"+route).with(user("teacher@test").roles("TEACHER"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(observations,history);
    }
}
