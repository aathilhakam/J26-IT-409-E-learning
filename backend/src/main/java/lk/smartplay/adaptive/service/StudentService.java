package lk.smartplay.adaptive.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.dto.CreateLearningRecordRequest;
import lk.smartplay.adaptive.dto.CreateStudentRequest;
import lk.smartplay.adaptive.dto.DomainProgress;
import lk.smartplay.adaptive.dto.StudentProfileResponse;
import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.LearningRecordRepository;
import lk.smartplay.adaptive.repository.StudentRepository;

@Service
public class StudentService {
    private final StudentRepository studentRepository;
    private final LearningRecordRepository recordRepository;

    public StudentService(StudentRepository studentRepository, LearningRecordRepository recordRepository) {
        this.studentRepository = studentRepository;
        this.recordRepository = recordRepository;
    }

    public List<Student> findAll(String ownerId) {
        return studentRepository.findByOwnerId(ownerId).stream()
                .sorted(Comparator.comparing(Student::getCreatedAt).reversed())
                .toList();
    }

    public Student findById(String id, String ownerId) {
        return studentRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));
    }

    public Student create(CreateStudentRequest request, String ownerId) {
        return studentRepository.save(new Student(request.name().trim(), request.grade().trim(), ownerId));
    }

    public List<LearningRecord> findRecords(String studentId, String ownerId) {
        findById(studentId, ownerId);
        return recordRepository.findByStudentIdOrderByAssessedAtDesc(studentId);
    }

    public LearningRecord addRecord(String studentId, CreateLearningRecordRequest request, String ownerId) {
        findById(studentId, ownerId);
        if (!Double.isFinite(request.score()) || !Double.isFinite(request.maxScore())
                || request.score() > request.maxScore()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Score cannot exceed the maximum score");
        }
        var assessment = request.creativityAssessment();
        if (assessment != null) {
            var scores = assessment.teacherScores();
            if (!"creativity".equalsIgnoreCase(request.domain().trim())
                    || !Integer.valueOf(10).equals(assessment.teacherScoreScale())
                    || request.maxScore() != 10.0
                    || scores == null
                    || !scores.keySet().equals(Set.of("originality", "imagination", "elaboration", "storytelling"))
                    || scores.values().stream().anyMatch(value -> value == null || !Double.isFinite(value)
                            || value < 1 || value > 10 || value != Math.rint(value))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Teacher observation requires all four whole-number scores from 1 to 10 and scale 10");
            }
            double average = scores.values().stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            if (Math.abs(request.score() - average) > 0.000001) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Creativity score must equal the teacher rubric average");
            }
        }
        LearningRecord record = new LearningRecord(
                studentId,
                request.domain().trim(),
                request.score(),
                request.maxScore(),
                request.notes() == null ? "" : request.notes().trim());
        record.setCreativityAssessment(assessment);
        return recordRepository.save(record);
    }

    public StudentProfileResponse profile(String studentId, String ownerId) {
        Student student = findById(studentId, ownerId);
        List<LearningRecord> records = recordRepository.findByStudentIdOrderByAssessedAtDesc(studentId);
        Map<String, List<LearningRecord>> byDomain = new LinkedHashMap<>();
        for (LearningRecord record : records) {
            byDomain.computeIfAbsent(record.getDomain(), ignored -> new ArrayList<>()).add(record);
        }

        List<DomainProgress> areas = byDomain.entrySet().stream()
                .map(entry -> summarize(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingDouble(DomainProgress::averagePercentage))
                .toList();
        double overallAverage = records.stream()
                .mapToDouble(StudentService::percentage)
                .average()
                .orElse(0);

        return new StudentProfileResponse(student, round(overallAverage), records.size(), areas);
    }

    private static DomainProgress summarize(String domain, List<LearningRecord> newestFirst) {
        double average = newestFirst.stream().mapToDouble(StudentService::percentage).average().orElse(0);
        String progress = "Starting";
        if (newestFirst.size() >= 2) {
            double newest = percentage(newestFirst.get(0));
            double oldest = percentage(newestFirst.get(newestFirst.size() - 1));
            progress = newest - oldest > 5 ? "Improving" : oldest - newest > 5 ? "Needs attention" : "Steady";
        }
        String level = average >= 75 ? "Strength" : average < 60 ? "Needs support" : "Developing";
        return new DomainProgress(domain, round(average), newestFirst.size(), progress, level);
    }

    public static double percentage(LearningRecord record) {
        return record.getScore() * 100 / record.getMaxScore();
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
