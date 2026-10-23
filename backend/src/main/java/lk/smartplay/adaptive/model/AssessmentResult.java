
package lk.smartplay.adaptive.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "assessment_results")
public class AssessmentResult {

    @Id
    private String id;

    private String studentId;
    private String externalStudentId;
    private String sourceComponent;
    private String assessmentId;
    private String activityId;

    private double score;
    private double maxScore;

    private Integer attempts;
    private Double timeTakenMinutes;

    private String completionStatus;
    private String notes;
    private Instant assessedAt;

    private String teacherOwnerId,studentName,worksheetTitle,subject,grade,materialId;
    private java.time.LocalDate assessmentDate;
    private Instant recordedAt;
    private boolean worksheet;
    private int worksheetRevision;
    @SuppressWarnings("unused") // Written atomically by WorksheetScoreService as the correction audit trail.
    private java.util.List<java.util.Map<String,Object>> corrections;
    public boolean isWorksheet(){return worksheet;}
    public String getTeacherOwnerId(){return teacherOwnerId;}
    public String getStudentName(){return studentName;}
    public String getWorksheetTitle(){return worksheetTitle;}
    public String getSubject(){return subject;}
    public String getGrade(){return grade;}
    public String getMaterialId(){return materialId;}
    public java.time.LocalDate getAssessmentDate(){return assessmentDate;}
    public Instant getRecordedAt(){return recordedAt;}
    public int getWorksheetRevision(){return worksheetRevision;}
    public double getPercentage(){return maxScore>0?Math.round(score*10000.0/maxScore)/100.0:0;}
    public void worksheetDetails(String identity,String owner,String name,String title,String subject,String grade,String material,java.time.LocalDate date){
        id=identity;worksheet=true;teacherOwnerId=owner;studentName=name;worksheetTitle=title;this.subject=subject;this.grade=grade;materialId=material;assessmentDate=date;recordedAt=Instant.now();assessedAt=date.atStartOfDay(java.time.ZoneId.of("Asia/Colombo")).toInstant();
    }
    private String assessmentType;
    private java.util.Map<String, Double> measures;
    public java.util.Map<String, Double> getMeasures() { return measures == null ? java.util.Map.of() : java.util.Map.copyOf(measures); }
    public void setMeasures(java.util.Map<String, Double> measures) { this.measures = java.util.Map.copyOf(measures); }


    public AssessmentResult() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getExternalStudentId() {
        return externalStudentId;
    }

    public void setExternalStudentId(String externalStudentId) {
        this.externalStudentId = externalStudentId;
    }

    public String getSourceComponent() {
        return sourceComponent;
    }

    public void setSourceComponent(String sourceComponent) {
        this.sourceComponent = sourceComponent;
    }

    public String getAssessmentId() {
        return assessmentId;
    }

    public void setAssessmentId(String assessmentId) {
        this.assessmentId = assessmentId;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public double getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(double maxScore) {
        this.maxScore = maxScore;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public Double getTimeTakenMinutes() {
        return timeTakenMinutes;
    }

    public void setTimeTakenMinutes(Double timeTakenMinutes) {
        this.timeTakenMinutes = timeTakenMinutes;
    }

    public String getCompletionStatus() {
        return completionStatus;
    }

    public void setCompletionStatus(String completionStatus) {
        this.completionStatus = completionStatus;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }

    public void setAssessedAt(Instant assessedAt) {
        this.assessedAt = assessedAt;
    }

    public String getAssessmentType() {
        return assessmentType;
    }

    public void setAssessmentType(String assessmentType) {
        this.assessmentType = assessmentType;
    }
}
