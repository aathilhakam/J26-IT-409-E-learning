package lk.smartplay.adaptive.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Document("content_assets")
public class ContentAsset {
    @Id
    private String id;
    private String ownerId;
    private String title;
    private String category;
    private String description;
    private String originalFileName;
    private String storageFileName;
    private String contentType;
    private long size;
    private Instant uploadedAt;

    public ContentAsset() {
    }

    public ContentAsset(
            String ownerId,
            String title,
            String category,
            String description,
            String originalFileName,
            String storageFileName,
            String contentType,
            long size) {
        this.ownerId = ownerId;
        this.title = title;
        this.category = category;
        this.description = description;
        this.originalFileName = originalFileName;
        this.storageFileName = storageFileName;
        this.contentType = contentType;
        this.size = size;
        this.uploadedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    @JsonIgnore
    public String getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    @JsonIgnore
    public String getStorageFileName() {
        return storageFileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
