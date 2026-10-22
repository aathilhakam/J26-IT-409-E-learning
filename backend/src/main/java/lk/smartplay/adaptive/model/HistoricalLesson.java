package lk.smartplay.adaptive.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Document("historical_lessons")
public class HistoricalLesson {
    @Id private String id;
    private String materialId;
    public void linkLesson(String materialId) {
        this.materialId=materialId;
        this.id=TeacherObservation.lessonEvidenceId(ownerId,materialId,studentId);
    }
    public String getMaterialId() { return materialId; }
    private String studentId;
    private String ownerId;
    private String lessonId;
    private int sequenceNumber;
    private String completionStatus;
    private Instant occurredAt;
    private Instant recordedAt;
    private Instant completedAt;
    public HistoricalLesson() {}
    public HistoricalLesson(String studentId, String ownerId, String lessonId, int sequenceNumber, String completionStatus, Instant occurredAt) {
        this.studentId = studentId; this.ownerId = ownerId; this.lessonId = lessonId;
        this.sequenceNumber = sequenceNumber; this.completionStatus = completionStatus;
        this.occurredAt = occurredAt; this.recordedAt = Instant.now();
        this.completedAt = completionStatus.equals("Completed") ? occurredAt : null;
    }
    public String getId() { return id; }
    public String getStudentId() { return studentId; }
    @JsonIgnore public String getOwnerId() { return ownerId; }
    public String getLessonId() { return lessonId; }
    public int getSequenceNumber() { return sequenceNumber; }
    public String getCompletionStatus() { return completionStatus; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getRecordedAt() { return recordedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
