package lk.smartplay.adaptive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyStudentOutcomeRequest(
        @NotBlank String outcome,
        @Size(max = 2000) String rationale) {}
