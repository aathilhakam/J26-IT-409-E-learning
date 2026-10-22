package lk.smartplay.adaptive.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Document("teacher_observations")
public class TeacherObservation {
    @Id private String id;
    private String materialId;
    private String question;
    private String studentAnswer;
    private String improvement;
    private String feedback;
    public void linkLesson(String materialId, String question, String studentAnswer, String improvement, String feedback) {
        this.materialId=materialId; this.question=question; this.studentAnswer=studentAnswer;
        this.improvement=improvement; this.feedback=feedback;
        this.id=lessonEvidenceId(ownerId,materialId,studentId);
    }
    public static String lessonEvidenceId(String owner,String material,String student) {
        return "lesson-"+java.util.UUID.nameUUIDFromBytes((owner+":"+material+":"+student).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public String getMaterialId() { return materialId; }
    public String getQuestion() { return question; }
    public String getStudentAnswer() { return studentAnswer; }
    public String getImprovement() { return improvement; }
    public String getFeedback() { return feedback; }
    private String studentId;
    private String ownerId;
    private String participation;
    private String attentionLevel;
    private String confidence;
    private String collaboration;
    private String learningDifficulty;
    private String learningStyle;
    private List<String> demonstratedSkills;
    private Instant observedAt;
    private Instant recordedAt;
    public TeacherObservation() {}
    public TeacherObservation(String studentId, String ownerId, String participation, String attentionLevel,
            String confidence, String collaboration, String learningDifficulty, String learningStyle,
            List<String> demonstratedSkills, Instant observedAt) {
        this.studentId = studentId; this.ownerId = ownerId; this.participation = participation;
        this.attentionLevel = attentionLevel; this.confidence = confidence; this.collaboration = collaboration;
        this.learningDifficulty = learningDifficulty; this.learningStyle = learningStyle;
        this.demonstratedSkills = List.copyOf(demonstratedSkills); this.observedAt = observedAt; this.recordedAt = Instant.now();
    }
    public String getId() { return id; }
    public String getStudentId() { return studentId; }
    @JsonIgnore public String getOwnerId() { return ownerId; }
    public String getParticipation() { return participation; }
    public String getAttentionLevel() { return attentionLevel; }
    public String getConfidence() { return confidence; }
    public String getCollaboration() { return collaboration; }
    public String getLearningDifficulty() { return learningDifficulty; }
    public String getLearningStyle() { return learningStyle; }
    public List<String> getDemonstratedSkills() { return demonstratedSkills == null ? List.of() : List.copyOf(demonstratedSkills); }
    public Instant getObservedAt() { return observedAt; }
    public Instant getRecordedAt() { return recordedAt; }
}
