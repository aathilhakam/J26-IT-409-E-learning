package lk.smartplay.adaptive.model;

import java.util.Map;

/** Teacher rubric and experimental model outputs are independent snapshots. */
public record CreativityAssessment(
        Map<String, Double> teacherScores,
        Integer teacherScoreScale,
        String activity,
        String prompt,
        Map<String, Object> imageAnalysis,
        Map<String, Object> voiceAnalysis,
        String imageAnalysisError,
        String voiceAnalysisError,
        Boolean voiceRecorded) {
}
