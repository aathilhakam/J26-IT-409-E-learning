package lk.smartplay.adaptive.service;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.model.AssessmentResult;
import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.repository.AssessmentResultRepository;
import lk.smartplay.adaptive.repository.ClassroomActivityRepository;
import lk.smartplay.adaptive.repository.LearningRecordRepository;

/** Builds only the ten inputs used by the isolated exploratory binary model. */
@Service
public class BinaryPrototypeFeatureService {
    public static final List<String> FEATURE_ORDER = List.of(
            "Average_Attempts", "Total_Activities", "Completion_Rate", "Participation", "Attention_Level",
            "Collaboration", "Learning_Difficulty", "Historical_Lessons", "Completed_Historical_Lessons",
            "Historical_Completion_Rate");
    private final StudentService students;
    private final LearningRecordRepository records;
    private final ClassroomActivityRepository classroomActivities;
    private final AssessmentResultRepository assessments;
    private final TeacherObservationService observations;
    private final HistoricalLessonService history;

    public BinaryPrototypeFeatureService(StudentService students, LearningRecordRepository records,
            ClassroomActivityRepository classroomActivities, AssessmentResultRepository assessments,
            TeacherObservationService observations, HistoricalLessonService history) {
        this.students = students; this.records = records; this.classroomActivities = classroomActivities;
        this.assessments = assessments; this.observations = observations; this.history = history;
    }

    public record Snapshot(Map<String, Object> features, List<String> sourceRecordIds) {}
    private record Activity(String id, String activityId, Integer attempts, String status) {}

