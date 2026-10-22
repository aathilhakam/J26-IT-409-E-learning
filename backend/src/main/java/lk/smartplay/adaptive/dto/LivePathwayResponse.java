package lk.smartplay.adaptive.dto;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

public record LivePathwayResponse(
        @JsonProperty("Predicted_Outcome") String outcome,
        @JsonProperty("Prediction_Probabilities") Map<String, Double> probabilities,
        @JsonProperty("Model_Version") String modelVersion,
        @JsonProperty("Status") String status,
        @JsonProperty("Learning_Path") List<Activity> activities,
        @JsonProperty("Path_Type") String pathwayType) {
    public LivePathwayResponse(String outcome,Map<String,Double> probabilities,String modelVersion,String status,List<Activity> activities) {
        this(outcome,probabilities,modelVersion,status,activities,"Needs Practice".equals(outcome)?"PERSONALIZED":"COMMON");
    }
    public record Activity(
            @JsonProperty("Recommended_Lesson_ID") String lessonId,
            @JsonProperty("Recommended_Subject") String subject,
            @JsonProperty("Recommended_Topic") String topic,
            @JsonProperty("Learning_Objective") String objective,
            @JsonProperty("Classroom_Activities") String activity,
            @JsonProperty("Difficulty_Level") String difficulty,
            @JsonProperty("Required_Skills") String prerequisites,
            @JsonProperty("Evidence_ID") String evidenceId,
            @JsonProperty("Selection_Reason") String reason) {}
}
