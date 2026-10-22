package lk.smartplay.adaptive.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("learning_records")
public class LearningRecord {
    @Id
    private String id;
    private String studentId;
    private String domain;
    private double score;
    private double maxScore;
    private String notes;
    private Instant assessedAt;
    private Integer attempts;
    private Double timeTakenMinutes;
    private String completionStatus;
    public void activityDetails(Integer attempts, Double minutes, String status) {
        this.attempts = attempts; this.timeTakenMinutes = minutes; this.completionStatus = status;
    }
    public Integer getAttempts() { return attempts; }
    public Double getTimeTakenMinutes() { return timeTakenMinutes; }
    public String getCompletionStatus() { return completionStatus; }


    public LearningRecord() {
    }

    public LearningRecord(String studentId, String domain, double score, double maxScore, String notes) {
        this.studentId = studentId;
        this.domain = domain;
        this.score = score;
        this.maxScore = maxScore;
        this.notes = notes;
        this.assessedAt = Instant.now();
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

    public double getScore() {
        return score;
    }

    public double getMaxScore() {
        return maxScore;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }
}
