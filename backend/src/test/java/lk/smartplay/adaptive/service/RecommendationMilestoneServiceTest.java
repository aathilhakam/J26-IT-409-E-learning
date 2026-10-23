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
    @Test void progressResponseSerializesRawPredictionAndScoreDecisionSeparatelyWithEvidence() throws Exception {
        var milestone=new RecommendationMilestone();milestone.predictedOutcome="Needs Practice";milestone.probabilities=java.util.Map.of("Needs Practice",0.505,"Successful",0.39,"Waiting",0.105);
        milestone.modelVersion="rf-v1";milestone.applicationOutcome="Successful";milestone.verifiedPerformancePercentage=82.5;milestone.verifiedAssessmentCount=4;
        milestone.assessmentEvidence=List.of(new RecommendationMilestone.AssessmentEvidence("a1","worksheet-1","Fruits","WORKSHEET","TEACHER_ASSESSMENT",8,10,80,"COMPLETED",null));
        var progress=new RecommendationMilestoneService.Progress("student",4,1,6,List.of(milestone),List.of());
        var json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(progress);
        var response=json.get("milestones").get(0);
        assertEquals("Needs Practice",response.get("predictedOutcome").asText());assertEquals("Successful",response.get("applicationOutcome").asText());
        assertEquals(82.5,response.get("verifiedPerformancePercentage").asDouble());assertEquals(4,response.get("verifiedAssessmentCount").asInt());
        assertEquals("Fruits",response.get("assessmentEvidence").get(0).get("title").asText());assertEquals(0.505,response.get("probabilities").get("Needs Practice").asDouble());
    }

    @Test void verifiedPerformanceUsesOnlyOwnedCompletedValidScoresAndKeepsZeroAsARealScore() {
        var exact=score("exact","a","student","teacher",8,10);
        var zero=score("zero","b","student","teacher",0,10);
        var invalid=score("invalid","c","student","teacher",12,10);
        var incomplete=score("incomplete","d","student","teacher",10,10);when(incomplete.getCompletionStatus()).thenReturn("IN_PROGRESS");
        var unauthorized=score("foreign","e","student","other",10,10);
        double exactThreshold=RecommendationMilestoneService.verifiedOverallPerformance("teacher","student",List.of(exact));
        assertEquals(80.0,exactThreshold);
        assertEquals("Successful",RecommendationMilestoneService.applicationOutcome(exactThreshold));
        assertEquals(40.0,RecommendationMilestoneService.verifiedOverallPerformance("teacher","student",List.of(exact,zero,invalid,incomplete,unauthorized)));
        assertEquals("Successful",RecommendationMilestoneService.applicationOutcome(80));
        assertEquals("Successful",RecommendationMilestoneService.applicationOutcome(82.5));
        assertEquals("Successful",RecommendationMilestoneService.applicationOutcome(100));
        assertEquals("Needs Practice",RecommendationMilestoneService.applicationOutcome(79.99));
        assertEquals("INSUFFICIENT_DATA",RecommendationMilestoneService.legacyApplicationCategory("Waiting"));
        assertEquals("Needs Practice",RecommendationMilestoneService.legacyApplicationCategory("Needs Practice"));
        assertEquals(null,RecommendationMilestoneService.verifiedOverallPerformance("teacher","student",List.of(invalid,incomplete,unauthorized)));
    }

    @Test void verifiedPerformanceDeduplicatesCorrectionsAndChangesWhenNewValidScoresArrive() {
        var day=LocalDate.parse("2026-10-21");
        var old=worksheetScore("old","fruits",day,6,Instant.parse("2026-10-21T10:00:00Z"));
        var correction=worksheetScore("corrected","fruits",day,8,Instant.parse("2026-10-22T10:00:00Z"));
        var birds=worksheetScore("birds","birds",day,8,Instant.parse("2026-10-21T10:00:00Z"));
        var songs=worksheetScore("songs","songs",day,9,Instant.parse("2026-10-21T10:00:00Z"));
        var letsGo=worksheetScore("lets-go","lets-go",day,8,Instant.parse("2026-10-21T10:00:00Z"));
        var verified=RecommendationMilestoneService.verifiedAssessment("teacher","student",List.of(old,correction,birds,songs,letsGo));
        assertEquals(4,verified.count());
        assertEquals(82.5,verified.percentage());
        assertEquals(4,verified.evidence().size());
        org.junit.jupiter.api.Assertions.assertTrue(verified.evidence().stream().anyMatch(row->row.title().equals("fruits")&&row.score()==8&&row.maximumScore()==10));
        assertEquals("Successful",RecommendationMilestoneService.applicationOutcome(verified.percentage()));
        var zero=worksheetScore("zero","zero",day,0,Instant.parse("2026-10-22T11:00:00Z"));
        var changed=RecommendationMilestoneService.verifiedAssessment("teacher","student",List.of(old,correction,birds,songs,letsGo,zero));
        assertEquals(5,changed.count());
        assertEquals(66.0,changed.percentage());
        assertEquals("Needs Practice",RecommendationMilestoneService.applicationOutcome(changed.percentage()));
        org.junit.jupiter.api.Assertions.assertNotEquals(verified.fingerprint(),changed.fingerprint());
    }

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
    private static AssessmentResult worksheetScore(String id,String material,LocalDate date,double score,Instant recordedAt) {
        var row=mock(AssessmentResult.class);when(row.isWorksheet()).thenReturn(true);when(row.getId()).thenReturn(id);when(row.getStudentId()).thenReturn("student");
        when(row.getTeacherOwnerId()).thenReturn("teacher");when(row.getMaterialId()).thenReturn(material);when(row.getAssessmentDate()).thenReturn(date);
        when(row.getWorksheetTitle()).thenReturn(material);when(row.getSubject()).thenReturn(material);when(row.getAssessmentType()).thenReturn("WORKSHEET");
        when(row.getActivityId()).thenReturn(material);when(row.getAttempts()).thenReturn(1);when(row.getTimeTakenMinutes()).thenReturn(10.0);
        when(row.getMeasures()).thenReturn(java.util.Map.of());when(row.getCompletionStatus()).thenReturn("COMPLETED");
        when(row.getScore()).thenReturn(score);when(row.getMaxScore()).thenReturn(10.0);when(row.getRecordedAt()).thenReturn(recordedAt);when(row.getAssessedAt()).thenReturn(recordedAt);
        return row;
    }
}
