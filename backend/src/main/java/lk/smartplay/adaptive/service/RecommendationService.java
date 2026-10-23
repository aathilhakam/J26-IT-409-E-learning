package lk.smartplay.adaptive.service;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.dto.ReviewRecommendationRequest;
import lk.smartplay.adaptive.model.LearningRecommendation;
import lk.smartplay.adaptive.model.LearningRecommendation.Status;
import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.repository.LearningRecommendationRepository;
import lk.smartplay.adaptive.repository.LearningRecordRepository;
import lk.smartplay.adaptive.repository.ClassroomActivityRepository;

@Service
public class RecommendationService {
    private static final Map<String, Activity> ACTIVITIES = Map.of(
            "literacy", new Activity(
                    "Picture-to-word matching",
                    "Use picture cards and familiar words. Invite the learner to say each word, match it to a picture, and practise the starting sound."),
            "numeracy", new Activity(
                    "Count and build with objects",
                    "Use counters or classroom objects to represent small numbers. Ask the learner to count, group, and explain how they found the answer."),
            "creativity", new Activity(
                    "Make a story picture",
                    "Invite the learner to draw a favourite place, then tell a short story about the people, objects, or events in the picture."),
            "motor", new Activity(
                    "Trace, cut, and create",
                    "Offer a short supervised activity using large shapes: trace a path, tear or cut along a line with age-appropriate tools, and assemble a picture."),
            "critical thinking", new Activity(
                    "Sort and explain",
                    "Provide familiar objects or picture cards. Ask the learner to group them in one way, explain the rule, and then try a different grouping."));

    private final StudentService studentService;
    private final LearningRecordRepository recordRepository;
    private final ClassroomActivityRepository classroomActivityRepository;
    private final LearningRecommendationRepository recommendationRepository;

    public RecommendationService(
            StudentService studentService,
            LearningRecordRepository recordRepository,
            LearningRecommendationRepository recommendationRepository) {
        this(studentService, recordRepository, null, recommendationRepository);
    }

    @Autowired
    public RecommendationService(StudentService studentService, LearningRecordRepository recordRepository,
            ClassroomActivityRepository classroomActivityRepository, LearningRecommendationRepository recommendationRepository) {
        this.studentService = studentService;
        this.recordRepository = recordRepository;
        this.classroomActivityRepository = classroomActivityRepository;
        this.recommendationRepository = recommendationRepository;
    }

    public List<LearningRecommendation> findForStudent(String studentId, String ownerId) {
        studentService.findById(studentId, ownerId);
        return recommendationRepository.findByStudentIdOrderByCreatedAtDesc(studentId);
    }

    public LearningRecommendation generate(String studentId, String ownerId) {
        studentService.findById(studentId, ownerId);
        List<LearningRecord> records = recordRepository.findByStudentIdOrderByAssessedAtDesc(studentId);
        if (classroomActivityRepository != null) records = new java.util.ArrayList<>(records);
        if (classroomActivityRepository != null) records.addAll(classroomActivityRepository.findByStudentIdOrderByAssessedAtDesc(studentId));
        if (records.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Add at least one learning record before generating a recommendation");
        }

        Map<String, List<LearningRecord>> recordsByDomain = new java.util.LinkedHashMap<>();
        records.forEach(record -> recordsByDomain.computeIfAbsent(record.getDomain(), ignored -> new java.util.ArrayList<>())
                .add(record));

        Map.Entry<String, List<LearningRecord>> focus = recordsByDomain.entrySet().stream()
                .min((first, second) -> Double.compare(average(first.getValue()), average(second.getValue())))
                .orElseThrow();
        double average = average(focus.getValue());
        Activity activity = activityFor(focus.getKey());
        String reason = String.format(
                "Recent assessments in %s average %.0f%%. This activity gives the learner a supported way to practise this area.",
                focus.getKey(),
                average);
        double confidence = Math.min(0.95, Math.max(0.55, 0.55 + (100 - average) / 250));

        return recommendationRepository.save(new LearningRecommendation(
                studentId,
                focus.getKey(),
                activity.title(),
                activity.description(),
                reason,
                Math.round(confidence * 100) / 100.0));
    }

    public LearningRecommendation review(String id, ReviewRecommendationRequest request, String ownerId) {
        if (request.status() == Status.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A reviewed recommendation must be approved or rejected");
        }
        LearningRecommendation recommendation = recommendationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recommendation not found"));
        studentService.findById(recommendation.getStudentId(), ownerId);
        recommendation.review(request.status(), request.activityTitle(), request.description(), request.teacherFeedback());
        return recommendationRepository.save(recommendation);
    }

    private static double average(List<LearningRecord> records) {
        return records.stream().mapToDouble(StudentService::percentage).average().orElse(0);
    }

    private static Activity activityFor(String domain) {
        return ACTIVITIES.entrySet().stream()
                .filter(entry -> domain.toLowerCase().contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(new Activity(
                        "Guided practice: " + domain,
                        "Work through a short, hands-on activity in " + domain
                                + ". Model one example, invite the learner to try, and note what support helps."));
    }

    private record Activity(String title, String description) {
    }
}
