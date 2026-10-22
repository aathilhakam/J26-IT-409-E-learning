package lk.smartplay.adaptive.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;
import lk.smartplay.adaptive.service.*;
import lk.smartplay.adaptive.model.AssessmentResult;
@RestController @RequestMapping("/api/worksheet-scores")
public class WorksheetScoreController {
    private final WorksheetScoreService scores;private final TeacherAccountService accounts;
    public WorksheetScoreController(WorksheetScoreService scores,TeacherAccountService accounts){this.scores=scores;this.accounts=accounts;}
    @GetMapping("/template") public ResponseEntity<byte[]> template(@RequestParam(defaultValue="") String materialId,@RequestParam(defaultValue="") String title,@RequestParam(defaultValue="") String subject,@RequestParam(defaultValue="") String grade,@RequestParam String date,Authentication auth){
        String owner=accounts.accountIdForEmail(auth.getName());return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=smartplay-worksheet-scores.xlsx").header(HttpHeaders.CACHE_CONTROL,"no-store, no-cache, must-revalidate, max-age=0").header(HttpHeaders.PRAGMA,"no-cache").contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(scores.template(owner,scores.context(owner,materialId,title,subject,grade,date)));
    }
    @PostMapping("/preview") public WorksheetScoreService.PreviewResult validate(@RequestPart MultipartFile file,@RequestParam(defaultValue="") String materialId,@RequestParam(defaultValue="") String title,@RequestParam(defaultValue="") String subject,@RequestParam(defaultValue="") String grade,@RequestParam String date,Authentication auth){
        String owner=accounts.accountIdForEmail(auth.getName());var context=scores.context(owner,materialId,title,subject,grade,date);
        return scores.preview(owner,context,file);
    }
    @PostMapping("/import") public WorksheetScoreService.ImportResult importScores(@RequestPart MultipartFile file,@RequestParam(defaultValue="") String materialId,@RequestParam(defaultValue="") String title,@RequestParam(defaultValue="") String subject,@RequestParam(defaultValue="") String grade,@RequestParam String date,@RequestParam String token,Authentication auth){
        String owner=accounts.accountIdForEmail(auth.getName());var context=scores.context(owner,materialId,title,subject,grade,date);
        return scores.importScores(owner,context,file,token);
    }
    @GetMapping("/results") public java.util.List<AssessmentResult> results(Authentication auth){return scores.findForTeacher(accounts.accountIdForEmail(auth.getName()));}
    @PatchMapping("/results/{id}") public AssessmentResult correct(@PathVariable String id,@RequestBody WorksheetScoreService.Correction request,Authentication auth){return scores.correct(accounts.accountIdForEmail(auth.getName()),id,request);}
}
