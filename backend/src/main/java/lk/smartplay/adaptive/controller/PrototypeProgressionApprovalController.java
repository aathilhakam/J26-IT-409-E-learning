package lk.smartplay.adaptive.controller;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import lk.smartplay.adaptive.dto.PrototypeProgressionApprovalRequest;
import lk.smartplay.adaptive.model.PrototypeProgressionApproval;
import lk.smartplay.adaptive.service.PrototypeProgressionApprovalService;
import lk.smartplay.adaptive.service.TeacherAccountService;

@RestController
public class PrototypeProgressionApprovalController {
    private final PrototypeProgressionApprovalService approvals;
    private final TeacherAccountService accounts;

    public PrototypeProgressionApprovalController(PrototypeProgressionApprovalService approvals, TeacherAccountService accounts) {
        this.approvals = approvals;
        this.accounts = accounts;
    }

    @GetMapping("/api/teacher/prototype-progression-approvals")
    public List<PrototypeProgressionApproval> list(Authentication auth) {
        return approvals.list(owner(auth));
    }

    @PostMapping("/api/students/{studentId}/prototype-progression-approval")
    public PrototypeProgressionApproval approve(@PathVariable String studentId,
            @Valid @RequestBody PrototypeProgressionApprovalRequest request, Authentication auth) {
        return approvals.approve(studentId, owner(auth), request);
    }

    private String owner(Authentication auth) { return accounts.accountIdForEmail(auth.getName()); }
}
