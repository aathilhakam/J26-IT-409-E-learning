package lk.smartplay.adaptive.service;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.model.TeacherObservation;
import lk.smartplay.adaptive.dto.CreateTeacherObservationRequest;
import lk.smartplay.adaptive.repository.TeacherObservationRepository;

@Service
public class TeacherObservationService {
    public static final Map<String,Integer> LEVELS = Map.of("Low",1,"Medium",2,"High",3);
    public static final Map<String,Integer> COLLABORATION = Map.of("Fair",1,"Needs improvement",2,"Good",3,"Excellent",4);
    public static final Set<String> DIFFICULTIES = Set.of("Addition difficulty", "Letter formation", "Listening difficulty", "None identified", "Number recognition", "Pencil control", "Phonics difficulty", "Reading difficulty", "Shape recognition", "Slow writing", "Vocabulary development");
    public static final Set<String> STYLES = Set.of("Auditory learner", "Creative learner", "Logical learner", "Practical learner", "Visual learner");
    private final StudentService students;
    private final TeacherObservationRepository repository;
    public TeacherObservationService(StudentService students, TeacherObservationRepository repository) { this.students = students; this.repository = repository; }
    public TeacherObservation create(String studentId, String ownerId, CreateTeacherObservationRequest request) {
        return repository.save(prepare(studentId, ownerId, request));
    }
    public TeacherObservation prepare(String studentId, String ownerId, CreateTeacherObservationRequest request) {
        return prepare(studentId,ownerId,request,true);
    }
    public TeacherObservation prepareFeedback(String studentId, String ownerId, CreateTeacherObservationRequest request) {
        return prepare(studentId,ownerId,request,false);
    }
    private TeacherObservation prepare(String studentId, String ownerId, CreateTeacherObservationRequest request, boolean requireDifficulty) {
        var student = students.findById(studentId, ownerId);
        if (!student.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot observe an archived student");
        if (request.participation() == null || request.attentionLevel() == null || request.confidence() == null || request.collaboration() == null
                || request.learningStyle() == null || !LEVELS.containsKey(request.participation()) || !LEVELS.containsKey(request.attentionLevel()) || !LEVELS.containsKey(request.confidence())
                || !COLLABORATION.containsKey(request.collaboration()) || !STYLES.contains(request.learningStyle())
                || (requireDifficulty && (request.learningDifficulty() == null || request.learningDifficulty().isBlank()))
                || (request.learningDifficulty() != null && !request.learningDifficulty().isBlank() && !DIFFICULTIES.contains(request.learningDifficulty()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use the supported teacher-observation categories exactly");
        }
        var skills = request.demonstratedSkills() == null ? List.<String>of() : request.demonstratedSkills();
        if (skills.size() > 200 || skills.stream().anyMatch(skill -> skill == null || skill.isBlank() || skill.length() > 180))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Demonstrated skills must be nonempty phrases up to 180 characters");
        Instant at = request.observedAt() == null ? Instant.now() : request.observedAt();
        if (at.isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Observation time cannot be in the future");
        return new TeacherObservation(studentId, ownerId, request.participation(), request.attentionLevel(), request.confidence(),
                request.collaboration(), request.learningDifficulty(), request.learningStyle(), skills.stream().map(String::trim).distinct().toList(), at);
    }
    public List<TeacherObservation> findForStudent(String studentId, String ownerId) {
        students.findById(studentId, ownerId);
        return repository.findByStudentIdAndOwnerIdOrderByObservedAtDescRecordedAtDescIdDesc(studentId, ownerId);
    }
}
