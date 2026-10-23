package lk.smartplay.adaptive.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.dto.VerifyStudentOutcomeRequest;
import lk.smartplay.adaptive.model.AssessmentResult;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.model.VerifiedStudentOutcome;
import lk.smartplay.adaptive.model.VerifiedStudentOutcome.AssessmentEvidence;
import lk.smartplay.adaptive.model.VerifiedStudentOutcome.Revision;
import lk.smartplay.adaptive.repository.AssessmentResultRepository;
import lk.smartplay.adaptive.repository.VerifiedStudentOutcomeRepository;

@Service
public class VerifiedStudentOutcomeService {
    public static final String POLICY_VERSION = "assessment-threshold-80-v1";
    private static final Set<String> OUTCOMES = Set.of("Needs Practice", "Successful");
    private final VerifiedStudentOutcomeRepository labels;
    private final AssessmentResultRepository assessments;
    private final StudentService students;
    private final LiveFeatureService liveFeatures;

    public VerifiedStudentOutcomeService(VerifiedStudentOutcomeRepository labels,
            AssessmentResultRepository assessments, StudentService students) {
        this(labels, assessments, students, null);
    }

    @Autowired
    public VerifiedStudentOutcomeService(VerifiedStudentOutcomeRepository labels,
            AssessmentResultRepository assessments, StudentService students, LiveFeatureService liveFeatures) {
        this.labels = labels; this.assessments = assessments; this.students = students; this.liveFeatures = liveFeatures;
    }

    public ReviewSnapshot review(String studentId, String ownerId) {
        Student student = students.findById(studentId, ownerId);
        Evidence evidence = evidence(studentId, ownerId);
        return new ReviewSnapshot(student.getId(), student.getName(), student.getGrade(), POLICY_VERSION,
                evidence.rows(), evidence.rows().size(), evidence.average(), evidence.suggestion(),
                evidence.rows().isEmpty() ? "INSUFFICIENT_DATA" : null,
                labels.findByStudentIdAndPolicyVersion(studentId, POLICY_VERSION).orElse(null));
    }

