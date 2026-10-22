package lk.smartplay.adaptive.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import lk.smartplay.adaptive.service.*;
@RestController @RequestMapping("/api/recommendations/student/{studentId}/milestones")
public class RecommendationMilestoneController {
    private final RecommendationMilestoneService milestones;private final TeacherAccountService accounts;
    public RecommendationMilestoneController(RecommendationMilestoneService milestones,TeacherAccountService accounts){this.milestones=milestones;this.accounts=accounts;}
    @GetMapping public RecommendationMilestoneService.Progress progress(@PathVariable String studentId,Authentication auth){return milestones.progress(accounts.accountIdForEmail(auth.getName()),studentId);}
    @PostMapping public RecommendationMilestoneService.Progress evaluate(@PathVariable String studentId,@RequestParam(defaultValue="false") boolean retry,@RequestBody(required=false) java.util.Map<String,Object> body,Authentication auth){
        if(body!=null && !body.isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Milestone features come from saved records; no feature payload is accepted");
        String owner=accounts.accountIdForEmail(auth.getName());if(retry)milestones.retryNoMatch(owner,studentId);
        return milestones.evaluate(owner,studentId);
    }
}
