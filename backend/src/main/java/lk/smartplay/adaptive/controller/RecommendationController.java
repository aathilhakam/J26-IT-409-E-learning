
package lk.smartplay.adaptive.controller;

import java.util.Map;

import jakarta.validation.Valid;

import lk.smartplay.adaptive.dto.ReviewRecommendationRequest;
import lk.smartplay.adaptive.model.LearningRecommendation;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.service.AIRecommendationClient;
import lk.smartplay.adaptive.service.RecommendationService;
import lk.smartplay.adaptive.service.StudentService;
import lk.smartplay.adaptive.service.TeacherAccountService;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final TeacherAccountService accountService;
    private final StudentService studentService;
    private final AIRecommendationClient aiRecommendationClient;

    public RecommendationController(
            RecommendationService recommendationService,
            TeacherAccountService accountService,
            StudentService studentService,
            AIRecommendationClient aiRecommendationClient) {

        this.recommendationService = recommendationService;
        this.accountService = accountService;
        this.studentService = studentService;
        this.aiRecommendationClient = aiRecommendationClient;
    }

    // NEW: Get AI recommendation for a student
    @GetMapping("/student/{studentId}")
    public Map<String, Object> getAIRecommendation(
            @PathVariable String studentId,
            Authentication authentication) {

        String ownerId = accountService.accountIdForEmail(
                authentication.getName()
        );

        // Verify that this student belongs to the teacher
        Student student = studentService.findById(studentId, ownerId);

        String externalStudentId = student.getExternalStudentId();

        if (externalStudentId == null || externalStudentId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Student does not have a linked AI dataset ID"
            );
        }

        return aiRecommendationClient.getRecommendation(externalStudentId);
    }

    // EXISTING: Teacher approves or rejects recommendation
    @PatchMapping("/{recommendationId}")
    public LearningRecommendation review(
            @PathVariable String recommendationId,
            @Valid @RequestBody ReviewRecommendationRequest request,
            Authentication authentication) {

        String ownerId = accountService.accountIdForEmail(
                authentication.getName()
        );

        return recommendationService.review(
                recommendationId,
                request,
                ownerId
        );
    }
}
