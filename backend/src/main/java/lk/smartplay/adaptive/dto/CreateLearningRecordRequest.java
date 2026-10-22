package lk.smartplay.adaptive.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateLearningRecordRequest(
        @NotBlank(message = "Learning area is required") String domain,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) Double score,
        @NotNull @DecimalMin(value = "0.1", inclusive = true) Double maxScore,
        String notes,
        @jakarta.validation.constraints.PositiveOrZero Integer attempts,
        @DecimalMin("0.0") Double timeTakenMinutes,
        String completionStatus) {
    public CreateLearningRecordRequest(String domain, Double score, Double maxScore, String notes) {
        this(domain, score, maxScore, notes, null, null, null);
    }
}
