package lk.smartplay.adaptive.repository;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import lk.smartplay.adaptive.model.HistoricalLesson;
public interface HistoricalLessonRepository extends MongoRepository<HistoricalLesson, String> {
    List<HistoricalLesson> findByStudentIdAndOwnerIdOrderByOccurredAtDescIdDesc(String studentId, String ownerId);
}
