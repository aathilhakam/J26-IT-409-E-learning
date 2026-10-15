package lk.smartplay.adaptive.controller;

import java.util.List;

import jakarta.validation.Valid;
import lk.smartplay.adaptive.dto.CreateLearningRecordRequest;
import lk.smartplay.adaptive.dto.CreateStudentRequest;
import lk.smartplay.adaptive.dto.StudentProfileResponse;
import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.service.RecommendationService;
import lk.smartplay.adaptive.service.StudentService;
import lk.smartplay.adaptive.service.TeacherAccountService;
import lk.smartplay.adaptive.model.LearningRecommendation;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StudentController {
    private final StudentService studentService;
    private final RecommendationService recommendationService;
    private final TeacherAccountService accountService;

    public StudentController(
            StudentService studentService,
            RecommendationService recommendationService,
            TeacherAccountService accountService) {
        this.studentService = studentService;
        this.recommendationService = recommendationService;
        this.accountService = accountService;
    }

    @GetMapping("/students")
    public List<Student> getStudents(Authentication authentication) {
        return studentService.findAll(ownerId(authentication));
    }

    @PostMapping("/students")
    public Student createStudent(
            @Valid @RequestBody CreateStudentRequest request,
            Authentication authentication) {
        return studentService.create(request, ownerId(authentication));
    }

    @GetMapping("/students/{studentId}")
    public Student getStudent(@PathVariable String studentId, Authentication authentication) {
        return studentService.findById(studentId, ownerId(authentication));
    }

    @GetMapping("/students/{studentId}/profile")
    public StudentProfileResponse getProfile(@PathVariable String studentId, Authentication authentication) {
        return studentService.profile(studentId, ownerId(authentication));
    }

    @GetMapping("/students/{studentId}/records")
    public List<LearningRecord> getRecords(@PathVariable String studentId, Authentication authentication) {
        return studentService.findRecords(studentId, ownerId(authentication));
    }

    @PostMapping("/students/{studentId}/records")
    public LearningRecord createRecord(
            @PathVariable String studentId,
            @Valid @RequestBody CreateLearningRecordRequest request,
            Authentication authentication) {
        return studentService.addRecord(studentId, request, ownerId(authentication));
    }

    @GetMapping("/students/{studentId}/recommendations")
    public List<LearningRecommendation> getRecommendations(@PathVariable String studentId, Authentication authentication) {
        return recommendationService.findForStudent(studentId, ownerId(authentication));
    }

    @PostMapping("/students/{studentId}/recommendations")
    public LearningRecommendation generateRecommendation(@PathVariable String studentId, Authentication authentication) {
        return recommendationService.generate(studentId, ownerId(authentication));
    }

    private String ownerId(Authentication authentication) {
        return accountService.accountIdForEmail(authentication.getName());
    }
}
