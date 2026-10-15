package lk.smartplay.adaptive.repository;

import java.util.Optional;

import lk.smartplay.adaptive.model.TeacherAccount;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TeacherAccountRepository extends MongoRepository<TeacherAccount, String> {
    Optional<TeacherAccount> findByEmail(String email);
    boolean existsByEmail(String email);
}
