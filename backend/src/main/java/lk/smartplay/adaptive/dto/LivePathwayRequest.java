package lk.smartplay.adaptive.dto;

import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Explicit current feature snapshot: never substitute dataset features for missing live data.
public record LivePathwayRequest(
        @NotEmpty Map<String, Object> features,
        @NotNull @Size(max = 200) List<String> demonstratedSkills,
        @NotNull @Size(max = 1000) List<String> completedLessonIds,
        @NotNull @Size(max = 10) Map<String, String> materialReferences) {
}
