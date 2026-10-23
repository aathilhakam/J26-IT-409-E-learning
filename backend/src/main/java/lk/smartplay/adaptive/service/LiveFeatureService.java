package lk.smartplay.adaptive.service;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

/** Notebook 03 formulas applied only to the authenticated student's persisted evidence. */
@Service
public class LiveFeatureService {
    public static final List<String> ASSESSMENT_MEASURES = List.of("Pre_Test", "Post_Test", "Unit_Test", "Monthly_Test", "Term_Result", "Motor_Skills_Score", "Creativity_Score", "Interactive_Engagement");
    public static final List<String> FEATURE_ORDER = List.of("Average_Score", "Average_Attempts", "Average_Time_Taken", "Total_Activities", "Completion_Rate", "Pre_Test", "Post_Test", "Unit_Test", "Monthly_Test", "Term_Result", "Motor_Skills_Score", "Creativity_Score", "Interactive_Engagement", "Test_Improvement", "Participation", "Attention_Level", "Confidence", "Collaboration", "Learning_Difficulty", "Learning_Style", "Participation_Score", "Attention_Score", "Confidence_Score", "Collaboration_Score", "Historical_Lessons", "Average_Sequence_No", "Completed_Historical_Lessons", "Historical_Completion_Rate");
    private final StudentService students;
    private final LearningRecordRepository records;
    private final ClassroomActivityRepository classroomActivities;
    private final AssessmentResultRepository assessments;
    private final TeacherObservationService observations;
    private final HistoricalLessonService history;
    public LiveFeatureService(StudentService students, LearningRecordRepository records, AssessmentResultRepository assessments,
            TeacherObservationService observations, HistoricalLessonService history) {
        this(students, records, null, assessments, observations, history);
    }
    @Autowired
    public LiveFeatureService(StudentService students, LearningRecordRepository records, ClassroomActivityRepository classroomActivities,
            AssessmentResultRepository assessments, TeacherObservationService observations, HistoricalLessonService history) {
        this.students = students; this.records = records; this.classroomActivities = classroomActivities; this.assessments = assessments; this.observations = observations; this.history = history;
    }
    public record Snapshot(Map<String,Object> features, List<String> demonstratedSkills, List<String> completedLessonIds,
            List<String> sourceRecordIds, Instant generatedAt) {}
    private record ActivityEvidence(String id, String activityId, double score, double maxScore,
            Integer attempts, Double timeTakenMinutes, String completionStatus, boolean worksheetResult, boolean classroomActivity) {}

