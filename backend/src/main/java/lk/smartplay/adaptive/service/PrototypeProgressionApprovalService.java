package lk.smartplay.adaptive.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.dto.PrototypeProgressionApprovalRequest;
import lk.smartplay.adaptive.model.PrototypeProgressionApproval;
import lk.smartplay.adaptive.repository.PrototypeProgressionApprovalRepository;
import lk.smartplay.adaptive.dto.BinaryPrototypePrediction;

@Service
public class PrototypeProgressionApprovalService {
    private static final double SUCCESS_THRESHOLD = 0.85;
    private final PrototypeProgressionApprovalRepository approvals;
    private final StudentService students;
    private final BinaryPrototypePredictionService predictions;

    public PrototypeProgressionApprovalService(PrototypeProgressionApprovalRepository approvals,
            StudentService students, BinaryPrototypePredictionService predictions) {
        this.approvals = approvals;
        this.students = students;
        this.predictions = predictions;
    }

    public List<PrototypeProgressionApproval> list(String teacherOwnerId) {
        return approvals.findByTeacherOwnerIdOrderByApprovedAtDesc(teacherOwnerId);
    }

    public PrototypeProgressionApproval approve(String studentId, String teacherOwnerId,
            PrototypeProgressionApprovalRequest request) {
        var student = students.findById(studentId, teacherOwnerId);
        if (!student.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot approve an archived student");

        // Recheck using the authenticated server-side prototype inference, never client-supplied probability.
        BinaryPrototypePrediction prediction = predictions.predict(studentId, teacherOwnerId);
        Double successful = prediction.probabilities() == null ? null : prediction.probabilities().get("Successful");
        Double needsPractice = prediction.probabilities() == null ? null : prediction.probabilities().get("Needs Practice");
        if (successful == null || needsPractice == null || !Double.isFinite(successful) || !Double.isFinite(needsPractice)
                || !List.of("Needs Practice", "Successful").contains(prediction.predictedClass())
                || successful < 0 || successful > 1 || needsPractice < 0 || needsPractice > 1
                || Math.abs(successful + needsPractice - 1) > 1e-6) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A valid binary prototype prediction is required before recording a progression approval");
        }
        if (successful >= SUCCESS_THRESHOLD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This student currently meets the prototype 85% threshold and is not in the manual-approval group");
        }

        String rationale = request.rationale().trim();
        if (rationale.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a reason for approving this student to progress");
        var approval = new PrototypeProgressionApproval(student.getId(), student.getName(), student.getGrade(),
                teacherOwnerId, rationale, successful, prediction.modelVersion());
        return approvals.save(approval);
    }
}
