package lk.smartplay.adaptive.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Document("students")
public class Student {

    @Id
    private String id;

    // NEW: Student ID from Excel / research dataset
    private String externalStudentId;

    private String name;
    private String grade;
    private String ownerId;
    private Instant createdAt;

    public Student() {
    }

    public Student(String name, String grade, String ownerId) {
        this.name = name;
        this.grade = grade;
        this.ownerId = ownerId;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    // NEW: Getter
    public String getExternalStudentId() {
        return externalStudentId;
    }

    // NEW: Setter
    public void setExternalStudentId(String externalStudentId) {
        this.externalStudentId = externalStudentId;
    }

    public String getName() {
        return name;
    }

    public String getGrade() {
        return grade;
    }

    @JsonIgnore
    public String getOwnerId() {
        return ownerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}