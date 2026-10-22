package lk.smartplay.adaptive.repository;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import lk.smartplay.adaptive.model.TeacherObservation;
public interface TeacherObservationRepository extends MongoRepository<TeacherObservation, String> {
    List<TeacherObservation> findByStudentIdAndOwnerIdOrderByObservedAtDescRecordedAtDescIdDesc(String studentId, String ownerId);
}
