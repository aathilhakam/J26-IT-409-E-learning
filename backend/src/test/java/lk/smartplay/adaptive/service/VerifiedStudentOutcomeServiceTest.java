package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.dto.VerifyStudentOutcomeRequest;
import lk.smartplay.adaptive.model.AssessmentResult;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.model.VerifiedStudentOutcome;
import lk.smartplay.adaptive.repository.AssessmentResultRepository;
import lk.smartplay.adaptive.repository.VerifiedStudentOutcomeRepository;

class VerifiedStudentOutcomeServiceTest {
    private VerifiedStudentOutcomeRepository labels;
    private AssessmentResultRepository assessments;
    private StudentService students;
    private VerifiedStudentOutcomeService service;
    private Student student;

    @BeforeEach void setup() {
        labels = mock(VerifiedStudentOutcomeRepository.class); assessments = mock(AssessmentResultRepository.class); students = mock(StudentService.class);
        service = new VerifiedStudentOutcomeService(labels, assessments, students);
        student = mock(Student.class); when(student.getId()).thenReturn("student-1"); when(student.getName()).thenReturn("Amaya");
        when(student.getGrade()).thenReturn("Grade 1"); when(student.isActive()).thenReturn(true);
        when(students.findById("student-1", "teacher-1")).thenReturn(student);
        when(assessments.findByStudentIdOrderByAssessedAtDesc("student-1")).thenReturn(List.of(result("a1", 0, 10, "COMPLETED"), result("a2", 9, 10, "COMPLETED"), result("bad", 11, 10, "COMPLETED"), result("partial", 10, 10, "IN_PROGRESS")));
        when(labels.findByStudentIdAndPolicyVersion("student-1", VerifiedStudentOutcomeService.POLICY_VERSION)).thenReturn(Optional.empty());
        when(labels.insert(any(VerifiedStudentOutcome.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test void reviewKeepsZeroValidAndExcludesInvalidAndIncompleteEvidence() {
        var review = service.review("student-1", "teacher-1");
        assertEquals(2, review.validAssessmentCount());
        assertEquals(45.0, review.averagePercentage());
        assertEquals("Needs Practice", review.suggestedOutcome());
        assertEquals(0.0, review.assessmentEvidence().stream().filter(row -> row.assessmentResultId().equals("a1")).findFirst().orElseThrow().percentage());
    }

    @Test void exactlyEightyPercentSuggestsSuccessful() {
        when(assessments.findByStudentIdOrderByAssessedAtDesc("student-1")).thenReturn(List.of(result("boundary", 8, 10, "COMPLETED")));
        assertEquals("Successful", service.review("student-1", "teacher-1").suggestedOutcome());
    }

    @Test void worksheetEvidenceOwnedByAnotherTeacherIsExcluded() {
        AssessmentResult worksheet = result("foreign", 10, 10, "COMPLETED");
        worksheet.worksheetDetails("foreign", "other-teacher", "Amaya", "worksheet", "English", "Grade 1", "m1", java.time.LocalDate.now());
        when(assessments.findByStudentIdOrderByAssessedAtDesc("student-1")).thenReturn(List.of(worksheet));
        assertEquals(0, service.review("student-1", "teacher-1").validAssessmentCount());
    }

    @Test void conflictingTeacherDecisionRequiresReasonAndRevisionSupersedesOldLabel() {
        assertThrows(ResponseStatusException.class, () -> service.verify("student-1", "teacher-1", new VerifyStudentOutcomeRequest("Successful", "")));
        var first = service.verify("student-1", "teacher-1", new VerifyStudentOutcomeRequest("Needs Practice", "Observed evidence supports practice"));
        assertEquals(1, first.current().getRevision());
        when(labels.findByStudentIdAndPolicyVersion("student-1", VerifiedStudentOutcomeService.POLICY_VERSION)).thenReturn(Optional.of(first));
        when(labels.save(first)).thenReturn(first);
        var revised = service.verify("student-1", "teacher-1", new VerifyStudentOutcomeRequest("Successful", "Teacher reviewed additional context"));
        assertEquals(2, revised.current().getRevision());
        assertEquals("SUPERSEDED", revised.getRevisions().get(0).getReviewStatus());
        assertEquals("APPROVED", revised.current().getReviewStatus());
    }

    @Test void repeatedConfirmationWithSameEvidenceIsIdempotent() {
        var saved = service.verify("student-1", "teacher-1", new VerifyStudentOutcomeRequest("Needs Practice", ""));
        when(labels.findByStudentIdAndPolicyVersion("student-1", VerifiedStudentOutcomeService.POLICY_VERSION)).thenReturn(Optional.of(saved));
        assertSame(saved, service.verify("student-1", "teacher-1", new VerifyStudentOutcomeRequest("Needs Practice", "updated note")));
        assertEquals(1, saved.getRevisions().size());
        verify(labels, never()).save(any());
    }

    @Test void noValidAssessmentEvidenceCannotBeLabeled() {
        when(assessments.findByStudentIdOrderByAssessedAtDesc("student-1")).thenReturn(List.of(result("partial", 8, 10, "IN_PROGRESS")));
        assertThrows(ResponseStatusException.class, () -> service.verify("student-1", "teacher-1", new VerifyStudentOutcomeRequest("Needs Practice", "")));
        verify(labels, never()).<VerifiedStudentOutcome>insert(any(VerifiedStudentOutcome.class));
    }

    @Test void archivedStudentsCannotReceiveNewLabels() {
        when(student.isActive()).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> service.verify("student-1", "teacher-1", new VerifyStudentOutcomeRequest("Needs Practice", "")));
        verify(labels, never()).<VerifiedStudentOutcome>insert(any(VerifiedStudentOutcome.class));
    }

    @Test void trainingExportIncludesOnlyCurrentApprovedLabelsOwnedByTeacher() {
        var approvedRevision = new VerifiedStudentOutcome.Revision(1, "Needs Practice", "Needs Practice", "", "teacher-1",
                Instant.parse("2026-10-22T00:00:00Z"), 2, 45.0, List.of());
        var approved = new VerifiedStudentOutcome("student-1", "Grade 1", "teacher-1", VerifiedStudentOutcomeService.POLICY_VERSION, approvedRevision);
        var pendingRevision = new VerifiedStudentOutcome.Revision(1, "Successful", "Needs Practice", "Reason", "teacher-1",
                Instant.parse("2026-10-22T00:00:00Z"), 2, 45.0, List.of());
        pendingRevision.supersede();
        var pending = new VerifiedStudentOutcome("student-2", "Grade 1", "teacher-1", VerifiedStudentOutcomeService.POLICY_VERSION, pendingRevision);
        when(labels.findByTeacherOwnerIdOrderByUpdatedAtDesc("teacher-1")).thenReturn(List.of(approved, pending));

        var export = service.trainingExport("teacher-1");

        assertEquals(1, export.size());
        assertEquals("student-1", export.get(0).studentId());
        assertEquals("APPROVED", export.get(0).revisions().get(0).getReviewStatus());
        verify(labels).findByTeacherOwnerIdOrderByUpdatedAtDesc("teacher-1");
    }

    @Test void approvalFreezesExactTimestampedLiveFeatureSnapshotIntoExportRevision() {
        LiveFeatureService liveFeatures = mock(LiveFeatureService.class);
        Instant generatedAt = Instant.parse("2026-10-22T09:00:00Z");
        Map<String, Object> values = new LinkedHashMap<>();
        for (String feature : LiveFeatureService.FEATURE_ORDER) values.put(feature, 1);
        when(liveFeatures.generate("student-1", "teacher-1"))
                .thenReturn(new LiveFeatureService.Snapshot(values, List.of(), List.of(), List.of("assessment:a1"), generatedAt));
        VerifiedStudentOutcomeService withFeatures = new VerifiedStudentOutcomeService(labels, assessments, students, liveFeatures);

        var saved = withFeatures.verify("student-1", "teacher-1", new VerifyStudentOutcomeRequest("Needs Practice", ""));
        var revision = saved.current();

        assertEquals("READY", revision.getFeatureSnapshotStatus());
        assertEquals(LiveFeatureService.FEATURE_ORDER.size(), revision.getFeatureSnapshot().size());
        assertEquals(generatedAt, revision.getFeatureSnapshotGeneratedAt());
        assertFalse(revision.getFeatureSnapshotGeneratedAt().isAfter(revision.getVerifiedAt()));
        when(labels.findByTeacherOwnerIdOrderByUpdatedAtDesc("teacher-1")).thenReturn(List.of(saved));
        assertEquals("READY", withFeatures.trainingExport("teacher-1").get(0).revisions().get(0).getFeatureSnapshotStatus());
    }

    @Test void incompleteFeatureReadinessKeepsTeacherLabelButMarksSnapshotIneligible() {
        LiveFeatureService liveFeatures = mock(LiveFeatureService.class);
        when(liveFeatures.generate("student-1", "teacher-1")).thenThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient learning data: Pre_Test"));
        VerifiedStudentOutcomeService withFeatures = new VerifiedStudentOutcomeService(labels, assessments, students, liveFeatures);

        var saved = withFeatures.verify("student-1", "teacher-1", new VerifyStudentOutcomeRequest("Needs Practice", ""));

        assertEquals("INCOMPLETE", saved.current().getFeatureSnapshotStatus());
        assertNull(saved.current().getFeatureSnapshot());
        assertTrue(saved.current().getFeatureSnapshotIssue().contains("Pre_Test"));
    }

    private static AssessmentResult result(String id, double score, double maximum, String status) {
        AssessmentResult result = new AssessmentResult(); result.setId(id); result.setAssessmentId(id); result.setScore(score); result.setMaxScore(maximum);
        result.setCompletionStatus(status); result.setAssessedAt(Instant.parse("2026-10-22T00:00:00Z")); return result;
    }
}
