
package lk.smartplay.adaptive.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.dto.CreateAssessmentResultRequest;
import lk.smartplay.adaptive.model.AssessmentResult;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.AssessmentResultRepository;

@Service
public class AssessmentResultService {

    private static final Set<String> COMPONENTS = Set.of(
            "MOTOR_SKILLS",
            "CREATIVITY_THINKING",
            "TEACHER_ASSESSMENT",
            "CLASSROOM_ACTIVITY",
            "WORKSHEET_ASSESSMENT",
            "PARENT_REINFORCEMENT",
            "OTHER"
    );

    private final AssessmentResultRepository repository;
    private final StudentService studentService;

    public AssessmentResultService(
            AssessmentResultRepository repository,
            StudentService studentService) {
        this.repository = repository;
        this.studentService = studentService;
    }

    public AssessmentResult create(
            String studentId,
            String ownerId,
            CreateAssessmentResultRequest request) {

        return repository.save(prepare(studentId,ownerId,request));
    }

    public AssessmentResult createImported(String studentId, String ownerId,
            CreateAssessmentResultRequest request, String stableId) {
        AssessmentResult result = prepare(studentId, ownerId, request);
        result.setId(stableId);
        return repository.insert(result);
    }

    private AssessmentResult prepare(String studentId,String ownerId,CreateAssessmentResultRequest request) {
        Student student = studentService.findById(studentId, ownerId);

        if (!student.isActive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot add assessments to an archived student"
            );
        }

        String component = request.sourceComponent()
                .trim().toUpperCase(Locale.ROOT);

        if (!COMPONENTS.contains(component)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported sourceComponent"
            );
        }

        if (request.score() == null || request.maxScore() == null || !Double.isFinite(request.score())
                || !Double.isFinite(request.maxScore()) || request.score() < 0 || request.maxScore() <= 0
                || request.score() > request.maxScore()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Score cannot exceed maximum score"
            );
        }

        String status = request.completionStatus()
                .trim().toUpperCase(Locale.ROOT);

        if (!Set.of("COMPLETED", "IN_PROGRESS", "NOT_COMPLETED")
                .contains(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported completionStatus"
            );
        }

        java.util.Map<String, Double> measures = request.measures() == null ? java.util.Map.of() : request.measures();
        for (var entry : measures.entrySet()) {
            if (!LiveFeatureService.ASSESSMENT_MEASURES.contains(entry.getKey()) || entry.getValue() == null
                    || !Double.isFinite(entry.getValue()) || entry.getValue() < 0 || entry.getValue() > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assessment measures must use the named model measures on a finite 0–100 scale");
            }
        }
        AssessmentResult result = new AssessmentResult();
        result.setMeasures(measures);

        result.setStudentId(student.getId());
        result.setExternalStudentId(student.getExternalStudentId());
        result.setSourceComponent(component);

        // NEW: Save the assessment type
        String assessmentType = request.assessmentType();

        result.setAssessmentType(
                assessmentType == null || assessmentType.isBlank()
                        ? null
                        : assessmentType.trim().toUpperCase(Locale.ROOT)
        );

        result.setAssessmentId(request.assessmentId().trim());
        result.setActivityId(request.activityId());
        result.setScore(request.score());
        result.setMaxScore(request.maxScore());
        result.setAttempts(request.attempts());
        result.setTimeTakenMinutes(request.timeTakenMinutes());
        result.setCompletionStatus(status);
        result.setNotes(
                request.notes() == null ? "" : request.notes().trim()
        );
        result.setAssessedAt(Instant.now());

        return result;
    }

    public AssessmentResult createWorksheet(String studentId,String owner,String identity,String material,String title,String subject,java.time.LocalDate date,double score,String feedback) {
        return createWorksheet(studentId,owner,identity,material,title,subject,date,score,10.0,"WORKSHEET",null,null,"COMPLETED",feedback);
    }

    public AssessmentResult createWorksheet(String studentId,String owner,String identity,String material,String title,String subject,java.time.LocalDate date,double score,String feedback,Integer attempts,Double timeTakenMinutes,String completionStatus) {
        return createWorksheet(studentId,owner,identity,material,title,subject,date,score,10.0,"WORKSHEET",attempts,timeTakenMinutes,completionStatus,feedback);
    }

    public AssessmentResult createWorksheet(String studentId,String owner,String identity,String material,String title,String subject,java.time.LocalDate date,double score,double maxScore,String assessmentType,Integer attempts,Double timeTakenMinutes,String completionStatus) {
        return createWorksheet(studentId,owner,identity,material,title,subject,date,score,maxScore,assessmentType,attempts,timeTakenMinutes,completionStatus,"");
    }

    private AssessmentResult createWorksheet(String studentId,String owner,String identity,String material,String title,String subject,java.time.LocalDate date,double score,double maxScore,String assessmentType,Integer attempts,Double timeTakenMinutes,String completionStatus,String feedback) {
        var student=studentService.findById(studentId,owner);
        var result=prepare(studentId,owner,new CreateAssessmentResultRequest("TEACHER_ASSESSMENT",assessmentType,identity,material,score,maxScore,attempts,timeTakenMinutes,completionStatus,feedback,java.util.Map.of()));
        result.worksheetDetails(identity,owner,student.getName(),title,subject,student.getGrade(),material,date);
        return repository.insert(result);
    }

    public List<AssessmentResult> findForStudent(
            String studentId,
            String ownerId) {

        studentService.findById(studentId, ownerId);

        return repository.findByStudentIdOrderByAssessedAtDesc(studentId);
    }
}
