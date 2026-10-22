package lk.smartplay.adaptive.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import lk.smartplay.adaptive.config.SecurityConfig;
import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.LearningRecordRepository;
import lk.smartplay.adaptive.repository.StudentRepository;
import lk.smartplay.adaptive.repository.TeacherAccountRepository;
import lk.smartplay.adaptive.service.RecommendationService;
import lk.smartplay.adaptive.service.StudentService;
import lk.smartplay.adaptive.service.TeacherAccountService;

@WebMvcTest(StudentController.class)
@Import({SecurityConfig.class, StudentService.class})
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class CreativityRecordControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private StudentRepository students;
    @MockitoBean private LearningRecordRepository records;
    @MockitoBean private TeacherAccountRepository accounts;
    @MockitoBean private TeacherAccountService accountService;
    @MockitoBean private RecommendationService recommendations;

    private static final String PAYLOAD = """
            {"domain":"Creativity","score":6.25,"maxScore":10,"notes":"Teacher observation",
             "creativityAssessment":{"teacherScoreScale":10,"activity":"Creative Coloring","prompt":"Treehouse",
               "teacherScores":{"originality":10,"imagination":1,"elaboration":6,"storytelling":8},
               "imageAnalysis":{"scores":{"originality":2.3},"score_scale":5,
                 "colour_analysis":{"dominant_colour":"Red","category_count":1}},"voiceRecorded":false}}
            """;

    @BeforeEach
    void setup() {
        when(accountService.accountIdForEmail("teacher@test.example")).thenReturn("teacher-1");
        when(students.findByIdAndOwnerId("student-1", "teacher-1"))
                .thenReturn(Optional.of(new Student("Amaya", "Grade 1", "teacher-1")));
    }

    @Test
    void postAndGetPreserveIndependentTeacherAndImageScores() throws Exception {
        var saved = new AtomicReference<LearningRecord>();
        when(records.save(any(LearningRecord.class))).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return saved.get();
        });
        mvc.perform(post("/api/students/student-1/records").with(user("teacher@test.example")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxScore").value(10))
                .andExpect(jsonPath("$.creativityAssessment.teacherScores.originality").value(10))
                .andExpect(jsonPath("$.creativityAssessment.imageAnalysis.scores.originality").value(2.3));
        when(records.findByStudentIdOrderByAssessedAtDesc("student-1")).thenReturn(List.of(saved.get()));
        mvc.perform(get("/api/students/student-1/records").with(user("teacher@test.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].creativityAssessment.teacherScores.storytelling").value(8))
                .andExpect(jsonPath("$[0].creativityAssessment.teacherScoreScale").value(10))
                .andExpect(jsonPath("$[0].creativityAssessment.imageAnalysis.score_scale").value(5))
                .andExpect(jsonPath("$[0].creativityAssessment.imageAnalysis.colour_analysis.dominant_colour").value("Red"));
    }

    @Test
    void rejectsFractionalMissingAndOutOfRangeTeacherScoresThroughHttp() throws Exception {
        for (String replacement : new String[] { "3.5", "0", "11", "null" }) {
            mvc.perform(post("/api/students/student-1/records").with(user("teacher@test.example")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PAYLOAD.replace("\"originality\":10", "\"originality\":" + replacement)))
                    .andExpect(status().isBadRequest());
        }
    }
}
