package lk.smartplay.adaptive.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.dto.ArchiveStudentRequest;
import lk.smartplay.adaptive.dto.CreateLearningRecordRequest;
import lk.smartplay.adaptive.dto.CreateStudentRequest;
import lk.smartplay.adaptive.dto.DomainProgress;
import lk.smartplay.adaptive.dto.StudentProfileResponse;
import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.model.ClassroomActivity;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.LearningRecordRepository;
import lk.smartplay.adaptive.repository.ClassroomActivityRepository;
import lk.smartplay.adaptive.repository.StudentRepository;

@Service
public class StudentService {

    private final StudentRepository studentRepository;
    private final LearningRecordRepository recordRepository;
    private final ClassroomActivityRepository classroomActivityRepository;

    public StudentService(
            StudentRepository studentRepository,
            LearningRecordRepository recordRepository) {
        this.studentRepository = studentRepository;
        this.recordRepository = recordRepository;
        this.classroomActivityRepository = null;
    }

    @Autowired
    public StudentService(StudentRepository studentRepository, LearningRecordRepository recordRepository,
            ClassroomActivityRepository classroomActivityRepository) {
        this.studentRepository = studentRepository;
        this.recordRepository = recordRepository;
        this.classroomActivityRepository = classroomActivityRepository;
    }

    // Return active students belonging to the current teacher.
    public List<Student> findAll(String ownerId) {
        return findActiveStudents(ownerId);
    }

    public List<Student> findActiveStudents(String ownerId) {
        return studentRepository.findByOwnerId(ownerId).stream()
                .filter(Student::isActive)
                .sorted(Comparator.comparing(
                        Student::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // Return archived students belonging to the current teacher.
    public List<Student> findArchivedStudents(String ownerId) {
        return studentRepository.findByOwnerId(ownerId).stream()
                .filter(student -> !student.isActive())
                .sorted(Comparator.comparing(
                        Student::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // Find a student and verify teacher ownership.
    public Student findById(String id, String ownerId) {
        return studentRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Student not found"));
    }

    // Create a student owned by the current teacher.
    public Student create(CreateStudentRequest request, String ownerId) {
        Student student = new Student(
                request.name().trim(),
                request.grade().trim(),
                ownerId);

        if (request.externalStudentId() != null
                && !request.externalStudentId().isBlank()) {
            student.setExternalStudentId(request.externalStudentId().trim());
        }

        student.setActive(true);
        return studentRepository.save(student);
    }

    // Archive a student without deleting their learning history.
    public Student archiveStudent(
            String studentId,
            ArchiveStudentRequest request,
            String ownerId) {
        Student student = findById(studentId, ownerId);

        if (!student.isActive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Student is already archived");
        }

        String expectedName = student.getName().trim().replaceAll("\\s+", " ");
        String confirmedName = request.studentFullName().trim().replaceAll("\\s+", " ");
        if (!expectedName.equalsIgnoreCase(confirmedName)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Student full name does not match");
        }

        student.setActive(false);
        return studentRepository.save(student);
    }

    // Restore an archived student.
    public Student restoreStudent(String studentId, String ownerId) {
        Student student = findById(studentId, ownerId);

        if (student.isActive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Student is already active");
        }

        student.setActive(true);
        return studentRepository.save(student);
    }

    // Return learning records after checking student ownership.
    public List<LearningRecord> findRecords(String studentId, String ownerId) {
        findById(studentId, ownerId);
        return allActivityRecords(studentId);
    }

    // Add a learning record after validating its score.
    public LearningRecord addRecord(
            String studentId,
            CreateLearningRecordRequest request,
            String ownerId) {
        return recordRepository.save(prepareRecord(studentId, request, ownerId));
    }

    /** Validate ownership and activity data, then return an unsaved learning record. */
    public LearningRecord prepareRecord(
            String studentId,
            CreateLearningRecordRequest request,
            String ownerId) {
        Student student = findById(studentId, ownerId);

        if (!student.isActive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot add learning records to an archived student");
        }

        if (request.maxScore() == null || !Double.isFinite(request.maxScore()) || request.maxScore() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Maximum score must be greater than zero");
        }

        if (request.score() == null || !Double.isFinite(request.score()) || request.score() < 0 || request.score() > request.maxScore()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Score must be between zero and the maximum score");
        }

        String domain = request.domain() == null ? "" : request.domain().trim();
        if (domain.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Learning area is required");
        }

        String notes = request.notes() == null ? "" : request.notes().trim();
        if (request.attempts() != null && request.attempts() < 0
                || request.timeTakenMinutes() != null && (!Double.isFinite(request.timeTakenMinutes()) || request.timeTakenMinutes() < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Activity attempts and duration must be nonnegative");
        }
        String status = request.completionStatus() == null ? null : request.completionStatus().trim().toUpperCase(java.util.Locale.ROOT);
        if (status != null && !java.util.Set.of("COMPLETED", "IN_PROGRESS").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Activity completionStatus must be COMPLETED or IN_PROGRESS");
        }
        LearningRecord record = new LearningRecord(studentId, domain, request.score(), request.maxScore(), notes);
        record.activityDetails(request.attempts(), request.timeTakenMinutes(), status);
        return record;
    }

    // Delete one record belonging to the specified student's records.
    // Ownership is checked before any deletion is performed.
    public void deleteRecord(String studentId, String recordId, String ownerId) {
        findById(studentId, ownerId);

        LearningRecord record = allActivityRecords(studentId)
                .stream()
                .filter(item -> Objects.equals(item.getId(), recordId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Learning record not found for this student"));

        if (record instanceof ClassroomActivity activity && classroomActivityRepository != null) classroomActivityRepository.delete(activity);
        else recordRepository.delete(record);
    }

    // Build the learning profile for a student.
    public StudentProfileResponse profile(String studentId, String ownerId) {
        Student student = findById(studentId, ownerId);
        List<LearningRecord> records = allActivityRecords(studentId);

        Map<String, List<LearningRecord>> byDomain = new LinkedHashMap<>();
        for (LearningRecord record : records) {
            byDomain.computeIfAbsent(record.getDomain(), ignored -> new ArrayList<>())
                    .add(record);
        }

        List<DomainProgress> areas = byDomain.entrySet().stream()
                .map(entry -> summarize(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingDouble(DomainProgress::averagePercentage))
                .toList();

        double overallAverage = records.stream()
                .mapToDouble(StudentService::percentage)
                .average()
                .orElse(0);

        return new StudentProfileResponse(
                student, round(overallAverage), records.size(), areas);
    }

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
            double oldest = percentage(newestFirst.get(newestFirst.size() - 1));
            progress = newest - oldest > 5
                    ? "Improving"
                    : oldest - newest > 5 ? "Needs attention" : "Steady";
        }

        String level = average >= 75
                ? "Strength"
                : average < 60 ? "Needs support" : "Developing";

        return new DomainProgress(domain, round(average), newestFirst.size(), progress, level);
    }

    public static double percentage(LearningRecord record) {
        if (record.getMaxScore() <= 0) {
            return 0;
        }
        return record.getScore() * 100.0 / record.getMaxScore();
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private List<LearningRecord> allActivityRecords(String studentId) {
        List<LearningRecord> combined = new ArrayList<>(recordRepository.findByStudentIdOrderByAssessedAtDesc(studentId));
        if (classroomActivityRepository != null) combined.addAll(classroomActivityRepository.findByStudentIdOrderByAssessedAtDesc(studentId));
        combined.sort(Comparator.comparing(LearningRecord::getAssessedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(LearningRecord::getId, Comparator.nullsLast(String::compareTo)));
        return List.copyOf(combined);
    }
}
