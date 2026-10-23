package lk.smartplay.adaptive.controller;

import lk.smartplay.adaptive.service.AssessmentResultsImportService;
import lk.smartplay.adaptive.service.TeacherAccountService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/assessment-results")
public class AssessmentResultsImportController {
    private final AssessmentResultsImportService imports;
    private final TeacherAccountService accounts;

    public AssessmentResultsImportController(AssessmentResultsImportService imports, TeacherAccountService accounts) {
        this.imports = imports;
        this.accounts = accounts;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam(defaultValue = "") String grade, Authentication authentication) {
        byte[] content = imports.template(accounts.accountIdForEmail(authentication.getName()), grade);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=smartplay-assessment-results.xlsx")
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    @PostMapping("/preview")
    public AssessmentResultsImportService.Preview preview(@RequestPart MultipartFile file, Authentication authentication) {
        return imports.preview(accounts.accountIdForEmail(authentication.getName()), file);
    }

    @PostMapping("/import")
    public AssessmentResultsImportService.ImportResult importResults(@RequestPart MultipartFile file,
            @RequestParam String token, Authentication authentication) {
        return imports.importFile(accounts.accountIdForEmail(authentication.getName()), file, token);
    }
}
