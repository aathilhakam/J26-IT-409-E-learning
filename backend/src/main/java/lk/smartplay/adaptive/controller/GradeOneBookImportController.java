package lk.smartplay.adaptive.controller;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import lk.smartplay.adaptive.service.*;
@RestController
@RequestMapping("/api/content/import-grade-one")
public class GradeOneBookImportController {
    private final GradeOneBookImportService imports;
    private final TeacherAccountService accounts;
    public GradeOneBookImportController(GradeOneBookImportService imports, TeacherAccountService accounts) { this.imports=imports; this.accounts=accounts; }
    @GetMapping public Object candidates() { return imports.candidates(); }
    @PostMapping public Object save(@RequestBody List<GradeOneBookImportService.Book> books, Authentication auth) { return imports.importBooks(accounts.accountIdForEmail(auth.getName()),books); }
}
