package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.dto.ReviewRecommendationRequest;
import lk.smartplay.adaptive.model.LearningRecommendation;
import lk.smartplay.adaptive.model.LearningRecommendation.Status;
import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.repository.LearningRecommendationRepository;
import lk.smartplay.adaptive.repository.LearningRecordRepository;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class RecommendationServiceTest {
    private static final String OWNER_ID = "teacher-1";
    @Mock
    private StudentService studentService;

    @Mock
    private LearningRecordRepository recordRepository;

    @Mock
    private LearningRecommendationRepository recommendationRepository;

    @InjectMocks
    private RecommendationService recommendationService;

    @Test
    void generatesAnExplainableSuggestionForTheLowestScoringArea() {
        when(recordRepository.findByStudentIdOrderByAssessedAtDesc("student-1"))
                .thenReturn(List.of(
                        new LearningRecord("student-1", "Numeracy", 4, 5, ""),
                        new LearningRecord("student-1", "Motor skills", 2, 5, "")));
        when(recommendationRepository.save(any(LearningRecommendation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LearningRecommendation recommendation = recommendationService.generate("student-1", OWNER_ID);

        assertEquals("Motor skills", recommendation.getDomain());
        assertEquals("Trace, cut, and create", recommendation.getActivityTitle());
        assertEquals(Status.PENDING, recommendation.getStatus());
        assertTrue(recommendation.getReason().contains("40%"));
    }

    @Test
    void requiresLearningRecordsBeforeGeneratingAPathway() {
        when(recordRepository.findByStudentIdOrderByAssessedAtDesc("student-1")).thenReturn(List.of());

        assertThrows(ResponseStatusException.class, () -> recommendationService.generate("student-1", OWNER_ID));
    }

    @Test
    void teacherReviewCanEditAndApproveSuggestion() {
        LearningRecommendation recommendation = new LearningRecommendation(
                "student-1", "Literacy", "Original title", "Original activity", "Reason", 0.8);
        when(recommendationRepository.findById("recommendation-1")).thenReturn(java.util.Optional.of(recommendation));
        when(recommendationRepository.save(recommendation)).thenReturn(recommendation);

        LearningRecommendation reviewed = recommendationService.review(
                "recommendation-1",
                new ReviewRecommendationRequest(Status.APPROVED, "Teacher's activity", "Try together.", "Use picture cards"),
                OWNER_ID);

        assertEquals(Status.APPROVED, reviewed.getStatus());
        assertEquals("Teacher's activity", reviewed.getActivityTitle());
        assertEquals("Use picture cards", reviewed.getTeacherFeedback());
        verify(recommendationRepository).save(recommendation);
    }

    @Test
    void teacherCannotReviewAnotherTeachersRecommendation() {
        LearningRecommendation recommendation = new LearningRecommendation(
                "student-1", "Literacy", "Activity", "Description", "Reason", 0.8);
        when(recommendationRepository.findById("recommendation-1")).thenReturn(java.util.Optional.of(recommendation));
        when(studentService.findById("student-1", OWNER_ID))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThrows(
                ResponseStatusException.class,
                () -> recommendationService.review(
                        "recommendation-1",
                        new ReviewRecommendationRequest(Status.APPROVED, null, null, null),
                        OWNER_ID));
        verify(recommendationRepository, never()).save(recommendation);
    }
}
