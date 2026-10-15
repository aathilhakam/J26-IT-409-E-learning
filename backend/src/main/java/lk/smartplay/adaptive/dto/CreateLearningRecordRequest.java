package lk.smartplay.adaptive.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateLearningRecordRequest(
        @NotBlank(message = "Learning area is required") String domain,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) Double score,
        @NotNull @DecimalMin(value = "0.1", inclusive = true) Double maxScore,
        String notes) {
}
