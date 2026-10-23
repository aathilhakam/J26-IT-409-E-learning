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

@WebMvcTest({StudentController.class, AssessmentResultController.class})
@Import({SecurityConfig.class, StudentService.class, AssessmentResultService.class})
class RecordSeparationTest {
    @Autowired MockMvc mvc;
    @MockitoBean StudentRepository students;
    @MockitoBean LearningRecordRepository records;
    @MockitoBean ClassroomActivityRepository classroomActivities;
    @MockitoBean AssessmentResultRepository assessments;
    @MockitoBean TeacherAccountRepository accounts;
    @MockitoBean TeacherAccountService accountService;
    @MockitoBean RecommendationService recommendations;
    @MockitoBean StudentExcelImportService imports;

    @BeforeEach void setup() {
        when(accountService.accountIdForEmail("teacher@test")).thenReturn("owner");
        var student = new Student("Learner", "Grade 1", "owner");
        org.springframework.test.util.ReflectionTestUtils.setField(student, "id", "s1");
        when(students.findByIdAndOwnerId("s1", "owner")).thenReturn(Optional.of(student));
    }

    @Test void assessmentPostOnlyWritesAssessmentCollection() throws Exception {
        when(assessments.save(any())).thenAnswer(call -> call.getArgument(0));
        mvc.perform(post("/api/students/s1/assessments").with(user("teacher@test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                  {"sourceComponent":"MOTOR_SKILLS","assessmentType":"PENCIL_CONTROL","assessmentId":"a1",
                   "score":4,"maxScore":5,"completionStatus":"COMPLETED"}
                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.studentId").value("s1"));
        verify(assessments).save(any(AssessmentResult.class));
        verifyNoInteractions(records);
    }

    @Test void activityPostOnlyWritesActivityCollection() throws Exception {
        when(records.save(any())).thenAnswer(call -> call.getArgument(0));
        mvc.perform(post("/api/students/s1/records").with(user("teacher@test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                  {"domain":"Motor skills","score":4,"maxScore":5,"notes":"Observed"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.studentId").value("s1"));
        verify(records).save(any(LearningRecord.class));
        verifyNoInteractions(assessments);
    }

    @Test void retrievalIsStudentScopedAndNeverWritesEitherCollection() throws Exception {
        when(assessments.findByStudentIdOrderByAssessedAtDesc("s1")).thenReturn(List.of());
        when(records.findByStudentIdOrderByAssessedAtDesc("s1")).thenReturn(List.of());
        for (String suffix : List.of("assessments", "records")) {
            mvc.perform(get("/api/students/s1/" + suffix).with(user("teacher@test"))).andExpect(status().isOk());
        }
        verify(assessments).findByStudentIdOrderByAssessedAtDesc("s1");
        verify(records).findByStudentIdOrderByAssessedAtDesc("s1");
        verify(assessments, never()).save(any()); verify(records, never()).save(any());
    }

    @Test void foreignStudentAndUnauthenticatedRequestsCannotReadOrWrite() throws Exception {
        for (String suffix : List.of("assessments", "records")) {
            mvc.perform(get("/api/students/foreign/" + suffix).with(user("teacher@test"))).andExpect(status().isNotFound());
            mvc.perform(get("/api/students/s1/" + suffix)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/students/s1/" + suffix).with(user("teacher@test"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(records, assessments);
    }

    @Test void foreignStudentPostsCannotWriteEitherCollection() throws Exception {
        mvc.perform(post("/api/students/foreign/records").with(user("teacher@test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"domain\":\"Math\",\"score\":1,\"maxScore\":5}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/students/foreign/assessments").with(user("teacher@test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sourceComponent\":\"OTHER\",\"assessmentId\":\"a\",\"score\":1,\"maxScore\":5,\"completionStatus\":\"COMPLETED\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(records, assessments);
    }
}
