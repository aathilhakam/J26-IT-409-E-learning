package lk.smartplay.adaptive.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One current label per student and policy, with corrections retained in revisions. */
@Document(collection = "verified_student_outcomes")
public class VerifiedStudentOutcome {
    @Id private String id;
    private String studentId;
    private String studentGrade;
    private String teacherOwnerId;
    private String policyVersion;
    private List<Revision> revisions = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public VerifiedStudentOutcome() {}
    public VerifiedStudentOutcome(String studentId, String grade, String owner, String policyVersion, Revision first) {
        this.id = studentId + ":" + policyVersion;
        this.studentId = studentId;
        this.studentGrade = grade;
        this.teacherOwnerId = owner;
        this.policyVersion = policyVersion;
        this.createdAt = first.getVerifiedAt();
        this.updatedAt = first.getVerifiedAt();
        this.revisions.add(first);
    }
    public void append(Revision revision) { if (current() != null) current().supersede(); revisions.add(revision); updatedAt = revision.getVerifiedAt(); }
    public Revision current() { return revisions == null || revisions.isEmpty() ? null : revisions.get(revisions.size() - 1); }
    public String getId() { return id; }
    public String getStudentId() { return studentId; }
    public String getStudentGrade() { return studentGrade; }
    public String getTeacherOwnerId() { return teacherOwnerId; }
    public String getPolicyVersion() { return policyVersion; }
    public List<Revision> getRevisions() { return revisions == null ? List.of() : List.copyOf(revisions); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public static class Revision {
        private int revision;
        private String outcome;
        private String suggestedOutcome;
        private String reviewStatus;
        private String rationale;
        private String verifyingTeacherId;
        private Instant verifiedAt;
        private int validAssessmentCount;
        private double averagePercentage;
        private List<AssessmentEvidence> assessmentEvidence = new ArrayList<>();
        private Map<String, Object> featureSnapshot;
        private Instant featureSnapshotGeneratedAt;
        private List<String> featureSnapshotSourceRecordIds = new ArrayList<>();
        private String featureSnapshotStatus;
        private String featureSnapshotIssue;
        public Revision() {}
        public Revision(int revision, String outcome, String suggestion, String rationale, String teacher, Instant at,
                int count, double average, List<AssessmentEvidence> evidence) {
            this(revision, outcome, suggestion, rationale, teacher, at, count, average, evidence, null, null, List.of(), "UNAVAILABLE", "Feature snapshot was not captured.");
        }
        public Revision(int revision, String outcome, String suggestion, String rationale, String teacher, Instant at,
                int count, double average, List<AssessmentEvidence> evidence, Map<String, Object> features,
                Instant featuresGeneratedAt, List<String> featureSources, String featuresStatus, String featuresIssue) {
            this.revision = revision; this.outcome = outcome; this.suggestedOutcome = suggestion;
            this.reviewStatus = "APPROVED"; this.rationale = rationale; this.verifyingTeacherId = teacher;
            this.verifiedAt = at; this.validAssessmentCount = count; this.averagePercentage = average;
            this.assessmentEvidence = new ArrayList<>(evidence);
            this.featureSnapshot = features == null ? null : Map.copyOf(features);
            this.featureSnapshotGeneratedAt = featuresGeneratedAt;
            this.featureSnapshotSourceRecordIds = featureSources == null ? new ArrayList<>() : new ArrayList<>(featureSources);
            this.featureSnapshotStatus = featuresStatus;
            this.featureSnapshotIssue = featuresIssue;
        }
        public int getRevision() { return revision; }
        public void supersede() { this.reviewStatus = "SUPERSEDED"; }
        public String getOutcome() { return outcome; }
        public String getSuggestedOutcome() { return suggestedOutcome; }
        public String getReviewStatus() { return reviewStatus; }
        public String getRationale() { return rationale; }
        public String getVerifyingTeacherId() { return verifyingTeacherId; }
        public Instant getVerifiedAt() { return verifiedAt; }
        public int getValidAssessmentCount() { return validAssessmentCount; }
        public double getAveragePercentage() { return averagePercentage; }
        public List<AssessmentEvidence> getAssessmentEvidence() { return assessmentEvidence == null ? List.of() : List.copyOf(assessmentEvidence); }
        public Map<String, Object> getFeatureSnapshot() { return featureSnapshot == null ? null : Map.copyOf(featureSnapshot); }
        public Instant getFeatureSnapshotGeneratedAt() { return featureSnapshotGeneratedAt; }
        public List<String> getFeatureSnapshotSourceRecordIds() { return featureSnapshotSourceRecordIds == null ? List.of() : List.copyOf(featureSnapshotSourceRecordIds); }
        public String getFeatureSnapshotStatus() { return featureSnapshotStatus; }
        public String getFeatureSnapshotIssue() { return featureSnapshotIssue; }
    }
    public record AssessmentEvidence(String assessmentResultId, String assessmentIdentifier, String assessmentType,
            String sourceComponent, String completionStatus, double score, double maximumScore,
            double percentage, Instant assessedAt) {}
}
