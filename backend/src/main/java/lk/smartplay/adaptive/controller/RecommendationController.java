package lk.smartplay.adaptive.controller;

import jakarta.validation.Valid;
import lk.smartplay.adaptive.dto.ReviewRecommendationRequest;
import lk.smartplay.adaptive.model.LearningRecommendation;
import lk.smartplay.adaptive.service.RecommendationService;
import lk.smartplay.adaptive.service.TeacherAccountService;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {
    private final RecommendationService recommendationService;
    private final TeacherAccountService accountService;

    public RecommendationController(
            RecommendationService recommendationService,
            TeacherAccountService accountService) {
        this.recommendationService = recommendationService;
        this.accountService = accountService;
    }

    @PatchMapping("/{recommendationId}")
    public LearningRecommendation review(
            @PathVariable String recommendationId,
            @Valid @RequestBody ReviewRecommendationRequest request,
            Authentication authentication) {
        String ownerId = accountService.accountIdForEmail(authentication.getName());
        return recommendationService.review(recommendationId, request, ownerId);
    }
}
