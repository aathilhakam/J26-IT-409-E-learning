package lk.smartplay.adaptive.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import lk.smartplay.adaptive.model.ContentAsset;

public interface ContentAssetRepository extends MongoRepository<ContentAsset, String> {
    List<ContentAsset> findByOwnerIdOrderByUploadedAtDesc(String ownerId);

    Optional<ContentAsset> findByIdAndOwnerId(String id, String ownerId);
}
