
package lk.smartplay.adaptive.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import lk.smartplay.adaptive.dto.CreateAssessmentResultRequest;
import lk.smartplay.adaptive.model.AssessmentResult;
import lk.smartplay.adaptive.service.AssessmentResultService;
import lk.smartplay.adaptive.service.TeacherAccountService;

@RestController
@RequestMapping("/api/students/{studentId}/assessments")
public class AssessmentResultController {

    private final AssessmentResultService assessmentService;
    private final TeacherAccountService accountService;

    public AssessmentResultController(
            AssessmentResultService assessmentService,
            TeacherAccountService accountService) {
        this.assessmentService = assessmentService;
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssessmentResult create(
            @PathVariable String studentId,
            @Valid @RequestBody CreateAssessmentResultRequest request,
            Authentication authentication) {

        String ownerId = accountService.accountIdForEmail(
                authentication.getName());

        return assessmentService.create(studentId, ownerId, request);
    }

    @GetMapping
    public List<AssessmentResult> list(
            @PathVariable String studentId,
            Authentication authentication) {

        String ownerId = accountService.accountIdForEmail(
                authentication.getName());

        return assessmentService.findForStudent(studentId, ownerId);
    }
}
