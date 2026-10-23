package lk.smartplay.adaptive.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import lk.smartplay.adaptive.repository.BinaryPrototypeFeatureSnapshotRepository;
import lk.smartplay.adaptive.service.StudentService;
import lk.smartplay.adaptive.service.TeacherAccountService;

@RestController
public class PrototypeFeatureSnapshotController {
    private final BinaryPrototypeFeatureSnapshotRepository snapshots;
    private final StudentService students;
    private final TeacherAccountService accounts;

    public PrototypeFeatureSnapshotController(BinaryPrototypeFeatureSnapshotRepository snapshots,
            StudentService students, TeacherAccountService accounts) {
        this.snapshots = snapshots;
        this.students = students;
        this.accounts = accounts;
    }

    @GetMapping("/api/students/{studentId}/prototype-feature-snapshot")
    public ResponseEntity<?> latest(@PathVariable String studentId, Authentication authentication) {
        String owner = accounts.accountIdForEmail(authentication.getName());
        students.findById(studentId, owner);
        return snapshots.findTopByStudentIdAndOwnerIdOrderByCreatedAtDesc(studentId, owner)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of("available", false)));
    }
}
