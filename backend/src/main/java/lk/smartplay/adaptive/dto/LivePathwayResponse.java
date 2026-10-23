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
        @JsonProperty("Path_Type") String pathwayType,
        @JsonProperty("Unmatched_Needs") List<UnmatchedNeed> unmatchedNeeds) {
    public LivePathwayResponse(String outcome,Map<String,Double> probabilities,String modelVersion,String status,List<Activity> activities) {
        this(outcome,probabilities,modelVersion,status,activities,"Needs Practice".equals(outcome)?"PERSONALIZED":"COMMON",List.of());
    }
    public LivePathwayResponse(String outcome,Map<String,Double> probabilities,String modelVersion,String status,List<Activity> activities,String pathwayType) {
        this(outcome,probabilities,modelVersion,status,activities,pathwayType,List.of());
    }
    public record UnmatchedNeed(@JsonProperty("Evidence_ID") String evidenceId,
            @JsonProperty("Area") String area,@JsonProperty("Reason") String reason,
            @JsonProperty("Explanation") String explanation,@JsonProperty("Catalog_Topics") List<String> catalogTopics,
            @JsonProperty("Catalog_Lesson_IDs") List<String> catalogLessonIds,
            @JsonProperty("Required_Skills") List<String> requiredSkills) {}
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