    /** Completes a six-value workbook snapshot with the four saved teacher observations. */
    public Snapshot completeImportedTemplate(String studentId, String ownerId, Map<String, Object> imported) {
        var student = students.findById(studentId, ownerId);
        if (!student.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot predict for an archived student");
        var observation = observations.findForStudent(studentId, ownerId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Experimental prediction requires saved teacher feedback for participation, attention, collaboration, and learning difficulty."));
        if (observation.getParticipation() == null || !TeacherObservationService.LEVELS.containsKey(observation.getParticipation())
                || observation.getAttentionLevel() == null || !TeacherObservationService.LEVELS.containsKey(observation.getAttentionLevel())
                || observation.getCollaboration() == null || !TeacherObservationService.COLLABORATION.containsKey(observation.getCollaboration())
                || observation.getLearningDifficulty() == null || !TeacherObservationService.DIFFICULTIES.contains(observation.getLearningDifficulty()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Saved teacher feedback is missing one or more required model categories.");

        var values = new LinkedHashMap<String, Object>(imported == null ? Map.of() : imported);
        validateImportedNumericValues(values);
        values.put("Participation", observation.getParticipation());
        values.put("Attention_Level", observation.getAttentionLevel());
        values.put("Collaboration", observation.getCollaboration());
        values.put("Learning_Difficulty", observation.getLearningDifficulty());
        if (!values.keySet().containsAll(FEATURE_ORDER))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The imported experimental template is missing required numeric values.");
        var ordered = new LinkedHashMap<String, Object>();
        FEATURE_ORDER.forEach(feature -> ordered.put(feature, values.get(feature)));
        return new Snapshot(Collections.unmodifiableMap(ordered), List.of("observation:" + observation.getId()));
    }

    private static void validateImportedNumericValues(Map<String, Object> values) {
        for (String feature : List.of("Average_Attempts", "Total_Activities", "Completion_Rate", "Historical_Lessons",
                "Completed_Historical_Lessons", "Historical_Completion_Rate")) {
            Object raw = values.get(feature);
            if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Imported experimental template has a missing, nonnumeric, or negative value for " + feature + ". Reimport a valid workbook.");
            }
        }
        for (String feature : List.of("Total_Activities", "Historical_Lessons", "Completed_Historical_Lessons")) {
            Number number = (Number) values.get(feature);
            if (number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Imported experimental template requires a whole-number value for " + feature + ". Reimport a valid workbook.");
            }
        }
        for (String feature : List.of("Completion_Rate", "Historical_Completion_Rate")) {
            if (((Number) values.get(feature)).doubleValue() > 100) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Imported experimental template requires " + feature + " to be between 0 and 100. Reimport a valid workbook.");
            }
        }
        long lessons = ((Number) values.get("Historical_Lessons")).longValue();
        long completed = ((Number) values.get("Completed_Historical_Lessons")).longValue();
        if (completed > lessons) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Completed_Historical_Lessons cannot exceed Historical_Lessons. Reimport a valid workbook.");
        }
    }

    public Snapshot generate(String studentId, String ownerId) {
        var student = students.findById(studentId, ownerId);
        if (!student.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot predict for an archived student");
        List<String> missing = new ArrayList<>();
        List<Activity> activities = activities(studentId, ownerId);
        if (activities.isEmpty()) missing.add("at least one saved activity record");
        if (activities.stream().anyMatch(activity -> activity.attempts() == null || activity.attempts() < 0))
            missing.add("non-negative attempts for every activity record");
        if (activities.stream().anyMatch(activity -> activity.status() == null || !Set.of("COMPLETED", "IN_PROGRESS", "NOT_COMPLETED").contains(activity.status())))
            missing.add("a recognized completion status for every activity record");

        var observed = observations.findForStudent(studentId, ownerId);
        if (observed.isEmpty()) missing.add("teacher observations: participation, attention, collaboration, and learning difficulty");
        var lessons = history.findForStudent(studentId, ownerId);
        if (lessons.isEmpty()) missing.add("at least one historical lesson with sequence and completion status");
        if (lessons.stream().anyMatch(lesson -> lesson.getSequenceNumber() < 1 || lesson.getLessonId() == null
                || lesson.getLessonId().isBlank() || lesson.getCompletionStatus() == null
                || !Set.of("Completed", "Not Started", "In Progress").contains(lesson.getCompletionStatus())))
            missing.add("valid sequence number and completion status for every historical lesson");

        var observation = observed.isEmpty() ? null : observed.get(0);
        if (observation != null && (observation.getParticipation() == null || !TeacherObservationService.LEVELS.containsKey(observation.getParticipation())
                || observation.getAttentionLevel() == null || !TeacherObservationService.LEVELS.containsKey(observation.getAttentionLevel())
                || observation.getCollaboration() == null || !TeacherObservationService.COLLABORATION.containsKey(observation.getCollaboration())
                || observation.getLearningDifficulty() == null || !TeacherObservationService.DIFFICULTIES.contains(observation.getLearningDifficulty())))
            missing.add("valid training-compatible teacher observation categories");
        if (!missing.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Binary prototype prediction unavailable; missing or invalid data: " + String.join("; ", missing));

        long completedActivities = activities.stream().filter(activity -> "COMPLETED".equals(activity.status())).count();
        long completedLessons = lessons.stream().filter(lesson -> "Completed".equals(lesson.getCompletionStatus())).count();
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("Average_Attempts", activities.stream().mapToInt(Activity::attempts).average().orElseThrow());
        features.put("Total_Activities", activities.size());
        features.put("Completion_Rate", completedActivities * 100.0 / activities.size());
        features.put("Participation", observation.getParticipation());
        features.put("Attention_Level", observation.getAttentionLevel());
        features.put("Collaboration", observation.getCollaboration());
        features.put("Learning_Difficulty", observation.getLearningDifficulty());
        features.put("Historical_Lessons", lessons.size());
        features.put("Completed_Historical_Lessons", completedLessons);
        features.put("Historical_Completion_Rate", completedLessons * 100.0 / lessons.size());
        List<String> sourceIds = new ArrayList<>();
        activities.forEach(activity -> sourceIds.add("activity:" + activity.id()));
        sourceIds.add("observation:" + observation.getId());
        lessons.forEach(lesson -> sourceIds.add("history:" + lesson.getId()));
        return new Snapshot(Collections.unmodifiableMap(features), List.copyOf(sourceIds));
    }

    private List<Activity> activities(String studentId, String ownerId) {
        List<Activity> result = new ArrayList<>();
        Set<String> activityIds = new HashSet<>();
        for (LearningRecord record : records.findByStudentIdOrderByAssessedAtDesc(studentId)) {
            if (record.getTeacherOwnerId() != null && !record.getTeacherOwnerId().equals(ownerId)) continue;
            result.add(new Activity(record.getId(), record.getActivityId(), record.getAttempts(), record.getCompletionStatus()));
            if (record.getActivityId() != null && !record.getActivityId().isBlank()) activityIds.add(record.getActivityId());
        }
        for (LearningRecord record : classroomActivities.findByStudentIdOrderByAssessedAtDesc(studentId)) {
            if (record.getTeacherOwnerId() != null && !record.getTeacherOwnerId().equals(ownerId)) continue;
            if (record.getActivityId() != null && !record.getActivityId().isBlank() && !activityIds.add(record.getActivityId())) continue;
            result.add(new Activity(record.getId(), record.getActivityId(), record.getAttempts(), record.getCompletionStatus()));
        }
        for (AssessmentResult assessment : assessments.findByStudentIdOrderByAssessedAtDesc(studentId)) {
            if (!assessment.isWorksheet() || !ownerId.equals(assessment.getTeacherOwnerId())) continue;
            if (assessment.getActivityId() != null && !assessment.getActivityId().isBlank() && activityIds.contains(assessment.getActivityId())) continue;
            result.add(new Activity(assessment.getId(), assessment.getActivityId(), assessment.getAttempts(), assessment.getCompletionStatus()));
        }
        return List.copyOf(result);
    }
}
