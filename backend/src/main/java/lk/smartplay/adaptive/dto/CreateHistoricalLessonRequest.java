package lk.smartplay.adaptive.dto;

import java.time.Instant;
import jakarta.validation.constraints.*;

public record CreateHistoricalLessonRequest(
        @NotBlank @Size(max = 180) String lessonId,
        @NotNull @Positive Integer sequenceNumber,
        @NotBlank String completionStatus,
        @PastOrPresent Instant occurredAt) {}
