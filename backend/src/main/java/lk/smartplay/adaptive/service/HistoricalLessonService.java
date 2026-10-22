package lk.smartplay.adaptive.service;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.model.HistoricalLesson;
import lk.smartplay.adaptive.dto.CreateHistoricalLessonRequest;
import lk.smartplay.adaptive.repository.HistoricalLessonRepository;

@Service
public class HistoricalLessonService {
    private final StudentService students;
    private final HistoricalLessonRepository repository;
    public HistoricalLessonService(StudentService students, HistoricalLessonRepository repository) { this.students = students; this.repository = repository; }
    public HistoricalLesson create(String studentId, String ownerId, CreateHistoricalLessonRequest request) {
        return repository.save(prepare(studentId, ownerId, request));
    }
    public HistoricalLesson prepare(String studentId, String ownerId, CreateHistoricalLessonRequest request) {
        var student = students.findById(studentId, ownerId);
        if (!student.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot record lessons for an archived student");
        if (request.lessonId() == null || request.lessonId().isBlank() || request.lessonId().length() > 180 || request.sequenceNumber() == null || request.sequenceNumber() < 1
                || request.completionStatus() == null || !Set.of("Completed", "Not Started", "In Progress").contains(request.completionStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a lesson ID, positive sequence number, and training-compatible completion status");
        }
        Instant at = request.occurredAt() == null ? Instant.now() : request.occurredAt();
        if (at.isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lesson time cannot be in the future");
        return new HistoricalLesson(studentId, ownerId, request.lessonId().trim(), request.sequenceNumber(), request.completionStatus(), at);
    }
    public List<HistoricalLesson> findForStudent(String studentId, String ownerId) {
        students.findById(studentId, ownerId);
        return repository.findByStudentIdAndOwnerIdOrderByOccurredAtDescIdDesc(studentId, ownerId);
    }
}
