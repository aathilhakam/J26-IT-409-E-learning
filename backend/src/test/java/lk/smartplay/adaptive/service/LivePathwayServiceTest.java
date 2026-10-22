package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.dto.*;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

@ExtendWith(MockitoExtension.class)
class LivePathwayServiceTest {
    @Mock StudentService students;
    @Mock AssessmentResultRepository assessments;
    @Mock LearningRecordRepository records;
    @Mock LearningRecommendationRepository recommendations;
    @Mock ContentAssetRepository materials;
    @Mock AIRecommendationClient ai;
    @InjectMocks LivePathwayService service;
    Student student;
    LivePathwayRequest request = new LivePathwayRequest(Map.of("actual", "features"), List.of("Sound awareness"), List.of(), Map.of());

    @BeforeEach void setup() {
        student = new Student("Learner", "Grade 1", "owner");
        org.springframework.test.util.ReflectionTestUtils.setField(student, "id", "s1");
        when(students.findById("s1", "owner")).thenReturn(student);
    }
    void evidence() {
        var result = new AssessmentResult(); result.setStudentId("s1"); result.setAssessmentType("Phonics"); result.setScore(2); result.setMaxScore(5);
        org.springframework.test.util.ReflectionTestUtils.setField(result, "id", "a1");
        when(assessments.findByStudentIdOrderByAssessedAtDesc("s1")).thenReturn(List.of(result));
        when(records.findByStudentIdOrderByAssessedAtDesc("s1")).thenReturn(List.of());
    }
    LivePathwayResponse prediction(List<LivePathwayResponse.Activity> activities) {
        return new LivePathwayResponse("Needs Practice", Map.of("Needs Practice", 0.7, "Successful", 0.2, "Waiting", 0.1), "model-hash", "Recommendation available", activities);
    }
    LivePathwayResponse.Activity activity() {
        return new LivePathwayResponse.Activity("L1", "English", "Phonics", "Read sounds", "Sound cards", "Beginner", "Sound awareness", "assessment:a1", "Phonics evidence 40%");
    }

