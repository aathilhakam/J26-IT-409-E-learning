package lk.smartplay.adaptive.dto;

import java.util.List;

public record ContentMetadataRequest(
        String title, String category, String description, String subject,
        String grade, String materialType, List<String> tags, String visibility,
        String assignmentMode, List<String> studentIds, String assignmentGrade) {
    public ContentMetadataRequest(String title, String category, String description, String subject,
            String grade, String materialType, List<String> tags, String visibility) {
        this(title, category, description, subject, grade, materialType, tags, visibility, null, null, null);
    }
}
