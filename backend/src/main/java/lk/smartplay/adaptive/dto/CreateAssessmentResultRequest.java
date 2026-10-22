
package lk.smartplay.adaptive.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateAssessmentResultRequest(
        @NotBlank String sourceComponent,
        String assessmentType,
        @NotBlank String assessmentId,
        String activityId,
        @NotNull @DecimalMin("0.0") Double score,
        @NotNull @DecimalMin("0.1") Double maxScore,
        @PositiveOrZero Integer attempts,
        @DecimalMin("0.0") Double timeTakenMinutes,
        @NotBlank String completionStatus,
        String notes,
        java.util.Map<String, Double> measures
) {
    public CreateAssessmentResultRequest(String sourceComponent, String assessmentType, String assessmentId,
            String activityId, Double score, Double maxScore, Integer attempts, Double timeTakenMinutes,
            String completionStatus, String notes) {
        this(sourceComponent, assessmentType, assessmentId, activityId, score, maxScore, attempts,
                timeTakenMinutes, completionStatus, notes, null);
    }
}
