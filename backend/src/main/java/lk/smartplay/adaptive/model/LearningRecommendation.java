package lk.smartplay.adaptive.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

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
