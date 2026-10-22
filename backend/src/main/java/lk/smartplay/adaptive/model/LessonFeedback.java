package lk.smartplay.adaptive.model;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import lk.smartplay.adaptive.dto.*;

/** One student's editable draft or immutable submitted revision, independent of evidence snapshots. */
@Document("lesson_feedback")
public class LessonFeedback {
    @Id private String id;
    @Version private Long version;
    private String ownerId,studentId,materialId,lessonTitle,studentName,grade,draftKey;
    private String submissionStatus,activityStatus,question,studentAnswer,improvement,feedback;
    private CreateTeacherObservationRequest observation;
    private String fingerprint;
    private Instant createdAt,updatedAt,submittedAt;
    public LessonFeedback() {}
    public LessonFeedback(String owner,String material,String title,String student,String name,String grade,String key) {
        ownerId=owner;materialId=material;lessonTitle=title;studentId=student;studentName=name;this.grade=grade;draftKey=key;
        id=TeacherObservation.lessonEvidenceId(owner,material,student)+"-"+key;
        submissionStatus="DRAFT";createdAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);updatedAt=createdAt;
    }
    public void update(LessonFeedbackRequest.Entry entry,String fingerprint) {
        if (!"DRAFT".equals(submissionStatus)) throw new IllegalStateException("Submitted feedback is immutable");
        activityStatus=entry.status();observation=entry.observation();question=entry.question();studentAnswer=entry.studentAnswer();improvement=entry.improvement();feedback=entry.feedback();
        this.fingerprint=fingerprint;updatedAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }
    public void submit() { if (!"DRAFT".equals(submissionStatus)) throw new IllegalStateException("Already submitted");submissionStatus="SUBMITTED";submittedAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);updatedAt=submittedAt; }
    public String getId(){return id;} public Long getVersion(){return version;}
    @JsonIgnore public String getOwnerId(){return ownerId;} @JsonIgnore public String getFingerprint(){return fingerprint;}
    public String getStudentId(){return studentId;} public String getMaterialId(){return materialId;} public String getLessonTitle(){return lessonTitle;}
    public String getStudentName(){return studentName;} public String getGrade(){return grade;} public String getDraftKey(){return draftKey;}
    public String getSubmissionStatus(){return submissionStatus;} public String getActivityStatus(){return activityStatus;}
    public CreateTeacherObservationRequest getObservation(){return observation;}
    public String getQuestion(){return question;} public String getStudentAnswer(){return studentAnswer;} public String getImprovement(){return improvement;} public String getFeedback(){return feedback;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;} public Instant getSubmittedAt(){return submittedAt;}
}
