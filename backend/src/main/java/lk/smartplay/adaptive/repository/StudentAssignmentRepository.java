package lk.smartplay.adaptive.repository;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import lk.smartplay.adaptive.model.StudentAssignment;
public interface StudentAssignmentRepository extends MongoRepository<StudentAssignment,String> {
    List<StudentAssignment> findByOwnerIdAndStudentIdAndActiveTrue(String ownerId, String studentId);
    List<StudentAssignment> findByOwnerIdAndMaterialIdAndActiveTrue(String ownerId, String materialId);
}
