
package lk.smartplay.adaptive.dto;

import jakarta.validation.constraints.NotBlank;

public record ArchiveStudentRequest(
    @NotBlank(message = "Student full name is required")
    String studentFullName
) {
}
