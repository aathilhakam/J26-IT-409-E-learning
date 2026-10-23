package lk.smartplay.adaptive.controller;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import lk.smartplay.adaptive.dto.VerifyStudentOutcomeRequest;
import lk.smartplay.adaptive.model.VerifiedStudentOutcome;
import lk.smartplay.adaptive.service.TeacherAccountService;
import lk.smartplay.adaptive.service.VerifiedStudentOutcomeService;

@RestController
public class VerifiedStudentOutcomeController {
    private final VerifiedStudentOutcomeService outcomes;
    private final TeacherAccountService accounts;
    public VerifiedStudentOutcomeController(VerifiedStudentOutcomeService outcomes, TeacherAccountService accounts) {
        this.outcomes = outcomes; this.accounts = accounts;
    }
    @GetMapping("/api/students/{studentId}/verified-outcome")
    public VerifiedStudentOutcomeService.ReviewSnapshot review(@PathVariable String studentId, Authentication auth) {
        return outcomes.review(studentId, owner(auth));
    }
    @PostMapping("/api/students/{studentId}/verified-outcome")
    public VerifiedStudentOutcome verify(@PathVariable String studentId, @Valid @RequestBody VerifyStudentOutcomeRequest request, Authentication auth) {
        return outcomes.verify(studentId, owner(auth), request);
    }
    @GetMapping("/api/teacher/verified-outcomes/training-export")
    public List<VerifiedStudentOutcomeService.TrainingLabelExport> trainingExport(Authentication auth) { return outcomes.trainingExport(owner(auth)); }
    private String owner(Authentication auth) { return accounts.accountIdForEmail(auth.getName()); }
}
