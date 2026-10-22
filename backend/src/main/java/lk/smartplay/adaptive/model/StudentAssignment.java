package lk.smartplay.adaptive.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Document("student_assignments")
public class StudentAssignment {
    @Id private String id;
    private String ownerId;
    private String materialId;
    private String studentId;
    private String status;
    private boolean active;
    private Instant assignedAt;
    private Instant updatedAt;
    private Instant completedAt;
    public String getId() { return id; }
    @JsonIgnore public String getOwnerId() { return ownerId; }
    public String getMaterialId() { return materialId; }
    public String getStudentId() { return studentId; }
    public String getStatus() { return status; }
    public boolean isActive() { return active; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
