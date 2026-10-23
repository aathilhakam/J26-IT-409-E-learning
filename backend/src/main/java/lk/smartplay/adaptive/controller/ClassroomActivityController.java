package lk.smartplay.adaptive.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import lk.smartplay.adaptive.service.ClassroomActivityImportService;
import lk.smartplay.adaptive.service.TeacherAccountService;

@RestController
@RequestMapping("/api/classroom-activities")
public class ClassroomActivityController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ClassroomActivityImportService activities;
    private final lk.smartplay.adaptive.service.BinaryPrototypeSnapshotImportService prototypeSnapshots;
    private final TeacherAccountService accounts;

    public ClassroomActivityController(ClassroomActivityImportService activities, TeacherAccountService accounts) {
        this(activities, null, accounts);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ClassroomActivityController(ClassroomActivityImportService activities,
            lk.smartplay.adaptive.service.BinaryPrototypeSnapshotImportService prototypeSnapshots,
            TeacherAccountService accounts) {
        this.activities = activities; this.prototypeSnapshots = prototypeSnapshots; this.accounts = accounts;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam(defaultValue = "") String grade,
            @RequestParam(defaultValue = "") String activityName, Authentication authentication) {
        String owner = accounts.accountIdForEmail(authentication.getName());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=SmartPlay_Simple_Assessment_Template.xlsx")
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .contentType(XLSX)
                .body(activities.template(owner, grade, activityName));
    }

    @PostMapping("/preview")
    public ClassroomActivityImportService.Preview preview(@RequestPart MultipartFile file, Authentication authentication) {
        return activities.preview(accounts.accountIdForEmail(authentication.getName()), file);
    }

    @PostMapping("/import")
    public ClassroomActivityImportService.ImportResult importActivities(@RequestPart MultipartFile file,
            @RequestParam String token, Authentication authentication) {
        return activities.importActivities(accounts.accountIdForEmail(authentication.getName()), file, token);
    }

    @GetMapping("/prototype-template")
    public ResponseEntity<byte[]> prototypeTemplate(@RequestParam(defaultValue = "") String grade,
            @RequestParam String worksheetId, Authentication authentication) {
        if (prototypeSnapshots == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Prototype snapshot import is unavailable.");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=SmartPlay_Prototype_10_Features.xlsx")
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0").contentType(XLSX)
                .body(prototypeSnapshots.template(accounts.accountIdForEmail(authentication.getName()), grade, worksheetId));
    }

    @PostMapping("/prototype-preview")
    public lk.smartplay.adaptive.service.BinaryPrototypeSnapshotImportService.Preview previewPrototype(
            @RequestPart MultipartFile file, Authentication authentication) {
        return prototypeSnapshots.preview(accounts.accountIdForEmail(authentication.getName()), file);
    }

    @PostMapping("/prototype-import")
    public lk.smartplay.adaptive.service.BinaryPrototypeSnapshotImportService.ImportResult importPrototype(
            @RequestPart MultipartFile file, @RequestParam String token, Authentication authentication) {
        return prototypeSnapshots.importFile(accounts.accountIdForEmail(authentication.getName()), file, token);
    }
}
