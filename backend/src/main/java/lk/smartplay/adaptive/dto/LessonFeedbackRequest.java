package lk.smartplay.adaptive.dto;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
public record LessonFeedbackRequest(@NotEmpty @Size(max=200) List<@NotNull @Valid Entry> students) {
    public record Entry(@NotBlank String studentId, String status,
        @NotNull CreateTeacherObservationRequest observation,
        @Size(max=1000) String question, @Size(max=2000) String studentAnswer,
        @Size(max=2000) String improvement, @Size(max=2000) String feedback,
        @Pattern(regexp="[A-Za-z0-9-]{1,100}") String draftKey, Long expectedVersion) {
        public Entry(String studentId,String status,CreateTeacherObservationRequest observation,String question,String studentAnswer,String improvement,String feedback) {
            this(studentId,status,observation,question,studentAnswer,improvement,feedback,"initial",null);
        }
    }
}
