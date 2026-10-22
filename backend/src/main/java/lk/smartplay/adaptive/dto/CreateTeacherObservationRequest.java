package lk.smartplay.adaptive.dto;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;

public record CreateTeacherObservationRequest(
        @NotBlank String participation, @NotBlank String attentionLevel, @NotBlank String confidence,
        @NotBlank String collaboration, @NotBlank String learningDifficulty, @NotBlank String learningStyle,
        @Size(max = 200) List<@NotBlank @Size(max = 180) String> demonstratedSkills,
        @PastOrPresent Instant observedAt) {}
