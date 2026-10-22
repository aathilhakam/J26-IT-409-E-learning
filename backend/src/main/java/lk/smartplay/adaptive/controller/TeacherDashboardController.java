package lk.smartplay.adaptive.controller;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import lk.smartplay.adaptive.service.*;

@RestController
@RequestMapping("/api/dashboard")
public class TeacherDashboardController {
    private final TeacherDashboardService dashboard;
    private final TeacherAccountService accounts;
    public TeacherDashboardController(TeacherDashboardService dashboard, TeacherAccountService accounts) { this.dashboard=dashboard; this.accounts=accounts; }
    @GetMapping("/statistics") public TeacherDashboardService.Statistics statistics(@RequestParam(required=false) String studentId,Authentication auth) { return dashboard.statistics(accounts.accountIdForEmail(auth.getName()),studentId); }
    @GetMapping("/reviews") public Object reviews(Authentication auth) { return dashboard.pending(accounts.accountIdForEmail(auth.getName())); }
    @GetMapping("/pathways") public List<TeacherDashboardService.StudentPathway> pathways(Authentication auth) { return dashboard.pathways(accounts.accountIdForEmail(auth.getName())); }
    @GetMapping("/lessons") public List<TeacherDashboardService.Lesson> lessons(@RequestParam(required=false) String grade,Authentication auth) { return dashboard.lessons(accounts.accountIdForEmail(auth.getName()),grade); }
    @GetMapping("/lessons/history") public List<TeacherDashboardService.Lesson> history(@RequestParam(required=false) String grade,Authentication auth) { return dashboard.completedLessons(accounts.accountIdForEmail(auth.getName()),grade); }
    @PostMapping("/lessons/{materialId}/complete") public Map<String,String> complete(@PathVariable String materialId,Authentication auth) {
        dashboard.completeLesson(accounts.accountIdForEmail(auth.getName()),materialId);
        return Map.of("status","COMPLETED");
    }
}
