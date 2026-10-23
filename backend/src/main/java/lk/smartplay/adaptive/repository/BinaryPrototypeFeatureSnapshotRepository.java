package lk.smartplay.adaptive.repository;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import lk.smartplay.adaptive.model.BinaryPrototypeFeatureSnapshot;

public interface BinaryPrototypeFeatureSnapshotRepository extends MongoRepository<BinaryPrototypeFeatureSnapshot, String> {
    Optional<BinaryPrototypeFeatureSnapshot> findTopByStudentIdAndOwnerIdOrderByCreatedAtDesc(String studentId, String ownerId);
    Optional<BinaryPrototypeFeatureSnapshot> findByStudentIdAndOwnerIdAndFingerprint(String studentId, String ownerId, String fingerprint);
}
