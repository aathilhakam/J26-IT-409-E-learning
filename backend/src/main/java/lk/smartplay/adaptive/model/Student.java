
package lk.smartplay.adaptive.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Document(collection = "students")
public class Student {

    @Id
    private String id;

    // ==============================
    // STUDENT INFORMATION
    // ==============================

    // Student's school index number
    private String externalStudentId;

    private String name;
    private String grade;
    private String classroom;
    private String gender;
    private String address;

    // ==============================
    // GUARDIAN INFORMATION
    // ==============================

    private String guardianName;
    private String guardianRelationship;
    private String guardianPhoneNumber;

    // ==============================
    // STUDENT STATUS
    // ==============================

    // true = Active student
    // false = Archived student
    // Existing MongoDB documents may not contain this field.
    private Boolean active = true;

    // ==============================
    // TEACHER OWNERSHIP
    // ==============================

    private String ownerId;

    private Instant createdAt;

    // ==============================
    // CONSTRUCTORS
    // ==============================

    // Required by MongoDB
    public Student() {
    }

    public Student(String name, String grade, String ownerId) {
        this.name = name;
        this.grade = grade;
        this.ownerId = ownerId;
        this.active = true;
        this.createdAt = Instant.now();
    }

    // ==============================
    // STUDENT ID
    // ==============================

    public String getId() {
        return id;
    }

    public String getExternalStudentId() {
        return externalStudentId;
    }

    public void setExternalStudentId(String externalStudentId) {
        this.externalStudentId = externalStudentId;
    }

    // ==============================
    // STUDENT DETAILS
    // ==============================

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public String getClassroom() {
        return classroom;
    }

    public void setClassroom(String classroom) {
        this.classroom = classroom;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    // ==============================
    // GUARDIAN DETAILS
    // ==============================

    public String getGuardianName() {
        return guardianName;
    }

    public void setGuardianName(String guardianName) {
        this.guardianName = guardianName;
    }

    public String getGuardianRelationship() {
        return guardianRelationship;
    }

    public void setGuardianRelationship(String guardianRelationship) {
        this.guardianRelationship = guardianRelationship;
    }

    public String getGuardianPhoneNumber() {
        return guardianPhoneNumber;
    }

    public void setGuardianPhoneNumber(String guardianPhoneNumber) {
        this.guardianPhoneNumber = guardianPhoneNumber;
    }

    // ==============================
    // ACTIVE / ARCHIVED STATUS
    // ==============================

    public boolean isActive() {
        // Treat older MongoDB records without an active
        // field as active students.
        return active == null || active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    // ==============================
    // TEACHER OWNERSHIP
    // ==============================

    @JsonIgnore
    public String getOwnerId() {
        return ownerId;
    }

    // ==============================
    // CREATED DATE
    // ==============================

    public Instant getCreatedAt() {
        return createdAt;
    }
}
