package lk.smartplay.adaptive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PrototypeProgressionApprovalRequest(
        @NotBlank(message = "Enter a reason for approving this student to progress")
        @Size(max = 2000, message = "Reason must be 2,000 characters or fewer")
        String rationale) {}
