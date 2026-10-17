
package lk.smartplay.adaptive.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public StudentService(
            StudentRepository studentRepository,
            LearningRecordRepository recordRepository) {

        this.studentRepository = studentRepository;
        this.recordRepository = recordRepository;
    }

    // Get all students belonging to the teacher
    public List<Student> findAll(String ownerId) {
        return studentRepository.findByOwnerId(ownerId).stream()
                .sorted(Comparator.comparing(Student::getCreatedAt).reversed())
                .toList();
    }

    // Get student by MongoDB ID and verify teacher ownership
    public Student findById(String id, String ownerId) {
        return studentRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Student not found"));
    }

    // UPDATED: Create student with optional externalStudentId
    public Student create(CreateStudentRequest request, String ownerId) {

        // Step 1: Create the student object
        Student student = new Student(
                request.name().trim(),
                request.grade().trim(),
                ownerId
        );

        // Step 2: Check if external student ID is provided
        if (request.externalStudentId() != null
                && !request.externalStudentId().isBlank()) {

            // Step 3: Set external ID, e.g., ST001
            student.setExternalStudentId(
                    request.externalStudentId().trim()
            );
        }

        // Step 4: Save student to MongoDB
        return studentRepository.save(student);
    }

    // Get student's learning records
    public List<LearningRecord> findRecords(
            String studentId,
            String ownerId) {

        findById(studentId, ownerId);

        return recordRepository
                .findByStudentIdOrderByAssessedAtDesc(studentId);
    }

    // Add new learning assessment record
    public LearningRecord addRecord(
            String studentId,
            CreateLearningRecordRequest request,
            String ownerId) {

        findById(studentId, ownerId);

        if (request.score() > request.maxScore()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Score cannot exceed the maximum score");
        }

        return recordRepository.save(new LearningRecord(
                studentId,
                request.domain().trim(),
                request.score(),
                request.maxScore(),
                request.notes() == null ? "" : request.notes().trim()
        ));
    }

    // Get student learning profile
    public StudentProfileResponse profile(
            String studentId,
            String ownerId) {

        Student student = findById(studentId, ownerId);

        List<LearningRecord> records = recordRepository
                .findByStudentIdOrderByAssessedAtDesc(studentId);

        Map<String, List<LearningRecord>> byDomain =
                new LinkedHashMap<>();

        for (LearningRecord record : records) {
            byDomain.computeIfAbsent(
                    record.getDomain(),
                    ignored -> new ArrayList<>()
            ).add(record);
        }

        List<DomainProgress> areas = byDomain.entrySet().stream()
                .map(entry -> summarize(
                        entry.getKey(),
                        entry.getValue()
                ))
                .sorted(Comparator.comparingDouble(
                        DomainProgress::averagePercentage
                ))
                .toList();

        double overallAverage = records.stream()
                .mapToDouble(StudentService::percentage)
                .average()
                .orElse(0);

        return new StudentProfileResponse(
                student,
                round(overallAverage),
                records.size(),
                areas
        );
    }

    // Calculate performance by learning domain
    private static DomainProgress summarize(
            String domain,
            List<LearningRecord> newestFirst) {

        double average = newestFirst.stream()
                .mapToDouble(StudentService::percentage)
                .average()
                .orElse(0);

        String progress = "Starting";

        if (newestFirst.size() >= 2) {

            double newest = percentage(newestFirst.get(0));

            double oldest = percentage(
                    newestFirst.get(newestFirst.size() - 1)
            );

            progress = newest - oldest > 5
                    ? "Improving"
                    : oldest - newest > 5
                    ? "Needs attention"
                    : "Steady";
        }

        String level = average >= 75
                ? "Strength"
                : average < 60
                ? "Needs support"
                : "Developing";

        return new DomainProgress(
                domain,
                round(average),
                newestFirst.size(),
                progress,
                level
        );
    }

    // Convert assessment score into percentage
    public static double percentage(LearningRecord record) {
        return record.getScore() * 100 / record.getMaxScore();
    }

    // Round percentage to one decimal place
    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
