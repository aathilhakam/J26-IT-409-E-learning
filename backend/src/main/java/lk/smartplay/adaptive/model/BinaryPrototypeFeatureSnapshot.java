package lk.smartplay.adaptive.model;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Teacher-entered, point-in-time inputs for the experimental ten-feature model. */
@Document(collection = "binary_prototype_feature_snapshots")
public class BinaryPrototypeFeatureSnapshot {
    @Id private String id;
    private String studentId;
    private String ownerId;
    private String studentName;
    private String grade;
    private Map<String, Object> features;
    private String fingerprint;
    private Instant createdAt;

    public BinaryPrototypeFeatureSnapshot() {}
    public BinaryPrototypeFeatureSnapshot(String studentId, String ownerId, String studentName, String grade,
            Map<String, Object> features, String fingerprint) {
        this.studentId = studentId; this.ownerId = ownerId; this.studentName = studentName;
        this.grade = grade; this.features = features; this.fingerprint = fingerprint; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getStudentId() { return studentId; }
    public String getOwnerId() { return ownerId; }
    public String getStudentName() { return studentName; }
    public String getGrade() { return grade; }
    public Map<String, Object> getFeatures() { return features; }
    public String getFingerprint() { return fingerprint; }
    public Instant getCreatedAt() { return createdAt; }
}
