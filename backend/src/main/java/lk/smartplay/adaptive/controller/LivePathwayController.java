package lk.smartplay.adaptive.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import lk.smartplay.adaptive.service.LivePathwayService;
import lk.smartplay.adaptive.service.TeacherAccountService;

@RestController
@RequestMapping("/api/recommendations")
public class LivePathwayController {
    private final LivePathwayService pathways;
    private final TeacherAccountService accounts;
    private final lk.smartplay.adaptive.service.BinaryPrototypePredictionService prototype;
    private final lk.smartplay.adaptive.service.BinaryPrototypeBatchReportService prototypeReports;
    public LivePathwayController(LivePathwayService pathways, TeacherAccountService accounts) {
        this(pathways, accounts, null, null);
    }
    public LivePathwayController(LivePathwayService pathways, TeacherAccountService accounts,
            lk.smartplay.adaptive.service.BinaryPrototypePredictionService prototype) {
        this(pathways, accounts, prototype, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public LivePathwayController(LivePathwayService pathways, TeacherAccountService accounts,
            lk.smartplay.adaptive.service.BinaryPrototypePredictionService prototype,
            lk.smartplay.adaptive.service.BinaryPrototypeBatchReportService prototypeReports) {
        this.pathways = pathways; this.accounts = accounts; this.prototype = prototype; this.prototypeReports = prototypeReports;
    }
    @PostMapping("/student/{studentId}/pathway")
    public LivePathwayService.Result generate(@PathVariable String studentId,
            @RequestBody(required = false) java.util.Map<String,Object> body, Authentication authentication) {
        if (body != null && !body.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Live features are generated from persisted student records; no feature payload is accepted");
        return pathways.generateStored(studentId, accounts.accountIdForEmail(authentication.getName()));
    }
    @GetMapping("/student/{studentId}/features")
    public lk.smartplay.adaptive.service.LiveFeatureService.Snapshot features(@PathVariable String studentId, Authentication authentication) {
        return pathways.snapshot(studentId, accounts.accountIdForEmail(authentication.getName()));
    }
    @PostMapping("/student/{studentId}/prototype-prediction")
    public lk.smartplay.adaptive.dto.BinaryPrototypePrediction prototypePrediction(@PathVariable String studentId, Authentication authentication) {
        if (prototype == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Binary prototype prediction is unavailable");
        return prototype.predict(studentId, accounts.accountIdForEmail(authentication.getName()));
    }
    @PostMapping(value = "/prototype-batch-report", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public org.springframework.http.ResponseEntity<byte[]> prototypeBatchReport(
            @RequestBody lk.smartplay.adaptive.dto.BinaryPrototypeBatchReportRequest request, Authentication authentication) {
        accounts.accountIdForEmail(authentication.getName()); // Require a mapped teacher account.
        if (prototypeReports == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Prototype batch report is unavailable");
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=Grade_1_Binary_Prototype_Predictions.xlsx")
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(prototypeReports.create(request));
    }
    public record AssignmentRequest(@jakarta.validation.constraints.NotBlank String materialId) {}
    @PostMapping("/{id}/assign")
    public java.util.Map<String, String> assign(@PathVariable String id,
            @Valid @RequestBody(required = false) AssignmentRequest request, Authentication authentication) {
        pathways.assign(id, accounts.accountIdForEmail(authentication.getName()), request == null ? null : request.materialId());
        return java.util.Map.of("message", "Approved material assigned");
    }
}