    public VerifiedStudentOutcome verify(String studentId, String ownerId, VerifyStudentOutcomeRequest request) {
        Student student = students.findById(studentId, ownerId);
        if (!student.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Archived students cannot receive a new training label");
        String selected = request.outcome().trim();
        if (!OUTCOMES.contains(selected)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose Needs Practice or Successful");
        Evidence evidence = evidence(studentId, ownerId);
        if (evidence.rows().isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No valid completed assessment evidence; a training label cannot be confirmed");
        String rationale = request.rationale() == null ? "" : request.rationale().trim();
        if (!selected.equals(evidence.suggestion()) && rationale.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected outcome conflicts with the 80% assessment suggestion. Enter a rationale to confirm this decision.");
        }

        FeatureCapture featureCapture = captureFeatureSnapshot(studentId, ownerId);
        var existing = labels.findByStudentIdAndPolicyVersion(studentId, POLICY_VERSION);
        Revision current = existing.map(VerifiedStudentOutcome::current).orElse(null);
        boolean sameLabelAndEvidence = current != null && current.getOutcome().equals(selected)
                && current.getAssessmentEvidence().equals(evidence.rows());
        if (sameLabelAndEvidence && current.getFeatureSnapshot() != null) return existing.orElseThrow();
        if (sameLabelAndEvidence && featureCapture.features() == null) return existing.orElseThrow();
        Instant verifiedAt = Instant.now();
        Revision revision = new Revision(current == null ? 1 : current.getRevision() + 1, selected,
                evidence.suggestion(), rationale, ownerId, verifiedAt, evidence.rows().size(), evidence.average(), evidence.rows(),
                featureCapture.features(), featureCapture.generatedAt(), featureCapture.sourceRecordIds(), featureCapture.status(), featureCapture.issue());
        if (existing.isPresent()) {
            VerifiedStudentOutcome document = existing.get();
            document.append(revision);
            return labels.save(document);
        }
        try {
            return labels.insert(new VerifiedStudentOutcome(student.getId(), student.getGrade(), ownerId, POLICY_VERSION, revision));
        } catch (DuplicateKeyException concurrent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A verified outcome was created concurrently. Reload the student's label before revising it.", concurrent);
        }
    }

    public List<VerifiedStudentOutcome> approvedLabels(String ownerId) {
        return labels.findByTeacherOwnerIdOrderByUpdatedAtDesc(ownerId).stream()
                .filter(label -> label.current() != null && "APPROVED".equals(label.current().getReviewStatus()))
                .toList();
    }
    public List<TrainingLabelExport> trainingExport(String ownerId) {
        return approvedLabels(ownerId).stream().map(label -> new TrainingLabelExport(label.getStudentId(), label.getStudentGrade(),
                label.getPolicyVersion(), List.of(label.current()))).toList();
    }

    private Evidence evidence(String studentId, String ownerId) {
        List<AssessmentEvidence> rows = assessments.findByStudentIdOrderByAssessedAtDesc(studentId).stream()
                .filter(result -> valid(result, ownerId))
                .map(VerifiedStudentOutcomeService::toEvidence)
                .sorted(Comparator.comparing(AssessmentEvidence::assessedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AssessmentEvidence::assessmentResultId, Comparator.nullsLast(String::compareTo)))
                .toList();
        double average = rows.isEmpty() ? 0.0 : rows.stream().mapToDouble(AssessmentEvidence::percentage).average().orElseThrow();
        String suggestion = rows.isEmpty() ? null : average >= 80.0 ? "Successful" : "Needs Practice";
        return new Evidence(rows, average, suggestion);
    }

    private FeatureCapture captureFeatureSnapshot(String studentId, String ownerId) {
        if (liveFeatures == null) return new FeatureCapture(null, null, List.of(), "UNAVAILABLE", "Live feature service is not configured.");
        try {
            LiveFeatureService.Snapshot snapshot = liveFeatures.generate(studentId, ownerId);
            if (snapshot == null || snapshot.features() == null || snapshot.generatedAt() == null) {
                return new FeatureCapture(null, null, List.of(), "UNAVAILABLE", "Live feature service returned no timestamped snapshot.");
            }
            return new FeatureCapture(snapshot.features(), snapshot.generatedAt(), snapshot.sourceRecordIds(), "READY", null);
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                return new FeatureCapture(null, null, List.of(), "INCOMPLETE", failure.getReason());
            }
            throw failure;
        }
    }

    private static boolean valid(AssessmentResult result, String ownerId) {
        if (result == null || !"COMPLETED".equalsIgnoreCase(result.getCompletionStatus())
                || !Double.isFinite(result.getScore()) || !Double.isFinite(result.getMaxScore())
                || result.getMaxScore() <= 0 || result.getScore() < 0 || result.getScore() > result.getMaxScore()) return false;
        return !result.isWorksheet() || ownerId.equals(result.getTeacherOwnerId());
    }
    private static AssessmentEvidence toEvidence(AssessmentResult result) {
        return new AssessmentEvidence(result.getId(), result.getAssessmentId(), result.getAssessmentType(),
                result.getSourceComponent(), result.getCompletionStatus(), result.getScore(), result.getMaxScore(),
                Math.round(result.getScore() * 10000.0 / result.getMaxScore()) / 100.0, result.getAssessedAt());
    }
    private record Evidence(List<AssessmentEvidence> rows, double average, String suggestion) {}
    private record FeatureCapture(java.util.Map<String, Object> features, Instant generatedAt, List<String> sourceRecordIds, String status, String issue) {}
    public record ReviewSnapshot(String studentId, String studentName, String grade, String policyVersion,
            List<AssessmentEvidence> assessmentEvidence, int validAssessmentCount, double averagePercentage,
            String suggestedOutcome, String evidenceStatus, VerifiedStudentOutcome verifiedLabel) {}
    public record TrainingLabelExport(String studentId, String studentGrade, String policyVersion, List<Revision> revisions) {}
}
