package lk.smartplay.adaptive.service;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.dto.*;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

@Service
public class FeedbackSubmissionService {
    private final LessonFeedbackService evidence;
    private final LessonFeedbackRepository repository;
    private final StudentService students;
    private final ObjectMapper mapper;
    private final ContentAssetRepository materials;
    private final StudentAssignmentService assignments;
    public FeedbackSubmissionService(LessonFeedbackService evidence,LessonFeedbackRepository repository,StudentService students,ObjectMapper mapper,ContentAssetRepository materials,StudentAssignmentService assignments) {
        this.evidence=evidence;this.repository=repository;this.students=students;this.mapper=mapper;this.materials=materials;this.assignments=assignments;
    }
    public record Workspace(TeacherDashboardService.Lesson lesson,int weekPosition,boolean completed,List<LessonFeedbackService.Saved> saved,List<LessonFeedback> drafts,List<LessonFeedback> submitted) {}
    public Workspace workspace(String owner,String material) {
        var old=evidence.workspace(owner,material);
        return new Workspace(old.lesson(),old.weekPosition(),old.completed(),old.saved(),forLesson(owner,material,"DRAFT"),forLesson(owner,material,"SUBMITTED"));
    }
    public List<LessonFeedback> forLesson(String owner,String material,String status) {
        evidence.workspace(owner,material); // Owned classroom material, never another teacher's library.
        return repository.findByOwnerIdAndMaterialIdAndSubmissionStatusOrderBySubmittedAtDescUpdatedAtDesc(owner,material,status);
    }
    public List<LessonFeedback> forStudent(String owner,String student) {
        students.findById(student,owner);
        return repository.findByOwnerIdAndStudentIdAndSubmissionStatusOrderBySubmittedAtDescUpdatedAtDesc(owner,student,"SUBMITTED");
    }
    public List<LessonFeedback> forTeacher(String owner,String status) {
        if(!Set.of("DRAFT","SUBMITTED").contains(status)) throw bad("Choose DRAFT or SUBMITTED");
        return repository.findByOwnerIdAndSubmissionStatusOrderBySubmittedAtDescUpdatedAtDesc(owner,status);
    }
    private record Prepared(LessonFeedbackRequest.Entry entry,LessonFeedback record,boolean alreadySubmitted,boolean unchanged,boolean updateStatus) {}
    public record Outcome(String studentId,String status,String message,LessonFeedback record) {}
    public List<Outcome> save(String owner,String material,LessonFeedbackRequest request,boolean submit) {
        var context=evidence.workspace(owner,material);
        if(!context.completed() && context.weekPosition()!=1) throw conflict("Feedback can be saved for the current week's open lesson only");
        if(request==null||request.students()==null||request.students().isEmpty()||request.students().size()>200||request.students().stream().anyMatch(Objects::isNull)
            ||request.students().stream().map(LessonFeedbackRequest.Entry::studentId).distinct().count()!=request.students().size()) throw bad("Select unique students for this lesson");
        var recipients=new HashMap<String,TeacherDashboardService.Recipient>();context.lesson().recipients().stream().filter(TeacherDashboardService.Recipient::activeStudent).forEach(r->recipients.put(r.studentId(),r));
        var prepared=new ArrayList<Prepared>();
        for(var original:request.students()) {
            var recipient=recipients.get(original.studentId());
            if(recipient==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student is not an active recipient of this lesson");
            var student=students.findById(original.studentId(),owner);if(!student.isActive())throw conflict("Cannot save feedback for an archived student");
            var normalized=normalize(submit ? withStatus(original,"COMPLETED") : original,recipient.status());
            String id=TeacherObservation.lessonEvidenceId(owner,material,normalized.studentId())+"-"+normalized.draftKey();
            var existing=repository.findByIdAndOwnerId(id,owner);
            boolean updateStatus=submit || original.status()!=null&&!original.status().isBlank();
            var entry=!updateStatus&&existing.isPresent()&&"SUBMITTED".equals(existing.get().getSubmissionStatus())?normalize(original,existing.get().getActivityStatus()):normalized;
            String fingerprint=fingerprint(entry);
            boolean submitted=existing.isPresent()&&"SUBMITTED".equals(existing.get().getSubmissionStatus());
            if(context.completed() && updateStatus && !"COMPLETED".equals(entry.status()) && !submitted) throw conflict("Completed lessons cannot be reopened through feedback");
            boolean unchanged=existing.isPresent()&&fingerprint.equals(existing.get().getFingerprint());
            if(submitted && (!submit||!unchanged))throw conflict("Submitted feedback cannot be overwritten. Create a new feedback revision first.");
            if(!submitted&&existing.isPresent()&&!unchanged&&!Objects.equals(existing.get().getVersion(),entry.expectedVersion()))throw conflict("This draft changed in another session. Reload it before editing.");
            var record=existing.orElseGet(()->new LessonFeedback(owner,material,context.lesson().title(),entry.studentId(),recipient.name(),recipient.grade(),entry.draftKey()));
            if(!submitted&&!unchanged)record.update(entry,fingerprint);
            prepared.add(new Prepared(entry,record,submitted,unchanged,updateStatus));
        }
        if(submit && prepared.stream().allMatch(Prepared::alreadySubmitted)) return prepared.stream().map(p -> new Outcome(p.entry().studentId(),"SAVED","Already submitted; no duplicate created",p.record())).toList();
        if(submit) evidence.validateSubmission(owner,material,new LessonFeedbackRequest(prepared.stream().map(Prepared::entry).toList()));
        var results=new ArrayList<Outcome>();
        for(var item:prepared) {
            LessonFeedback record=item.record();
            try {
                if(item.alreadySubmitted()) {results.add(new Outcome(record.getStudentId(),"SAVED","Already submitted; no duplicate created",record));continue;}
                if(!item.unchanged()) record=repository.save(record); // @Version prevents stale draft updates.
                if(submit) {
                    var projected=evidence.save(owner,material,new LessonFeedbackRequest(List.of(item.entry())),item.updateStatus()).get(0);
                    if(!"SAVED".equals(projected.status()))throw new IllegalStateException("Evidence synchronization incomplete");
                    record.submit();record=repository.save(record);
                    assignments.completeFromSubmittedFeedback(materials.findByIdAndOwnerId(material,owner).orElseThrow());
                }
                results.add(new Outcome(record.getStudentId(),"SAVED",submit?"Feedback submitted":"Draft saved",record));
            } catch(org.springframework.dao.OptimisticLockingFailureException|org.springframework.dao.DuplicateKeyException race) {
                var current=repository.findByIdAndOwnerId(item.record().getId(),owner);
                if(current.isPresent()&&Objects.equals(current.get().getFingerprint(),item.record().getFingerprint())
                    &&(!submit||"SUBMITTED".equals(current.get().getSubmissionStatus()))) results.add(new Outcome(item.entry().studentId(),"SAVED","Already saved; no duplicate created",current.get()));
                else results.add(new Outcome(item.entry().studentId(),"FAILED","Draft changed during saving. Reload before retrying.",null));
            } catch(RuntimeException error) {
                results.add(new Outcome(item.entry().studentId(),"FAILED","Could not finish saving this student. Retry the same draft safely.",null));
            }
        }
        return results;
    }
    private LessonFeedbackRequest.Entry normalize(LessonFeedbackRequest.Entry row,String assignmentStatus) {
        String key=row.draftKey()==null?"initial":row.draftKey();if(!key.matches("[A-Za-z0-9-]{1,100}"))throw bad("Invalid draft identifier");
        if(row.observation()==null)throw bad("Provide an observation object for every selected student");
        var o=row.observation();
        if(!optional(o.participation(),TeacherObservationService.LEVELS.keySet())||!optional(o.attentionLevel(),TeacherObservationService.LEVELS.keySet())||!optional(o.confidence(),TeacherObservationService.LEVELS.keySet())
            ||!optional(o.collaboration(),TeacherObservationService.COLLABORATION.keySet())||!optional(o.learningDifficulty(),TeacherObservationService.DIFFICULTIES)||!optional(o.learningStyle(),TeacherObservationService.STYLES))throw bad("Use supported observation categories");
        var skills=o.demonstratedSkills()==null?List.<String>of():o.demonstratedSkills();if(skills.size()>200||skills.stream().anyMatch(s->s==null||s.isBlank()||s.length()>180))throw bad("Use up to 200 skills, each up to 180 characters");
        if(!optional(row.status(),Set.of("NOT_STARTED","IN_PROGRESS","COMPLETED")))throw bad("Choose a supported activity status");
        if(tooLong(row.question(),1000)||tooLong(row.studentAnswer(),2000)||tooLong(row.improvement(),2000)||tooLong(row.feedback(),2000))throw bad("Feedback exceeds the supported text length");
        var observation=new CreateTeacherObservationRequest(text(o.participation()),text(o.attentionLevel()),text(o.confidence()),text(o.collaboration()),text(o.learningDifficulty()),text(o.learningStyle()),skills.stream().map(String::trim).distinct().toList(),null);
        String status=row.status()==null||row.status().isBlank()?assignmentStatus:text(row.status());
        if(status==null||!Set.of("NOT_STARTED","IN_PROGRESS","COMPLETED","UNRECORDED").contains(status))status="UNRECORDED";
        return new LessonFeedbackRequest.Entry(row.studentId(),status,observation,text(row.question()),text(row.studentAnswer()),text(row.improvement()),text(row.feedback()),key,row.expectedVersion());
    }
    private LessonFeedbackRequest.Entry withStatus(LessonFeedbackRequest.Entry row,String status) {
        return new LessonFeedbackRequest.Entry(row.studentId(),status,row.observation(),row.question(),row.studentAnswer(),row.improvement(),row.feedback(),row.draftKey(),row.expectedVersion());
    }
    private String fingerprint(LessonFeedbackRequest.Entry entry) {
        try {
            // Exclude retry/version identifiers and all client timestamps from content identity.
            var content=new LessonFeedbackRequest.Entry(entry.studentId(),entry.status(),entry.observation(),entry.question(),entry.studentAnswer(),entry.improvement(),entry.feedback(),null,null);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(content).getBytes(StandardCharsets.UTF_8)));
        }catch(Exception error){throw new IllegalStateException("Could not identify feedback",error);}
    }
    private static boolean optional(String value,Set<String> supported){return value==null||value.isEmpty()||supported.contains(value);}
    private static String text(String value){return value==null?"":value.trim();}
    private static boolean tooLong(String value,int limit){return value!=null&&value.length()>limit;}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