    @Test void persistsPendingRecommendationWithRealEvidenceAndDeduplicatesRequest() {
        evidence(); when(ai.selectPathway(any())).thenReturn(prediction(List.of(activity())));
        when(recommendations.save(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.generate("s1", "owner", request);
        var saved = result.recommendations().get(0);
        assertEquals(LearningRecommendation.Status.PENDING, saved.getStatus());
        assertEquals("L1", saved.getLessonId()); assertEquals("assessment:a1", saved.getEvidenceId());
        assertEquals("Needs Practice", saved.getPredictedOutcome()); assertEquals(0.7, saved.getConfidence());
        ArgumentCaptor<Map<String,Object>> profile = ArgumentCaptor.captor();
        verify(ai).selectPathway(profile.capture());
        assertEquals("Grade 1", profile.getValue().get("grade"));
        assertTrue(profile.getValue().get("needs").toString().contains("40.0"));
        when(recommendations.findByStudentIdAndRequestKeyAndLessonId("s1", saved.getRequestKey(), "L1")).thenReturn(Optional.of(saved));
        assertSame(saved, service.generate("s1", "owner", request).recommendations().get(0));
        verify(recommendations, times(1)).save(any());
    }
    @Test void noMatchDoesNotPersistAndMissingEvidenceDoesNotCallPython() {
        assertThrows(ResponseStatusException.class, () -> service.generate("s1", "owner", request));
        verifyNoInteractions(ai, recommendations);
        evidence(); when(ai.selectPathway(any())).thenReturn(prediction(List.of()));
        assertEquals("No suitable validated activity", service.generate("s1", "owner", request).status());
        verifyNoInteractions(recommendations);
    }
    @Test void invalidPythonEvidenceAndForeignMaterialDoNotPersist() {
        evidence(); var invalid = new LivePathwayResponse.Activity("L1", "English", "Phonics", "Objective", "Activity", "Basic", "Skill", "foreign-evidence", "Reason");
        when(ai.selectPathway(any())).thenReturn(prediction(List.of(invalid)));
        assertThrows(ResponseStatusException.class, () -> service.generate("s1", "owner", request));
        var linked = new LivePathwayRequest(request.features(), request.demonstratedSkills(), List.of(), Map.of("L1", "foreign-file"));
        assertThrows(ResponseStatusException.class, () -> service.generate("s1", "owner", linked));
        verifyNoInteractions(recommendations);
    }
    @Test void ownerFailureStopsBeforeEvidenceOrPrediction() {
        when(students.findById("s1", "owner")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThrows(ResponseStatusException.class, () -> service.generate("s1", "owner", request));
        verifyNoInteractions(ai, records, assessments, recommendations);
    }
    @Test void assignmentRequiresApprovalAndOwnedMaterialAndIsIdempotent() {
        var recommendation = new LearningRecommendation("s1", "English", "Phonics", "Sound cards", "Reason", 0.7);
        recommendation.liveDetails("L1", "hash", "Needs Practice", "PERSONALIZED", Map.of(), "a1", "key", "file");
        when(recommendations.findById("r1")).thenReturn(Optional.of(recommendation));
        assertThrows(ResponseStatusException.class, () -> service.assign("r1", "owner"));
        verifyNoInteractions(materials);
        recommendation.review(LearningRecommendation.Status.APPROVED, null, null, "Checked");
        var material = new ContentAsset("owner", "Sounds", "Lesson", "", "sounds.pdf", "private.pdf", "application/pdf", 10);
        when(materials.findByIdAndOwnerId("file", "owner")).thenReturn(Optional.of(material));
        service.assign("r1", "owner"); service.assign("r1", "owner");
        assertEquals(List.of("s1"), material.getAssignedStudentIds());
        verify(materials, times(2)).save(material);
    }

    @Test void teacherDecisionsRejectPendingAndSupportRejectionWithoutChangingModelDetails() {
        var recommendation = new LearningRecommendation("s1", "English", "Phonics", "Activity", "Reason", 0.7);
        recommendation.liveDetails("L1", "hash", "Needs Practice", "PERSONALIZED", Map.of("Needs Practice", 0.7), "a1", "key", null);
        when(recommendations.findById("r1")).thenReturn(Optional.of(recommendation));
        when(recommendations.save(recommendation)).thenReturn(recommendation);
        var review = new RecommendationService(students, records, recommendations);
        var rejected = review.review("r1", new ReviewRecommendationRequest(LearningRecommendation.Status.REJECTED, "Edited", "Modified", "Not suitable"), "owner");
        assertEquals(LearningRecommendation.Status.REJECTED, rejected.getStatus());
        assertEquals("Edited", rejected.getActivityTitle()); assertEquals("hash", rejected.getModelVersion());
        assertEquals("Not suitable", rejected.getTeacherFeedback());
        assertThrows(ResponseStatusException.class, () -> review.review("r1", new ReviewRecommendationRequest(LearningRecommendation.Status.PENDING, null, null, null), "owner"));
    }

    @Test void newestEvidencePerAreaReplacesOlderResults() {
        var old = new AssessmentResult(); old.setAssessmentType("Phonics"); old.setScore(1); old.setMaxScore(5); old.setAssessedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(old, "id", "old");
        var recent = new AssessmentResult(); recent.setAssessmentType("Phonics"); recent.setScore(4); recent.setMaxScore(5); recent.setAssessedAt(java.time.Instant.parse("2026-02-01T00:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(recent, "id", "recent");
        when(assessments.findByStudentIdOrderByAssessedAtDesc("s1")).thenReturn(List.of(old, recent));
        when(ai.selectPathway(any())).thenAnswer(call -> {
            Map<String,Object> profile = call.getArgument(0);
            List<?> needs = (List<?>) profile.get("needs");
            Map<?,?> need = (Map<?,?>) needs.get(0);
            assertEquals(1, needs.size()); assertEquals("assessment:recent", need.get("assessment_id"));
            assertEquals(80.0, need.get("score_percentage"));
            return prediction(List.of());
        });
        service.generate("s1", "owner", request);
    }
}
