package lk.smartplay.adaptive.service;

import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

@ExtendWith(MockitoExtension.class)
class LiveFeatureServiceTest {
    @Mock StudentService students;
    @Mock LearningRecordRepository records;
    @Mock AssessmentResultRepository assessments;
    @Mock TeacherObservationService observations;
    @Mock HistoricalLessonService history;
    @InjectMocks LiveFeatureService service;
    @BeforeEach void setup() { when(students.findById("s1", "owner")).thenReturn(new Student("Test", "Grade 1", "owner")); }
    void evidence() {
        var first = new LearningRecord("s1", "Numbers", 4, 5, ""); first.activityDetails(1, 10.0, "COMPLETED");
        var second = new LearningRecord("s1", "Numbers", 2, 5, ""); second.activityDetails(3, 20.0, "IN_PROGRESS");
        when(records.findByStudentIdOrderByAssessedAtDesc("s1")).thenReturn(List.of(first, second));
        var a = new AssessmentResult(); Map<String,Double> measures = new HashMap<>();
        LiveFeatureService.ASSESSMENT_MEASURES.forEach(key -> measures.put(key, 50.0)); measures.put("Post_Test", 70.0); a.setMeasures(measures);
        when(assessments.findByStudentIdOrderByAssessedAtDesc("s1")).thenReturn(List.of(a));
        when(observations.findForStudent("s1", "owner")).thenReturn(List.of(new TeacherObservation("s1", "owner", "High", "Medium", "Low", "Needs improvement", "None identified", "Visual learner", List.of("Number sequence knowledge"), Instant.now())));
        when(history.findForStudent("s1", "owner")).thenReturn(List.of(new HistoricalLesson("s1", "owner", "LES001", 1, "Completed", Instant.now()), new HistoricalLesson("s1", "owner", "LES002", 3, "In Progress", Instant.now())));
    }
    @Test void all28FeaturesMatchNotebookAggregationsIncludingObservationAndHistory() {
        evidence(); var snapshot = service.generate("s1", "owner"); var f = snapshot.features();
        assertEquals(LiveFeatureService.FEATURE_ORDER, new ArrayList<>(f.keySet())); assertEquals(28, f.size());
        assertEquals(60.0, f.get("Average_Score")); assertEquals(2.0, f.get("Average_Attempts")); assertEquals(15.0, f.get("Average_Time_Taken"));
        assertEquals(50.0, f.get("Completion_Rate")); assertEquals(20.0, f.get("Test_Improvement"));
        assertEquals(3, f.get("Participation_Score")); assertEquals(2, f.get("Attention_Score")); assertEquals(1, f.get("Confidence_Score"));
        assertEquals(2, f.get("Collaboration_Score")); // Exact notebook mapping; Fair=1, Needs improvement=2.
        assertEquals(2, f.get("Historical_Lessons")); assertEquals(2.0, f.get("Average_Sequence_No")); assertEquals(1L, f.get("Completed_Historical_Lessons")); assertEquals(50.0, f.get("Historical_Completion_Rate"));
        assertEquals(List.of("LES001"), snapshot.completedLessonIds()); assertEquals(List.of("Number sequence knowledge"), snapshot.demonstratedSkills());
    }
    @Test void missingDataAndLegacyActivitiesReturnSpecific422InsteadOfInventedFeatures() {
        var error = assertThrows(ResponseStatusException.class, () -> service.generate("s1", "owner"));
        assertEquals(422, error.getStatusCode().value()); assertTrue(error.getReason().contains("teacher observation"));
        evidence(); when(records.findByStudentIdOrderByAssessedAtDesc("s1")).thenReturn(List.of(new LearningRecord("s1", "Numbers", 4, 5, "legacy")));
        assertTrue(assertThrows(ResponseStatusException.class, () -> service.generate("s1", "owner")).getReason().contains("including older records"));
    }
    @Test void foreignOwnerCannotTriggerAnySourceReads() {
        when(students.findById("s1", "owner")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThrows(ResponseStatusException.class, () -> service.generate("s1", "owner")); verifyNoInteractions(records, assessments, observations, history);
    }
    @Test void separateUnpairedTestsDoNotInventImprovement() {
        evidence(); var pre = new AssessmentResult(); pre.setMeasures(Map.of("Pre_Test", 20.0));
        var post = new AssessmentResult(); var values = new HashMap<String,Double>(); LiveFeatureService.ASSESSMENT_MEASURES.forEach(key -> values.put(key, 50.0)); values.remove("Pre_Test"); post.setMeasures(values);
        when(assessments.findByStudentIdOrderByAssessedAtDesc("s1")).thenReturn(List.of(pre, post));
        assertTrue(assertThrows(ResponseStatusException.class, () -> service.generate("s1", "owner")).getReason().contains("paired"));
    }
}
