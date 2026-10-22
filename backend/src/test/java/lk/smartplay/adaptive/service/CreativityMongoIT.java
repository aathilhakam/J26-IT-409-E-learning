package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import com.mongodb.client.MongoClients;

import lk.smartplay.adaptive.dto.CreateLearningRecordRequest;
import lk.smartplay.adaptive.model.CreativityAssessment;
import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.LearningRecordRepository;
import lk.smartplay.adaptive.repository.StudentRepository;

/** Opt-in local MongoDB check: mvn test -Dtest=CreativityMongoIT.
 * Uses and cleans up only a unique disposable verification database. */
class CreativityMongoIT {
    @Test
    void mongoRoundTripKeepsTeacherRubricModelOutputsAndHistoricalScale() {
        String database = "smartplay_creativity_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create("mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000")) {
            try {
                var template = new MongoTemplate(client, database);
                var factory = new MongoRepositoryFactory(template);
                var students = factory.getRepository(StudentRepository.class);
                var records = factory.getRepository(LearningRecordRepository.class);
                var service = new StudentService(students, records);
                var student = students.save(new Student("Verification Learner", "Grade 1", "test-owner"));
                var historical = records.save(new LearningRecord(student.getId(), "Creativity", 4, 5, "Rubric: Originality 4/5."));
                var teacher = Map.of("originality", 10.0, "imagination", 1.0, "elaboration", 6.0, "storytelling", 8.0);
                var observation = new CreativityAssessment(teacher, 10, "Creative Coloring", "Treehouse",
                        Map.of("scores", Map.of("originality", 2.3), "score_scale", 5,
                                "colour_analysis", Map.of("dominant_colour", "Red", "category_count", 1)),
                        null, null, null, false);
                var saved = service.addRecord(student.getId(),
                        new CreateLearningRecordRequest("Creativity", 6.25, 10.0, "Teacher notes", observation), "test-owner");
                var reloaded = records.findById(saved.getId()).orElseThrow();
                assertEquals(teacher, reloaded.getCreativityAssessment().teacherScores());
                assertEquals(10, reloaded.getCreativityAssessment().teacherScoreScale());
                assertEquals(observation.imageAnalysis(), reloaded.getCreativityAssessment().imageAnalysis());
                assertEquals(6.25, reloaded.getScore());
                var old = records.findById(historical.getId()).orElseThrow();
                assertEquals(5, old.getMaxScore());
                assertEquals(4, old.getScore());
                assertNull(old.getCreativityAssessment());
                assertEquals(71.3, service.profile(student.getId(), "test-owner").overallAverage());
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
