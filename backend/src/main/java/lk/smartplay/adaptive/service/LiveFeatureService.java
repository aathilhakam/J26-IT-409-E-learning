package lk.smartplay.adaptive.service;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
    private final AssessmentResultRepository assessments;
    private final TeacherObservationService observations;
    private final HistoricalLessonService history;
    public LiveFeatureService(StudentService students, LearningRecordRepository records, AssessmentResultRepository assessments,
            TeacherObservationService observations, HistoricalLessonService history) {
        this.students = students; this.records = records; this.assessments = assessments; this.observations = observations; this.history = history;
    }
    public record Snapshot(Map<String,Object> features, List<String> demonstratedSkills, List<String> completedLessonIds,
            List<String> sourceRecordIds, Instant generatedAt) {}

    public Snapshot generate(String studentId, String ownerId) {
        var student = students.findById(studentId, ownerId);
        if (!student.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot generate features for an archived student");
        var activities = records.findByStudentIdOrderByAssessedAtDesc(studentId);
        var results = assessments.findByStudentIdOrderByAssessedAtDesc(studentId);
        var observed = observations.findForStudent(studentId, ownerId);
        var lessons = history.findForStudent(studentId, ownerId);
        List<String> missing = new ArrayList<>();
        if (activities.isEmpty()) missing.add("classroom activities");
        if (activities.stream().anyMatch(record -> record.getAttempts() == null || record.getAttempts() < 0
                || record.getTimeTakenMinutes() == null || !Double.isFinite(record.getTimeTakenMinutes()) || record.getTimeTakenMinutes() < 0
                || record.getCompletionStatus() == null || !Set.of("COMPLETED", "IN_PROGRESS").contains(record.getCompletionStatus())
                || !Double.isFinite(record.getScore()) || !Double.isFinite(record.getMaxScore()) || record.getMaxScore() <= 0 || record.getScore() < 0 || record.getScore() > record.getMaxScore())) {
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
        values.put("Average_Score", activities.stream().mapToDouble(record -> record.getScore() * 100 / record.getMaxScore()).average().orElseThrow());
        values.put("Average_Attempts", activities.stream().mapToInt(LearningRecord::getAttempts).average().orElseThrow());
        values.put("Average_Time_Taken", activities.stream().mapToDouble(LearningRecord::getTimeTakenMinutes).average().orElseThrow());
        values.put("Total_Activities", activities.size());
        values.put("Completion_Rate", activities.stream().filter(record -> "COMPLETED".equals(record.getCompletionStatus())).count() * 100.0 / activities.size());
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
        activities.forEach(record -> sources.add("record:" + record.getId())); results.forEach(result -> sources.add("assessment:" + result.getId()));
        sources.add("observation:" + observation.getId()); lessons.forEach(lesson -> sources.add("history:" + lesson.getId()));
        var completedIds = lessons.stream().filter(lesson -> "Completed".equals(lesson.getCompletionStatus())).map(HistoricalLesson::getLessonId).distinct().sorted().toList();
        return new Snapshot(Collections.unmodifiableMap(values), observation.getDemonstratedSkills(), completedIds, List.copyOf(sources), Instant.now());
    }
    private static boolean validMeasure(Double value) { return value != null && Double.isFinite(value) && value >= 0 && value <= 100; }
    private static ResponseStatusException insufficient(List<String> missing) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient learning data: " + String.join(", ", missing));
    }
}
