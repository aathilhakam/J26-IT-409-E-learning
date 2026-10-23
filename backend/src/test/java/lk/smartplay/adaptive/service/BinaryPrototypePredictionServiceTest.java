package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.model.BinaryPrototypeFeatureSnapshot;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.BinaryPrototypeFeatureSnapshotRepository;

class BinaryPrototypePredictionServiceTest {
    private BinaryPrototypeFeatureService features;
    private BinaryPrototypeFeatureSnapshotRepository snapshots;
    private StudentService students;
    private AIRecommendationClient ai;
    private BinaryPrototypePredictionService service;

    @BeforeEach void setUp() {
        features = mock(BinaryPrototypeFeatureService.class);
        snapshots = mock(BinaryPrototypeFeatureSnapshotRepository.class);
        students = mock(StudentService.class);
        ai = mock(AIRecommendationClient.class);
        when(students.findById("student-1", "teacher-1")).thenReturn(new Student("Amaya", "Grade 1", "teacher-1"));
        service = new BinaryPrototypePredictionService(features, snapshots, students, ai);
    }

    @Test void importedSixFeatureTemplateIsCompletedFromTeacherObservationAndUsed() {
        var imported = sixFeatures();
        var completed = tenFeatures();
        var savedSnapshot = snapshot("snapshot-1", imported);
        when(snapshots.findTopByStudentIdAndOwnerIdOrderByCreatedAtDesc("student-1", "teacher-1"))
                .thenReturn(Optional.of(savedSnapshot));
        when(features.completeImportedTemplate("student-1", "teacher-1", imported))
                .thenReturn(new BinaryPrototypeFeatureService.Snapshot(completed, List.of("observation:obs-1")));

        service.predict("student-1", "teacher-1");

        verify(ai).predictBinaryPrototype(completed, List.of("prototype_snapshot:snapshot-1", "observation:obs-1"));
        verify(features, never()).generate(anyString(), anyString());
    }

    @Test void noImportedSnapshotExplainsBothMissingTemplateAndAutomaticActivityEvidence() {
        when(snapshots.findTopByStudentIdAndOwnerIdOrderByCreatedAtDesc("student-1", "teacher-1"))
                .thenReturn(Optional.empty());
        when(features.generate("student-1", "teacher-1")).thenThrow(new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "Binary prototype prediction unavailable; missing or invalid data: at least one saved activity record"));

        var error = assertThrows(ResponseStatusException.class, () -> service.predict("student-1", "teacher-1"));

        assertEquals(422, error.getStatusCode().value());
        assertTrue(error.getReason().contains("No imported experimental feature template was found"));
        assertTrue(error.getReason().contains("at least one saved activity record"));
        assertTrue(error.getReason().contains("saved teacher feedback"));
        verify(ai, never()).predictBinaryPrototype(anyMap(), anyList());
    }

    @Test void legacyTenFeatureSnapshotIsSentDirectlyWithoutReplacingItsValues() {
        var all = tenFeatures();
        var savedSnapshot = snapshot("snapshot-2", all);
        when(snapshots.findTopByStudentIdAndOwnerIdOrderByCreatedAtDesc("student-1", "teacher-1"))
                .thenReturn(Optional.of(savedSnapshot));

        service.predict("student-1", "teacher-1");

        verify(ai).predictBinaryPrototype(all, List.of("prototype_snapshot:snapshot-2"));
        verifyNoInteractions(features);
    }

    @Test void studentOwnershipIsCheckedBeforeSnapshotLookup() {
        when(students.findById("student-1", "teacher-1"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));

        assertThrows(ResponseStatusException.class, () -> service.predict("student-1", "teacher-1"));

        verifyNoInteractions(snapshots, features);
        verify(ai, never()).predictBinaryPrototype(anyMap(), anyList());
    }

    private static BinaryPrototypeFeatureSnapshot snapshot(String id, Map<String, Object> values) {
        var snapshot = mock(BinaryPrototypeFeatureSnapshot.class);
        when(snapshot.getId()).thenReturn(id);
        when(snapshot.getFeatures()).thenReturn(values);
        return snapshot;
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

    private static Map<String, Object> tenFeatures() {
        var values = sixFeatures();
        var all = new LinkedHashMap<String, Object>();
        all.put("Average_Attempts", values.get("Average_Attempts"));
        all.put("Total_Activities", values.get("Total_Activities"));
        all.put("Completion_Rate", values.get("Completion_Rate"));
        all.put("Participation", "High");
        all.put("Attention_Level", "Medium");
        all.put("Collaboration", "Good");
        all.put("Learning_Difficulty", "None identified");
        all.put("Historical_Lessons", values.get("Historical_Lessons"));
        all.put("Completed_Historical_Lessons", values.get("Completed_Historical_Lessons"));
        all.put("Historical_Completion_Rate", values.get("Historical_Completion_Rate"));
        return all;
    }
}
