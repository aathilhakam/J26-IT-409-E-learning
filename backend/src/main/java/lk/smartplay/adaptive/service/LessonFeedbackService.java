package lk.smartplay.adaptive.service;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.dto.*;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

@Service
public class LessonFeedbackService {
    private final TeacherDashboardService dashboard;
    private final TeacherObservationRepository observations;
    private final HistoricalLessonRepository history;
    private final TeacherObservationService observationService;
    private final HistoricalLessonService historyService;
    private final StudentAssignmentService assignments;
    private final MongoTemplate mongo;
    public LessonFeedbackService(TeacherDashboardService dashboard,
            TeacherObservationRepository observations, HistoricalLessonRepository history,
            TeacherObservationService observationService, HistoricalLessonService historyService,
            StudentAssignmentService assignments, MongoTemplate mongo) {
        this.dashboard=dashboard; this.observations=observations; this.history=history;
        this.observationService=observationService; this.historyService=historyService; this.assignments=assignments; this.mongo=mongo;
    }
    public record Saved(String studentId, TeacherObservation observation, HistoricalLesson history) {}
    public record Workspace(TeacherDashboardService.Lesson lesson, int weekPosition, boolean completed, List<Saved> saved) {}
    public Workspace workspace(String owner,String materialId) {
        var lesson=dashboard.lessonForOwner(owner,materialId);
        var queue=dashboard.lessons(owner,"GRADE".equals(lesson.assignmentMode())?lesson.assignmentGrade():lesson.grade());
        int position=0;
        for(int i=0;i<queue.size();i++) if(queue.get(i).id().equals(materialId)) position=i+1;
        var saved=lesson.recipients().stream().map(r -> {
            String id=TeacherObservation.lessonEvidenceId(owner,materialId,r.studentId());
            return new Saved(r.studentId(),observations.findById(id).orElse(null),history.findById(id).orElse(null));
        }).toList();
        return new Workspace(lesson,position,TeacherDashboardService.isCompleted(lesson),saved);
    }
    private record Prepared(String studentId,String status,TeacherObservation observation,HistoricalLesson history) {}
    public record Outcome(String studentId,String status,String message) {}
    public void validateSubmission(String owner,String materialId,LessonFeedbackRequest request) { prepare(owner,materialId,request); }
    private List<Prepared> prepare(String owner,String materialId,LessonFeedbackRequest request) {
        var workspace=workspace(owner,materialId);
        if (!workspace.completed() && workspace.weekPosition()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Feedback can be recorded for the current week's open lesson only");
        if(request==null || request.students()==null || request.students().isEmpty() || request.students().size()>200
                || request.students().stream().anyMatch(Objects::isNull)
                || request.students().stream().map(LessonFeedbackRequest.Entry::studentId).distinct().count()!=request.students().size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select unique students for this lesson");
        var active=workspace.lesson().recipients().stream().filter(TeacherDashboardService.Recipient::activeStudent).map(TeacherDashboardService.Recipient::studentId).collect(java.util.stream.Collectors.toSet());
        var prepared=new ArrayList<Prepared>();
        // Validate the entire batch before any writes, including ownership and all training categories.
        for(var row:request.students()) {
            if(!active.contains(row.studentId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student is not an active recipient of this lesson");
            if(row.observation()==null || row.status()==null || !Set.of("NOT_STARTED","IN_PROGRESS","COMPLETED","UNRECORDED").contains(row.status()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid observation and assignment status");
            if(tooLong(row.question(),1000)||tooLong(row.studentAnswer(),2000)||tooLong(row.improvement(),2000)||tooLong(row.feedback(),2000))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Feedback exceeds the supported text length");
            if(workspace.completed() && !"COMPLETED".equals(row.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Completed lessons cannot be reopened through feedback");
            var observation=observationService.prepareFeedback(row.studentId(),owner,row.observation());
            observation.linkLesson(materialId,text(row.question()),text(row.studentAnswer()),text(row.improvement()),text(row.feedback()));
            String id=TeacherObservation.lessonEvidenceId(owner,materialId,row.studentId());
            var prior=history.findById(id);
            int sequence=prior.map(HistoricalLesson::getSequenceNumber).orElseGet(() -> history.findByStudentIdAndOwnerIdOrderByOccurredAtDescIdDesc(row.studentId(),owner).stream().mapToInt(HistoricalLesson::getSequenceNumber).max().orElse(0)+1);
            String status=Map.of("NOT_STARTED","Not Started","IN_PROGRESS","In Progress","COMPLETED","Completed").get(row.status());
            var lessonHistory=status==null?null:historyService.prepare(row.studentId(),owner,new CreateHistoricalLessonRequest(materialId,sequence,status,null));
            if(lessonHistory!=null)lessonHistory.linkLesson(materialId);
            prepared.add(new Prepared(row.studentId(),row.status(),observation,lessonHistory));
        }
        return prepared;
    }
    public List<Outcome> save(String owner,String materialId,LessonFeedbackRequest request) {
        return save(owner,materialId,request,true);
    }
    public List<Outcome> save(String owner,String materialId,LessonFeedbackRequest request,boolean updateStatus) {
        var prepared=prepare(owner,materialId,request);
        var results=new ArrayList<Outcome>();
        for(var row:prepared) {
            try {
                // Stable IDs make a retry repair a partial save, without duplicate observations/history.
                // Submitted feedback includes all training categories; persist them for live feature aggregation.
                mongo.save(row.observation());
                if(row.history()!=null)mongo.save(row.history());
                if(updateStatus)assignments.update(materialId,row.studentId(),owner,row.status());
                results.add(new Outcome(row.studentId(),"SAVED","Observation, feedback and lesson history saved"));
            } catch(RuntimeException error) {
                results.add(new Outcome(row.studentId(),"FAILED","Could not finish saving this student's feedback. Retry safely."));
            }
        }
        return results;
    }
    private static String text(String value) { return value==null?"":value.trim(); }
    private static boolean tooLong(String value,int limit) { return value!=null && value.length()>limit; }
}
