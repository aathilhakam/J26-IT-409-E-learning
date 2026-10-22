package lk.smartplay.adaptive.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@org.springframework.data.mongodb.core.index.CompoundIndex(name = "unique_live_pathway", def = "{'studentId':1,'requestKey':1,'lessonId':1}", unique = true, partialFilter = "{'source':'RANDOM_FOREST'}")
@Document("learning_recommendations")
public class LearningRecommendation {
    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    @Id
    private String id;
    private String studentId;
    private String domain;
    private String activityTitle;
    private String description;
    private String reason;
    private double confidence;
    private Status status;
    private String teacherFeedback;
    private Instant createdAt;
    private Instant reviewedAt;

    private String learningObjective,curriculumDifficulty,prerequisites;
    public String getLearningObjective(){return learningObjective;}
    public String getCurriculumDifficulty(){return curriculumDifficulty;}
    public String getPrerequisites(){return prerequisites;}
    public void curriculumDetails(String objective,String difficulty,String required){learningObjective=objective;curriculumDifficulty=difficulty;prerequisites=required;}
    private Instant assignedAt;
    public Instant getAssignedAt() { return assignedAt; }
    public void markAssigned() { if(assignedAt==null)assignedAt=Instant.now(); }
    private String teacherOwnerId;
    private Integer completionMilestone;
    private Instant featuresGeneratedAt;
    private java.util.List<String> sourceEvidenceIds;
    private RecommendationMilestone.ThreeLessonPerformance threeLessonPerformance;
    public String getTeacherOwnerId() { return teacherOwnerId; }
    public Integer getCompletionMilestone() { return completionMilestone; }
    public Instant getFeaturesGeneratedAt() { return featuresGeneratedAt; }
    public java.util.List<String> getSourceEvidenceIds() { return sourceEvidenceIds == null ? java.util.List.of() : sourceEvidenceIds; }
    public RecommendationMilestone.ThreeLessonPerformance getThreeLessonPerformance() { return threeLessonPerformance; }
    public void milestonePerformance(RecommendationMilestone.ThreeLessonPerformance performance) { this.threeLessonPerformance=performance; }
    public void milestoneDetails(String owner,int milestone,Instant at,java.util.List<String> evidence) { id="milestone-"+java.util.UUID.nameUUIDFromBytes((owner+":"+studentId+":"+milestone+":"+lessonId).getBytes(java.nio.charset.StandardCharsets.UTF_8)); teacherOwnerId=owner;completionMilestone=milestone;featuresGeneratedAt=at;sourceEvidenceIds=java.util.List.copyOf(evidence); }
    private String source;
    private String lessonId;
    private String modelVersion;
    private String predictedOutcome;
    private String pathwayType;
    private java.util.Map<String, Double> predictionProbabilities;
    private String evidenceId;
    private String requestKey;
    private String materialId;

    public void liveDetails(String lessonId, String modelVersion, String predictedOutcome, String pathwayType,
            java.util.Map<String, Double> probabilities, String evidenceId, String requestKey, String materialId) {
        this.source = "RANDOM_FOREST";
        this.lessonId = lessonId;
        this.modelVersion = modelVersion;
        this.predictedOutcome = predictedOutcome;
        this.pathwayType = pathwayType;
        this.predictionProbabilities = java.util.Map.copyOf(probabilities);
        this.evidenceId = evidenceId;
        this.requestKey = requestKey;
        this.materialId = materialId;
    }
    public String getSource() { return source; }
    public String getLessonId() { return lessonId; }
    public String getModelVersion() { return modelVersion; }
    public String getPredictedOutcome() { return predictedOutcome; }
    public String getPathwayType() { return pathwayType; }
    public java.util.Map<String, Double> getPredictionProbabilities() { return predictionProbabilities; }
    public String getEvidenceId() { return evidenceId; }
    public String getRequestKey() { return requestKey; }
    public String getMaterialId() { return materialId; }
    public void linkMaterial(String materialId) { this.materialId = materialId; }

    public LearningRecommendation() {
    }

    public LearningRecommendation(
            String studentId,
            String domain,
            String activityTitle,
            String description,
            String reason,
            double confidence) {
        this.studentId = studentId;
        this.domain = domain;
        this.activityTitle = activityTitle;
        this.description = description;
        this.reason = reason;
        this.confidence = confidence;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getDomain() {
        return domain;
    }

    public String getActivityTitle() {
        return activityTitle;
    }

    public String getDescription() {
        return description;
    }

    public String getReason() {
        return reason;
    }

    public double getConfidence() {
        return confidence;
    }

    public Status getStatus() {
        return status;
    }

    public String getTeacherFeedback() {
        return teacherFeedback;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void review(Status status, String activityTitle, String description, String teacherFeedback) {
        this.status = status;
        if (activityTitle != null && !activityTitle.isBlank()) {
            this.activityTitle = activityTitle.trim();
        }
        if (description != null && !description.isBlank()) {
            this.description = description.trim();
        }
        this.teacherFeedback = teacherFeedback == null ? "" : teacherFeedback.trim();
        this.reviewedAt = Instant.now();
    }
}
