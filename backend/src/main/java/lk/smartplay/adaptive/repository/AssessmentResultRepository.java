
package lk.smartplay.adaptive.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import lk.smartplay.adaptive.model.AssessmentResult;

public interface AssessmentResultRepository
        extends MongoRepository<AssessmentResult, String> {

    List<AssessmentResult>
        findByStudentIdOrderByAssessedAtDesc(String studentId);

    List<AssessmentResult> findByTeacherOwnerIdAndWorksheetTrueOrderByAssessedAtDesc(String teacherOwnerId);
}
