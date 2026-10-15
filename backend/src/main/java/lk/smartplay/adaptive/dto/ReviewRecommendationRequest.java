package lk.smartplay.adaptive.dto;

import jakarta.validation.constraints.NotNull;

import lk.smartplay.adaptive.model.LearningRecommendation.Status;

public record ReviewRecommendationRequest(
        @NotNull Status status,
        String activityTitle,
        String description,
        String teacherFeedback) {
}
