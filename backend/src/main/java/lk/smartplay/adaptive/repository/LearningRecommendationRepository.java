package lk.smartplay.adaptive.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import lk.smartplay.adaptive.model.LearningRecommendation;

public interface LearningRecommendationRepository extends MongoRepository<LearningRecommendation, String> {
    List<LearningRecommendation> findByStudentIdOrderByCreatedAtDesc(String studentId);
}
