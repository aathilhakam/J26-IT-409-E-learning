package lk.smartplay.adaptive.model;

import java.time.Instant;
import java.util.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import lk.smartplay.adaptive.service.LiveFeatureService;

/** One durable evaluation per owner/student/completed-lesson milestone. */
@Document("recommendation_milestones")
public class RecommendationMilestone {
    @Id public String id;
    public String ownerId,studentId,status,message,predictedOutcome,modelVersion;
    public int milestone;
    public Instant createdAt,updatedAt,leaseUntil;
    public List<String> completedMaterialIds=List.of(),recommendationIds=List.of(),evidenceIds=List.of();
    public Map<String,Double> probabilities=Map.of();
    public boolean inferenceReady;
    public List<LearningRecommendation> plannedRecommendations=List.of();
    public LiveFeatureService.Snapshot featureSnapshot;
    public ThreeLessonPerformance threeLessonPerformance;

    public record LessonScore(String materialId,String worksheetTitle,String subject,java.time.Instant completedAt,
            java.time.LocalDate assessmentDate,double score,double maxScore,double percentage,String teacherFeedback,String assessmentResultId) {}
    public record ThreeLessonPerformance(List<LessonScore> lessons,double averagePercentage,double changePercentagePoints,String trend) {}
}
