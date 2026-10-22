package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class RecommendationMilestoneServiceTest {
    @Test void countsOnlyDistinctTeacherOwnedCompletedAssignmentsWithValidMatchingWorksheetScores() {
        String owner="teacher",student="student";
        var students=mock(StudentService.class);var assignments=mock(StudentAssignmentRepository.class);var materials=mock(ContentAssetRepository.class);
        var results=mock(AssessmentResultRepository.class);var recommendations=mock(LearningRecommendationRepository.class);
        var feedback=mock(LessonFeedbackRepository.class);var pathways=mock(LivePathwayService.class);var mongo=mock(MongoTemplate.class);
        var service=new RecommendationMilestoneService(students,assignments,materials,results,recommendations,feedback,pathways,mongo);

        var ownedMaterials=List.of(material("lesson-a",owner,student),material("lesson-b",owner,student),material("lesson-c",owner,student),
                material("lesson-unscored",owner,student),material("lesson-unfinished",owner,student));
        when(materials.findByOwnerIdOrderByUploadedAtDesc(owner)).thenReturn(ownedMaterials);
        var rows=List.of(assignment("a1","lesson-a",student,Instant.parse("2026-10-18T00:00:00Z")),
                assignment("b1","lesson-b",student,Instant.parse("2026-10-19T00:00:00Z")),
                assignment("c1","lesson-c",student,Instant.parse("2026-10-20T00:00:00Z")),
                assignment("unscored","lesson-unscored",student,Instant.parse("2026-10-20T00:00:00Z")),
                assignment("unfinished","lesson-unfinished",student,Instant.parse("2026-10-20T00:00:00Z"),"IN_PROGRESS"));
        when(assignments.findByOwnerIdAndStudentIdAndActiveTrue(owner,student)).thenReturn(rows);
        var scores=List.of(score("score-a","lesson-a",student,owner,8,10),score("score-a-duplicate","lesson-a",student,owner,9,10),
                score("score-b","lesson-b",student,owner,6,10),score("score-c","lesson-c",student,owner,7,10),
                score("score-unauthorized","lesson-unscored",student,"other",10,10),score("score-bad","lesson-unfinished",student,owner,11,10));
        when(results.findByStudentIdOrderByAssessedAtDesc(student)).thenReturn(scores);

        assertEquals(List.of("lesson-a","lesson-b","lesson-c"),service.completed(owner,student).stream().map(StudentAssignment::getMaterialId).toList());
    }

    private static ContentAsset material(String id,String owner,String student) {
        var material=mock(ContentAsset.class);when(material.getId()).thenReturn(id);when(material.getOwnerId()).thenReturn(owner);
        when(material.getAssignmentMode()).thenReturn("STUDENTS");when(material.getAssignedStudentIds()).thenReturn(List.of(student));return material;
    }
    private static StudentAssignment assignment(String id,String material,String student,Instant completedAt) {return assignment(id,material,student,completedAt,"COMPLETED");}
    private static StudentAssignment assignment(String id,String material,String student,Instant completedAt,String status) {
        var row=mock(StudentAssignment.class);when(row.getId()).thenReturn(id);when(row.getMaterialId()).thenReturn(material);when(row.getStudentId()).thenReturn(student);
        when(row.getCompletedAt()).thenReturn(completedAt);when(row.getStatus()).thenReturn(status);return row;
    }
    private static AssessmentResult score(String id,String material,String student,String owner,double score,double max) {
        var row=mock(AssessmentResult.class);when(row.isWorksheet()).thenReturn(true);when(row.getId()).thenReturn(id);when(row.getStudentId()).thenReturn(student);
        when(row.getTeacherOwnerId()).thenReturn(owner);when(row.getMaterialId()).thenReturn(material);when(row.getCompletionStatus()).thenReturn("COMPLETED");
        when(row.getScore()).thenReturn(score);when(row.getMaxScore()).thenReturn(max);when(row.getRecordedAt()).thenReturn(Instant.parse("2026-10-21T00:00:00Z"));
        when(row.getAssessedAt()).thenReturn(LocalDate.parse("2026-10-20").atStartOfDay(java.time.ZoneId.of("Asia/Colombo")).toInstant());return row;
    }
}
