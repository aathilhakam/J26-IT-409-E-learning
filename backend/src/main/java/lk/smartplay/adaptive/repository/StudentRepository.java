package lk.smartplay.adaptive.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;

import lk.smartplay.adaptive.model.Student;

public interface StudentRepository extends MongoRepository<Student, String> {
    List<Student> findByOwnerId(String ownerId);
    Optional<Student> findByIdAndOwnerId(String id, String ownerId);
}
