package lk.smartplay.adaptive.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import lk.smartplay.adaptive.model.VerifiedStudentOutcome;

public interface VerifiedStudentOutcomeRepository extends MongoRepository<VerifiedStudentOutcome, String> {
    Optional<VerifiedStudentOutcome> findByStudentIdAndPolicyVersion(String studentId, String policyVersion);
    List<VerifiedStudentOutcome> findByTeacherOwnerIdOrderByUpdatedAtDesc(String teacherOwnerId);
}
