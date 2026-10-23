package lk.smartplay.adaptive.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import lk.smartplay.adaptive.model.PrototypeProgressionApproval;

public interface PrototypeProgressionApprovalRepository extends MongoRepository<PrototypeProgressionApproval, String> {
    List<PrototypeProgressionApproval> findByTeacherOwnerIdOrderByApprovedAtDesc(String teacherOwnerId);
}
