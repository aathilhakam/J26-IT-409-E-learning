package lk.smartplay.adaptive.repository;
import java.util.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import lk.smartplay.adaptive.model.LessonFeedback;
public interface LessonFeedbackRepository extends MongoRepository<LessonFeedback,String> {
    Optional<LessonFeedback> findByIdAndOwnerId(String id,String owner);
    List<LessonFeedback> findByOwnerIdAndMaterialIdAndSubmissionStatusOrderBySubmittedAtDescUpdatedAtDesc(String owner,String material,String status);
    List<LessonFeedback> findByOwnerIdAndStudentIdAndSubmissionStatusOrderBySubmittedAtDescUpdatedAtDesc(String owner,String student,String status);
    List<LessonFeedback> findByOwnerIdAndSubmissionStatusOrderBySubmittedAtDescUpdatedAtDesc(String owner,String status);
}
