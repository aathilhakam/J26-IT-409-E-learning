package lk.smartplay.adaptive.dto;

import java.time.Instant;

import lk.smartplay.adaptive.model.ContentAsset;

public record ContentAssetResponse(
        String id,
        String title,
        String category,
        String description,
        String originalFileName,
        String contentType,
        long size,
        Instant uploadedAt) {
    public static ContentAssetResponse from(ContentAsset asset) {
        return new ContentAssetResponse(
                asset.getId(),
                asset.getTitle(),
                asset.getCategory(),
                asset.getDescription(),
                asset.getOriginalFileName(),
                asset.getContentType(),
                asset.getSize(),
                asset.getUploadedAt());
    }
}
