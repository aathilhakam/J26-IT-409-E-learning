package lk.smartplay.adaptive.model;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Document("content_assets")
public class ContentAsset {
    @Id
    private String id;
    private Instant classroomCompletedAt;
    public Instant getClassroomCompletedAt() { return classroomCompletedAt; }
    public void completeClassroomLesson() { classroomCompletedAt = Instant.now(); }
    private String uploadFingerprint;
    private boolean individualAssignmentsTracked;
    @JsonIgnore public boolean isIndividualAssignmentsTracked() { return individualAssignmentsTracked; }
    public void trackIndividualAssignments() { individualAssignmentsTracked = true; }
    public void uploadIdentity(String id, String fingerprint) { this.id=id; this.uploadFingerprint=fingerprint; }
    @JsonIgnore public String getUploadFingerprint() { return uploadFingerprint; }
    private String ownerId;
    private String title;
    private String category;
    private String description;
    private String originalFileName;
    private String storageFileName;
    private String contentType;
    private long size;
    private Instant uploadedAt;
    private String subject;
    private String grade;
    private String materialType;
    private List<String> tags;
    private String visibility;
    private String assignmentMode;
    private String assignmentGrade;
    private List<String> assignedStudentIds;

    public void assign(String mode, String grade, List<String> ids) {
        assignmentMode = mode;
        assignmentGrade = grade;
        assignedStudentIds = List.copyOf(ids);
    }
    public String getAssignmentMode() { return assignmentMode == null ? "LIBRARY" : assignmentMode; }
    public String getAssignmentGrade() { return assignmentGrade == null ? "" : assignmentGrade; }
    public List<String> getAssignedStudentIds() { return assignedStudentIds == null ? List.of() : List.copyOf(assignedStudentIds); }



    public ContentAsset() {
    }

    public ContentAsset(
            String ownerId,
            String title,
            String category,
            String description,
            String originalFileName,
            String storageFileName,
            String contentType,
            long size) {
        this.ownerId = ownerId;
        this.title = title;
        this.category = category;
        this.description = description;
        this.originalFileName = originalFileName;
        this.storageFileName = storageFileName;
        this.contentType = contentType;
        this.size = size;
        this.uploadedAt = Instant.now();
    }

    public void updateMetadata(String title, String category, String description,
            String subject, String grade, String materialType, List<String> tags, String visibility) {
        this.title = title;
        this.category = category;
        this.description = description;
        this.subject = subject;
        this.grade = grade;
        this.materialType = materialType;
        this.tags = List.copyOf(tags);
        this.visibility = visibility;
    }

    public String getSubject() { return subject == null ? "" : subject; }
    public String getGrade() { return grade == null ? "" : grade; }
    public String getMaterialType() { return materialType == null ? "" : materialType; }
    public List<String> getTags() { return tags == null ? List.of() : List.copyOf(tags); }
    public String getVisibility() { return visibility == null ? "TEACHER_ONLY" : visibility; }

    public String getId() {
        return id;
    }

    @JsonIgnore
    public String getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    @JsonIgnore
    public String getStorageFileName() {
        return storageFileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
