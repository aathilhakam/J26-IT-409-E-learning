package lk.smartplay.adaptive.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateStudentRequest(
        @NotBlank(message = "Student name is required") String name,
        @NotBlank(message = "Grade is required") String grade) {
}
