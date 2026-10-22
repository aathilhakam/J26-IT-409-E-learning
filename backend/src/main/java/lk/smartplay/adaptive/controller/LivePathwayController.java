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
    public LivePathwayController(LivePathwayService pathways, TeacherAccountService accounts) {
        this.pathways = pathways; this.accounts = accounts;
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
    public record AssignmentRequest(@jakarta.validation.constraints.NotBlank String materialId) {}
    @PostMapping("/{id}/assign")
    public java.util.Map<String, String> assign(@PathVariable String id,
            @Valid @RequestBody(required = false) AssignmentRequest request, Authentication authentication) {
        pathways.assign(id, accounts.accountIdForEmail(authentication.getName()), request == null ? null : request.materialId());
        return java.util.Map.of("message", "Approved material assigned");
    }
}
