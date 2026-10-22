package lk.smartplay.adaptive.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import lk.smartplay.adaptive.service.*;
@RestController
public class StudentFeedbackController {
    private final FeedbackSubmissionService feedback;
    private final TeacherAccountService accounts;
    public StudentFeedbackController(FeedbackSubmissionService feedback,TeacherAccountService accounts){this.feedback=feedback;this.accounts=accounts;}
    @GetMapping("/api/students/{studentId}/feedback") public Object student(@PathVariable String studentId,Authentication auth){return feedback.forStudent(accounts.accountIdForEmail(auth.getName()),studentId);}
    @GetMapping("/api/dashboard/feedback") public Object teacher(@RequestParam(defaultValue="SUBMITTED") String status,Authentication auth){return feedback.forTeacher(accounts.accountIdForEmail(auth.getName()),status);}
}
