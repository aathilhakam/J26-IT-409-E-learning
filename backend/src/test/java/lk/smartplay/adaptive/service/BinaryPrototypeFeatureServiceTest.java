package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.model.TeacherObservation;
import lk.smartplay.adaptive.repository.AssessmentResultRepository;
import lk.smartplay.adaptive.repository.ClassroomActivityRepository;
import lk.smartplay.adaptive.repository.LearningRecordRepository;

class BinaryPrototypeFeatureServiceTest {
    private StudentService students;
    private TeacherObservationService observations;
    private BinaryPrototypeFeatureService service;

    @BeforeEach void setUp() {
        students = mock(StudentService.class);
        observations = mock(TeacherObservationService.class);
        when(students.findById("student-1", "teacher-1"))
                .thenReturn(new Student("Amaya", "Grade 1", "teacher-1"));
        service = new BinaryPrototypeFeatureService(students, mock(LearningRecordRepository.class),
                mock(ClassroomActivityRepository.class), mock(AssessmentResultRepository.class), observations,
                mock(HistoricalLessonService.class));
    }

    @Test void sixWorkbookValuesAndFourSavedObservationCategoriesFormTenFeatureSnapshot() {
        var observation = new TeacherObservation("student-1", "teacher-1", "High", "Medium", "Low", "Good",
                "None identified", "Visual learner", List.of(), Instant.now());
        when(observations.findForStudent("student-1", "teacher-1")).thenReturn(List.of(observation));

        var result = service.completeImportedTemplate("student-1", "teacher-1", sixFeatures());

        assertEquals(BinaryPrototypeFeatureService.FEATURE_ORDER, List.copyOf(result.features().keySet()));
        assertEquals(10, result.features().size());
        assertEquals(4L, result.features().get("Total_Activities"));
        assertEquals("High", result.features().get("Participation"));
        assertEquals("Medium", result.features().get("Attention_Level"));
        assertEquals("Good", result.features().get("Collaboration"));
        assertEquals("None identified", result.features().get("Learning_Difficulty"));
        assertEquals(List.of("observation:" + observation.getId()), result.sourceRecordIds());
    }

    @Test void missingTeacherObservationDoesNotInventFourCategoricalFeatures() {
        when(observations.findForStudent("student-1", "teacher-1")).thenReturn(List.of());

        var error = assertThrows(ResponseStatusException.class,
                () -> service.completeImportedTemplate("student-1", "teacher-1", sixFeatures()));

        assertEquals(422, error.getStatusCode().value());
        assertTrue(error.getReason().contains("saved teacher feedback"));
    }

    @Test void malformedPersistedWorkbookValueHasSpecificDiagnostic() {
        when(observations.findForStudent("student-1", "teacher-1")).thenReturn(List.of(
                new TeacherObservation("student-1", "teacher-1", "High", "Medium", "Low", "Good",
                        "None identified", "Visual learner", List.of(), Instant.now())));
        var invalid = sixFeatures();
        invalid.put("Completion_Rate", 101.0);

        var error = assertThrows(ResponseStatusException.class,
                () -> service.completeImportedTemplate("student-1", "teacher-1", invalid));

        assertEquals(422, error.getStatusCode().value());
        assertTrue(error.getReason().contains("Completion_Rate"));
    }

    private static Map<String, Object> sixFeatures() {
        var values = new LinkedHashMap<String, Object>();
        values.put("Average_Attempts", 1.25);
        values.put("Total_Activities", 4L);
        values.put("Completion_Rate", 75.0);
        values.put("Historical_Lessons", 3L);
        values.put("Completed_Historical_Lessons", 2L);
        values.put("Historical_Completion_Rate", 66.7);
        return values;
    }
}