    public Snapshot generate(String studentId, String ownerId) {
        var student = students.findById(studentId, ownerId);
        if (!student.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot generate features for an archived student");
        var results = assessments.findByStudentIdOrderByAssessedAtDesc(studentId);
        var activities = activityEvidence(studentId, results);
        var observed = observations.findForStudent(studentId, ownerId);
        var lessons = history.findForStudent(studentId, ownerId);
        List<String> missing = new ArrayList<>();
        if (activities.isEmpty()) missing.add("classroom activities");
        if (activities.stream().anyMatch(activity -> activity.attempts() == null || activity.attempts() < 0
                || activity.timeTakenMinutes() == null || !Double.isFinite(activity.timeTakenMinutes()) || activity.timeTakenMinutes() < 0
                || activity.completionStatus() == null || !validActivityStatus(activity)
                || !Double.isFinite(activity.score()) || !Double.isFinite(activity.maxScore()) || activity.maxScore() <= 0 || activity.score() < 0 || activity.score() > activity.maxScore())) {
            missing.add("complete activity attempts, duration, completion status and valid scores (including older records)");
        }
        for (String measure : ASSESSMENT_MEASURES) {
            if (results.stream().noneMatch(result -> validMeasure(result.getMeasures().get(measure)))) missing.add(measure);
        }
        if (results.stream().noneMatch(result -> validMeasure(result.getMeasures().get("Pre_Test")) && validMeasure(result.getMeasures().get("Post_Test")))) {
            missing.add("paired Pre_Test and Post_Test measures");
        }
        if (observed.isEmpty()) missing.add("teacher observation");
        if (lessons.isEmpty()) missing.add("historical lessons");
        if (lessons.stream().anyMatch(lesson -> lesson.getSequenceNumber() < 1 || lesson.getLessonId() == null || lesson.getLessonId().isBlank()
                || lesson.getCompletionStatus() == null || !Set.of("Completed", "Not Started", "In Progress").contains(lesson.getCompletionStatus()))) missing.add("valid historical lesson metadata");
        if (!missing.isEmpty()) throw insufficient(missing);
        TeacherObservation observation = observed.get(0); // latest observedAt, then recordedAt/id; no averaging categories.
        if (observation.getParticipation() == null || observation.getAttentionLevel() == null || observation.getConfidence() == null || observation.getCollaboration() == null
                || observation.getLearningDifficulty() == null || observation.getLearningStyle() == null || !TeacherObservationService.LEVELS.containsKey(observation.getParticipation()) || !TeacherObservationService.LEVELS.containsKey(observation.getAttentionLevel())
                || !TeacherObservationService.LEVELS.containsKey(observation.getConfidence()) || !TeacherObservationService.COLLABORATION.containsKey(observation.getCollaboration())
                || !TeacherObservationService.DIFFICULTIES.contains(observation.getLearningDifficulty()) || !TeacherObservationService.STYLES.contains(observation.getLearningStyle())) {
            throw insufficient(List.of("valid training-compatible teacher-observation categories"));
        }
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("Average_Score", activities.stream().mapToDouble(activity -> activity.score() * 100 / activity.maxScore()).average().orElseThrow());
        values.put("Average_Attempts", activities.stream().mapToInt(ActivityEvidence::attempts).average().orElseThrow());
        values.put("Average_Time_Taken", activities.stream().mapToDouble(ActivityEvidence::timeTakenMinutes).average().orElseThrow());
        values.put("Total_Activities", activities.size());
        values.put("Completion_Rate", activities.stream().filter(activity -> "COMPLETED".equals(activity.completionStatus())).count() * 100.0 / activities.size());
        for (String measure : ASSESSMENT_MEASURES) values.put(measure, results.stream().map(result -> result.getMeasures().get(measure)).filter(LiveFeatureService::validMeasure).mapToDouble(Double::doubleValue).average().orElseThrow());
        values.put("Test_Improvement", results.stream().filter(result -> validMeasure(result.getMeasures().get("Pre_Test")) && validMeasure(result.getMeasures().get("Post_Test")))
                .mapToDouble(result -> result.getMeasures().get("Post_Test") - result.getMeasures().get("Pre_Test")).average().orElseThrow());
        values.put("Participation", observation.getParticipation()); values.put("Attention_Level", observation.getAttentionLevel());
        values.put("Confidence", observation.getConfidence()); values.put("Collaboration", observation.getCollaboration());
        values.put("Learning_Difficulty", observation.getLearningDifficulty()); values.put("Learning_Style", observation.getLearningStyle());
        values.put("Participation_Score", TeacherObservationService.LEVELS.get(observation.getParticipation()));
        values.put("Attention_Score", TeacherObservationService.LEVELS.get(observation.getAttentionLevel()));
        values.put("Confidence_Score", TeacherObservationService.LEVELS.get(observation.getConfidence()));
        values.put("Collaboration_Score", TeacherObservationService.COLLABORATION.get(observation.getCollaboration()));
        values.put("Historical_Lessons", lessons.size());
        values.put("Average_Sequence_No", lessons.stream().mapToInt(HistoricalLesson::getSequenceNumber).average().orElseThrow());
        long completed = lessons.stream().filter(lesson -> "Completed".equals(lesson.getCompletionStatus())).count();
        values.put("Completed_Historical_Lessons", completed);
        values.put("Historical_Completion_Rate", completed * 100.0 / lessons.size());
        List<String> sources = new ArrayList<>();
        activities.stream().filter(activity -> !activity.worksheetResult()).forEach(activity ->
                sources.add((activity.classroomActivity() ? "classroom_activity:" : "record:") + activity.id()));
        results.forEach(result -> sources.add("assessment:" + result.getId()));
        sources.add("observation:" + observation.getId()); lessons.forEach(lesson -> sources.add("history:" + lesson.getId()));
        var completedIds = lessons.stream().filter(lesson -> "Completed".equals(lesson.getCompletionStatus())).map(HistoricalLesson::getLessonId).distinct().sorted().toList();
        return new Snapshot(Collections.unmodifiableMap(values), observation.getDemonstratedSkills(), completedIds, List.copyOf(sources), Instant.now());
    }
    private static boolean validMeasure(Double value) { return value != null && Double.isFinite(value) && value >= 0 && value <= 100; }
    private List<ActivityEvidence> activityEvidence(String studentId, List<AssessmentResult> results) {
        var learningRecords = records.findByStudentIdOrderByAssessedAtDesc(studentId);
        var output = new ArrayList<ActivityEvidence>(learningRecords.size() + results.size());
        var existingActivityIds = new HashSet<String>();
        for (LearningRecord record : learningRecords) {
            output.add(new ActivityEvidence(record.getId(), record.getActivityId(), record.getScore(), record.getMaxScore(),
                    record.getAttempts(), record.getTimeTakenMinutes(), record.getCompletionStatus(), false, false));
            if (record.getActivityId() != null && !record.getActivityId().isBlank()) existingActivityIds.add(record.getActivityId());
        }
        if (classroomActivities != null) for (ClassroomActivity activity : classroomActivities.findByStudentIdOrderByAssessedAtDesc(studentId)) {
            if (activity.getActivityId() != null && !activity.getActivityId().isBlank() && !existingActivityIds.add(activity.getActivityId())) continue;
            output.add(new ActivityEvidence(activity.getId(), activity.getActivityId(), activity.getScore(), activity.getMaxScore(),
                    activity.getAttempts(), activity.getTimeTakenMinutes(), activity.getCompletionStatus(), false, true));
        }
        for (AssessmentResult result : results) {
            if (!result.isWorksheet()) continue;
            String activityId = result.getActivityId();
            // If both workflows point at the same explicit activity ID, count its learning record once.
            if (activityId != null && !activityId.isBlank() && existingActivityIds.contains(activityId)) continue;
            output.add(new ActivityEvidence(result.getId(), activityId, result.getScore(), result.getMaxScore(),
                    result.getAttempts(), result.getTimeTakenMinutes(), result.getCompletionStatus(), true, false));
        }
        return List.copyOf(output);
    }
    private static boolean validActivityStatus(ActivityEvidence activity) {
        return activity.worksheetResult()
                ? Set.of("COMPLETED", "IN_PROGRESS", "NOT_COMPLETED").contains(activity.completionStatus())
                : Set.of("COMPLETED", "IN_PROGRESS").contains(activity.completionStatus());
    }
    private static ResponseStatusException insufficient(List<String> missing) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient learning data: " + String.join(", ", missing));
    }
}
