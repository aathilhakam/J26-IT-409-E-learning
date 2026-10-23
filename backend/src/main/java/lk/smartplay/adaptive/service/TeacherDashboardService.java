package lk.smartplay.adaptive.service;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

@Service
public class TeacherDashboardService {
    private final StudentRepository students;
    private final LearningRecordRepository records;
    private final ClassroomActivityRepository classroomActivities;
    private final AssessmentResultRepository assessments;
    private final LearningRecommendationRepository recommendations;
    private final ContentAssetRepository materials;
    private final StudentAssignmentRepository assignments;
    private final StudentAssignmentService assignmentService;
    public TeacherDashboardService(StudentRepository students, LearningRecordRepository records, AssessmentResultRepository assessments,
            LearningRecommendationRepository recommendations, ContentAssetRepository materials, StudentAssignmentRepository assignments, StudentAssignmentService assignmentService) {
        this(students, records, null, assessments, recommendations, materials, assignments, assignmentService);
    }
    @Autowired
    public TeacherDashboardService(StudentRepository students, LearningRecordRepository records, ClassroomActivityRepository classroomActivities, AssessmentResultRepository assessments,
            LearningRecommendationRepository recommendations, ContentAssetRepository materials, StudentAssignmentRepository assignments, StudentAssignmentService assignmentService) {
        this.students=students; this.records=records; this.classroomActivities=classroomActivities; this.assessments=assessments; this.recommendations=recommendations; this.materials=materials; this.assignments=assignments; this.assignmentService=assignmentService;
    }
    public record Statistics(long studentsEnrolled, long learningRecords, double learningProgress, long pendingReviews, long validResults, long teacherMaterials, long classroomLessons, long completedLessons, long remainingLessons, long activityRecords) {}
    public Statistics statistics(String owner, String studentId) {
        var roster = students.findByOwnerId(owner).stream().filter(Student::isActive).toList();
        if (studentId != null && roster.stream().noneMatch(s -> s.getId().equals(studentId))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student not found");
        long count=0, pending=0, valid=0;
        for (var student : roster) {
            if (studentId != null && !student.getId().equals(studentId)) continue;
            var activity = records.findByStudentIdOrderByAssessedAtDesc(student.getId());
            var classroomActivity = classroomActivities == null ? List.<ClassroomActivity>of() : classroomActivities.findByStudentIdOrderByAssessedAtDesc(student.getId());
            var results = assessments.findByStudentIdOrderByAssessedAtDesc(student.getId());
            count += activity.size()+classroomActivity.size()+results.size();
            for (var row : activity) if (valid(row.getScore(),row.getMaxScore())) { valid++; }
            for (var row : classroomActivity) if (valid(row.getScore(),row.getMaxScore())) { valid++; }
            for (var row : results) if (valid(row.getScore(),row.getMaxScore())) { valid++; }
            pending += recommendations.findByStudentIdOrderByCreatedAtDesc(student.getId()).stream().filter(r -> r.getStatus()==LearningRecommendation.Status.PENDING).count();
        }
        // Classroom progression excludes private-library materials, which have no assigned recipients.
        var rosterById = new HashMap<String,Student>();
        students.findByOwnerId(owner).forEach(s -> rosterById.put(s.getId(),s));
        var uploaded = materials.findByOwnerIdOrderByUploadedAtDesc(owner);
        var classroomMaterials=uploaded.stream().filter(material -> !"LIBRARY".equals(material.getAssignmentMode())).toList();
        long completed=0;
        for (var material : classroomMaterials) {
            var lesson = lesson(material,rosterById,owner);
            if (isCompleted(lesson)) completed++;
        }
        long total=uploaded.size(), classroomTotal=classroomMaterials.size();
        double progress=classroomTotal==0?0:Math.round(completed*10000.0/classroomTotal)/100.0;
        return new Statistics(roster.size(),total,progress,pending,valid,total,classroomTotal,completed,classroomTotal-completed,count);
    }
    public List<LearningRecommendation> pending(String owner) {
        return students.findByOwnerId(owner).stream().filter(Student::isActive)
            .flatMap(s -> recommendations.findByStudentIdOrderByCreatedAtDesc(s.getId()).stream())
            .filter(r -> r.getStatus()==LearningRecommendation.Status.PENDING)
            .sorted(Comparator.comparing(LearningRecommendation::getCreatedAt,Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }
    public record StudentPathway(String studentId,String studentName,String studentGrade,LearningRecommendation recommendation) {}
    public List<StudentPathway> pathways(String owner) {
        return students.findByOwnerId(owner).stream().filter(Student::isActive).flatMap(student ->
            recommendations.findByStudentIdOrderByCreatedAtDesc(student.getId()).stream()
                .filter(row -> owner.equals(row.getTeacherOwnerId()) && row.getCompletionMilestone()!=null)
                .map(row -> new StudentPathway(student.getId(),student.getName(),student.getGrade(),row)))
            .sorted(Comparator.comparing((StudentPathway row) -> row.recommendation().getCreatedAt(),Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(StudentPathway::studentName).thenComparing(row -> row.recommendation().getId())).toList();
    }
    private boolean valid(double score,double max) { return Double.isFinite(score)&&Double.isFinite(max)&&max>0&&score>=0&&score<=max; }
    public record Recipient(String studentId, String name, String grade, boolean activeStudent, String status, Instant assignedAt, Instant completedAt) {}
    public record Lesson(String id,String title,String description,String subject,String grade,String materialType,String assignmentMode,String assignmentGrade,
            Instant assignedAt,Instant uploadedAt,int assigned,int completed,int inProgress,int notStarted,int unrecorded,Integer overdue,List<Recipient> recipients,String originalFileName,Instant completedAt) {}
    public static boolean isCompleted(Lesson lesson) { return lesson.assigned()>0 && lesson.completed()==lesson.assigned(); }
    private List<Lesson> classroom(String owner,String grade) {
        var roster = new HashMap<String,Student>(); students.findByOwnerId(owner).forEach(s -> roster.put(s.getId(),s));
        return materials.findByOwnerIdOrderByUploadedAtDesc(owner).stream()
            .filter(m -> !"LIBRARY".equals(m.getAssignmentMode()))
            .filter(m -> grade==null || grade.isBlank() || grade.trim().equalsIgnoreCase("GRADE".equals(m.getAssignmentMode())?m.getAssignmentGrade():m.getGrade()))
            .map(m -> lesson(m,roster,owner)).toList();
    }
    public List<Lesson> lessons(String owner) { return lessons(owner,null); }
    public List<Lesson> lessons(String owner,String grade) {
        // Retain the existing persisted assigned/uploaded date ordering and deterministic ID tie-break.
        return classroom(owner,grade).stream().filter(l -> !isCompleted(l))
            .sorted(Comparator.comparing((Lesson l) -> l.assignedAt()==null?l.uploadedAt():l.assignedAt(), Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Lesson::id)).toList();
    }
    public List<Lesson> completedLessons(String owner,String grade) {
        return classroom(owner,grade).stream().filter(TeacherDashboardService::isCompleted)
            .sorted(Comparator.comparing(Lesson::completedAt,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Lesson::id)).toList();
    }
    public Lesson lessonForOwner(String owner, String materialId) {
        var material = materials.findByIdAndOwnerId(materialId,owner)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Lesson not found"));
        if (material.getAssignmentMode().equals("LIBRARY"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Classroom lesson not found");
        var roster = new HashMap<String,Student>(); students.findByOwnerId(owner).forEach(s -> roster.put(s.getId(),s));
        return lesson(material,roster,owner);
    }
    public void completeLesson(String owner, String materialId) {
        var material = materials.findByIdAndOwnerId(materialId, owner)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lesson not found"));
        var lesson = lessonForOwner(owner,materialId);
        if (!isCompleted(lesson)) throw new ResponseStatusException(HttpStatus.CONFLICT,"All assigned students must complete this activity first");
        if (material.getClassroomCompletedAt()==null) { material.completeClassroomLesson(); materials.save(material); }
    }

    private Lesson lesson(ContentAsset m,Map<String,Student> roster,String owner) {
        assignmentService.completeFromSubmittedFeedback(m);
        var states = new HashMap<String,StudentAssignment>(); assignments.findByOwnerIdAndMaterialIdAndActiveTrue(owner,m.getId()).forEach(a -> states.put(a.getStudentId(),a));
        var recipients=m.getAssignedStudentIds().stream().distinct().filter(roster::containsKey).map(id -> {
            var s=roster.get(id); var a=states.get(id); return new Recipient(id,s.getName(),s.getGrade(),s.isActive(),a==null?"UNRECORDED":a.getStatus(),a==null?null:a.getAssignedAt(),a==null?null:a.getCompletedAt());
        }).toList();
        int completed = count(recipients,"COMPLETED");
        Instant completedAt = !recipients.isEmpty() && completed==recipients.size()
            ? recipients.stream().map(Recipient::completedAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(m.getClassroomCompletedAt()) : null;
        Instant at=recipients.stream().map(Recipient::assignedAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
        return new Lesson(m.getId(),m.getTitle(),m.getDescription(),m.getSubject(),m.getGrade(),m.getMaterialType(),m.getAssignmentMode(),m.getAssignmentGrade(),at,m.getUploadedAt(),recipients.size(),
            completed,count(recipients,"IN_PROGRESS"),count(recipients,"NOT_STARTED"),count(recipients,"UNRECORDED"),null,recipients,m.getOriginalFileName(),completedAt);
    }
    private int count(List<Recipient> rows,String status) { return (int) rows.stream().filter(r -> status.equals(r.status())).count(); }
}
