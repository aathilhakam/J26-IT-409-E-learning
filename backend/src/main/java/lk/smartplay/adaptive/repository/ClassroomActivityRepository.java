package lk.smartplay.adaptive.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import lk.smartplay.adaptive.model.ClassroomActivity;

public interface ClassroomActivityRepository extends MongoRepository<ClassroomActivity, String> {
    List<ClassroomActivity> findByStudentIdOrderByAssessedAtDesc(String studentId);
}
