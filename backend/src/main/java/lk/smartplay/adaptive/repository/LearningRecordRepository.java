package lk.smartplay.adaptive.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import lk.smartplay.adaptive.model.LearningRecord;

public interface LearningRecordRepository extends MongoRepository<LearningRecord, String> {
    List<LearningRecord> findByStudentIdOrderByAssessedAtDesc(String studentId);
}
