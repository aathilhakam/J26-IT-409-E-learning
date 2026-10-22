package lk.smartplay.adaptive.dto;

import java.time.Instant;
import java.util.List;

import lk.smartplay.adaptive.model.ContentAsset;

public record ContentAssetResponse(
        String id,
        String title,
        String category,
        String description,
        String originalFileName,
        String contentType,
        long size,
        Instant uploadedAt,
        String subject,
        String grade,
        String materialType,
        List<String> tags,
        String visibility, String assignmentMode, String assignmentGrade, List<String> assignedStudentIds) {
    public static ContentAssetResponse from(ContentAsset asset) {
        return new ContentAssetResponse(
                asset.getId(),
                asset.getTitle(),
                asset.getCategory(),
                asset.getDescription(),
                asset.getOriginalFileName(),
                asset.getContentType(),
                asset.getSize(),
                asset.getUploadedAt(),
                asset.getSubject(), asset.getGrade(), asset.getMaterialType(),
                asset.getTags(), asset.getVisibility(), asset.getAssignmentMode(), asset.getAssignmentGrade(), asset.getAssignedStudentIds());
    }
}
