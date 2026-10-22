package lk.smartplay.adaptive.controller;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import lk.smartplay.adaptive.dto.LessonFeedbackRequest;
import lk.smartplay.adaptive.service.*;
@RestController
@RequestMapping("/api/dashboard/lessons/{materialId}")
public class LessonFeedbackController {
    private final FeedbackSubmissionService feedback;
    private final TeacherAccountService accounts;
    public LessonFeedbackController(FeedbackSubmissionService feedback,TeacherAccountService accounts) { this.feedback=feedback;this.accounts=accounts; }
    @GetMapping public Object details(@PathVariable String materialId,Authentication auth) { return feedback.workspace(accounts.accountIdForEmail(auth.getName()),materialId); }
    @PostMapping("/feedback") public Object save(@PathVariable String materialId,@Valid @RequestBody LessonFeedbackRequest request,Authentication auth) {
        return feedback.save(accounts.accountIdForEmail(auth.getName()),materialId,request,true);
    }
    @PutMapping("/feedback/drafts") public Object drafts(@PathVariable String materialId,@RequestBody LessonFeedbackRequest request,Authentication auth) {
        return feedback.save(accounts.accountIdForEmail(auth.getName()),materialId,request,false);
    }
    @GetMapping("/feedback") public Object submitted(@PathVariable String materialId,Authentication auth) {
        return feedback.forLesson(accounts.accountIdForEmail(auth.getName()),materialId,"SUBMITTED");
    }
}
