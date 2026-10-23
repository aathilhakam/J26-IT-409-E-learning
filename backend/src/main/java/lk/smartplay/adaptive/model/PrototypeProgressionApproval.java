package lk.smartplay.adaptive.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Teacher-authored override for progression; it never changes a model prediction. */
@Document(collection = "prototype_progression_approvals")
public class PrototypeProgressionApproval {
    @Id private String id;
    private String studentId;
    private String studentName;
    private String studentGrade;
    private String teacherOwnerId;
    private String rationale;
    private double successfulProbability;
    private String modelVersion;
    private Instant approvedAt;

    public PrototypeProgressionApproval() {}

    public PrototypeProgressionApproval(String studentId, String studentName, String studentGrade,
            String teacherOwnerId, String rationale, double successfulProbability, String modelVersion) {
        this.id = teacherOwnerId + ":" + studentId;
        this.studentId = studentId;
        this.studentName = studentName;
        this.studentGrade = studentGrade;
        this.teacherOwnerId = teacherOwnerId;
        this.rationale = rationale;
        this.successfulProbability = successfulProbability;
        this.modelVersion = modelVersion;
        this.approvedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getStudentGrade() { return studentGrade; }
    public String getTeacherOwnerId() { return teacherOwnerId; }
    public String getRationale() { return rationale; }
    public double getSuccessfulProbability() { return successfulProbability; }
    public String getModelVersion() { return modelVersion; }
    public Instant getApprovedAt() { return approvedAt; }
}
