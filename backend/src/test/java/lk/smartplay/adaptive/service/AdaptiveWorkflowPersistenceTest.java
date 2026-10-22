package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import com.mongodb.client.MongoClients;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockMultipartFile;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;
import lk.smartplay.adaptive.dto.*;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class AdaptiveWorkflowPersistenceTest {
    @TempDir Path files;

    @Test void collectionsRemainSeparateWithActualRepositoryWritesAndOwnerChecks() {
        String database = "smartplay_separation_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create(System.getenv("MONGODB_TEST_URI"))) {
            var mongo = new MongoTemplate(client, database);
            try {
                var factory = new MongoRepositoryFactory(mongo);
                var students = factory.getRepository(StudentRepository.class);
                var records = factory.getRepository(LearningRecordRepository.class);
                var assessments = factory.getRepository(AssessmentResultRepository.class);
                var studentService = new StudentService(students, records);
                var assessmentService = new AssessmentResultService(assessments, studentService);
                var student = students.save(new Student("Test learner", "Grade 1", "test-owner"));
                String id = student.getId();
                assessmentService.create(id, "test-owner", new CreateAssessmentResultRequest("TEACHER_ASSESSMENT", "NUMBER_RECOGNITION", "source-event-1", null, 2.0, 5.0, 1, 4.0, "COMPLETED", "Observed"));
                assertEquals(1, mongo.getCollection("assessment_results").countDocuments());
                assertEquals(0, mongo.getCollection("learning_records").countDocuments());
                var assessmentBefore = mongo.getCollection("assessment_results").find().first();
                studentService.addRecord(id, new CreateLearningRecordRequest("Numeracy", 3.0, 5.0, "Classroom activity"), "test-owner");
                assertEquals(1, mongo.getCollection("assessment_results").countDocuments());
                assertEquals(1, mongo.getCollection("learning_records").countDocuments());
                assertEquals(assessmentBefore, mongo.getCollection("assessment_results").find().first());
                assertEquals(id, assessmentService.findForStudent(id, "test-owner").get(0).getStudentId());
                assertEquals(id, studentService.findRecords(id, "test-owner").get(0).getStudentId());
                var other = students.save(new Student("Other learner", "Grade 1", "test-owner"));
                assertTrue(assessmentService.findForStudent(other.getId(), "test-owner").isEmpty());
                assertTrue(studentService.findRecords(other.getId(), "test-owner").isEmpty());
                assertThrows(ResponseStatusException.class, () -> assessmentService.findForStudent(id, "foreign-owner"));
                assertThrows(ResponseStatusException.class, () -> studentService.findRecords(id, "foreign-owner"));
            } finally { mongo.getDb().drop(); }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AI_TEST_URL", matches = ".+")
    void livePythonSelectionPersistsDeduplicatesReviewsAndAssignsOwnedMaterial() throws Exception {
        String database = "smartplay_live_pathway_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create(System.getenv("MONGODB_TEST_URI"))) {
            var mongo = new MongoTemplate(client, database);
            try {
                var factory = new MongoRepositoryFactory(mongo);
                var students = factory.getRepository(StudentRepository.class);
                var records = factory.getRepository(LearningRecordRepository.class);
                var assessments = factory.getRepository(AssessmentResultRepository.class);
                var recommendations = factory.getRepository(LearningRecommendationRepository.class);
                var materials = factory.getRepository(ContentAssetRepository.class);
                var studentService = new StudentService(students, records);
                var assessmentService = new AssessmentResultService(assessments, studentService);
                var service = new LivePathwayService(studentService, assessments, records, recommendations, materials,
                        new AIRecommendationClient(System.getenv("AI_TEST_URL"),System.getProperty("smartplay.ai.inference-token","")), null);
                var student = students.save(new Student("Test learner", "Grade 1", "test-owner"));
                assessmentService.create(student.getId(), "test-owner", new CreateAssessmentResultRequest("TEACHER_ASSESSMENT", "Number Recognition", "a1", null, 2.0, 5.0, 1, 4.0, "COMPLETED", "Test evidence"));
                var content = new ContentAssetService(materials, students, files.toString());
                var material = content.store("test-owner", "Number chart", "Worksheet", "Test material", new MockMultipartFile("file", "chart.pdf", "application/pdf", "%PDF-1.7 test".getBytes()));
                Map<String, Object> features;
                try (var input = getClass().getResourceAsStream("/fixtures/st001-features.json")) {
                    features = new ObjectMapper().readValue(input, new TypeReference<Map<String, Object>>() {});
                }
                // Fixture is an explicit known training-compatible snapshot, not synthesized from this single assessment.
                var request = new LivePathwayRequest(features, List.of("Number sequence knowledge"), List.of(), Map.of("LES021", material.getId()));
                var result = service.generate(student.getId(), "test-owner", request);
                assertEquals("Successful", result.predictedOutcome());
                assertEquals(1, result.recommendations().size());
                var saved = result.recommendations().get(0);
                assertEquals("LES021", saved.getLessonId());
                assertEquals(LearningRecommendation.Status.PENDING, saved.getStatus());
                assertEquals(material.getId(), saved.getMaterialId());
                assertEquals(1, recommendations.count());
                assertEquals(saved.getId(), service.generate(student.getId(), "test-owner", request).recommendations().get(0).getId());
                assertEquals(1, recommendations.count());
                assertThrows(ResponseStatusException.class, () -> service.assign(saved.getId(), "test-owner"));
                var review = new RecommendationService(studentService, records, recommendations);
                review.review(saved.getId(), new ReviewRecommendationRequest(LearningRecommendation.Status.APPROVED, "Teacher edited title", null, "Validated"), "test-owner");
                service.assign(saved.getId(), "test-owner");
                assertEquals(List.of(student.getId()), materials.findById(material.getId()).orElseThrow().getAssignedStudentIds());
                assertTrue(Files.isRegularFile(content.fileFor(material)));
                review.review(saved.getId(), new ReviewRecommendationRequest(LearningRecommendation.Status.REJECTED, null, null, "Reconsidered"), "test-owner");
                assertThrows(ResponseStatusException.class, () -> service.assign(saved.getId(), "test-owner"));
                assertThrows(ResponseStatusException.class, () -> service.generate(student.getId(), "foreign-owner", request));
            } finally { mongo.getDb().drop(); }
        }
    }
}
