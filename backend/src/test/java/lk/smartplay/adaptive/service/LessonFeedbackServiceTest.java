package lk.smartplay.adaptive.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.dto.*;
import lk.smartplay.adaptive.repository.*;
class LessonFeedbackServiceTest {
    @Test void partialSaveReportsAffectedStudentAndRetryReusesBothEvidenceIdentities() {
        var dashboard=mock(TeacherDashboardService.class);
        var observations=mock(TeacherObservationRepository.class);var history=mock(HistoricalLessonRepository.class);
        var observationService=mock(TeacherObservationService.class);var historyService=mock(HistoricalLessonService.class);
        var assignments=mock(StudentAssignmentService.class);var mongo=mock(MongoTemplate.class);
        var service=new LessonFeedbackService(dashboard,observations,history,observationService,historyService,assignments,mongo);
        var lesson=new TeacherDashboardService.Lesson("m","Birds","","","Grade 1","Learning Book","STUDENTS","",null,null,2,0,0,2,0,null,List.of(
            new TeacherDashboardService.Recipient("s1","First","Grade 1",true,"NOT_STARTED",null,null),
            new TeacherDashboardService.Recipient("s2","Second","Grade 1",true,"NOT_STARTED",null,null)),"birds.pdf",null);
        when(dashboard.lessonForOwner("owner","m")).thenReturn(lesson);when(dashboard.lessons("owner","Grade 1")).thenReturn(List.of(lesson));
        when(observationService.prepareFeedback(anyString(),eq("owner"),any())).thenAnswer(call -> new TeacherObservation(call.getArgument(0),"owner","High","Medium","High","Good","None identified","Visual learner",List.of(),java.time.Instant.now()));
        when(historyService.prepare(anyString(),eq("owner"),any())).thenAnswer(call -> new HistoricalLesson(call.getArgument(0),"owner","m",1,"Completed",java.time.Instant.now()));
        when(mongo.save(any(HistoricalLesson.class))).thenAnswer(call -> { HistoricalLesson row=call.getArgument(0);if(row.getStudentId().equals("s2"))throw new IllegalStateException("private internal failure");return row; });
        var observation=new CreateTeacherObservationRequest("High","Medium","High","Good","None identified","Visual learner",List.of(),null);
        var request=new LessonFeedbackRequest(List.of(new LessonFeedbackRequest.Entry("s1","COMPLETED",observation,"Q","A","Improved","Practice"),new LessonFeedbackRequest.Entry("s2","COMPLETED",observation,"Q","B","Improved","Practice")));
        var result=service.save("owner","m",request);assertEquals("SAVED",result.get(0).status());assertEquals("FAILED",result.get(1).status());assertFalse(result.get(1).message().contains("private internal"));
        verify(assignments,never()).update("m","s2","owner","COMPLETED");
        when(mongo.save(any(HistoricalLesson.class))).thenAnswer(call -> call.getArgument(0));
        assertTrue(service.save("owner","m",request).stream().allMatch(row -> row.status().equals("SAVED")));
        var capture=org.mockito.ArgumentCaptor.forClass(TeacherObservation.class);verify(mongo,times(4)).save(capture.capture());
        var rows=capture.getAllValues();assertEquals(rows.get(0).getId(),rows.get(2).getId());assertEquals(rows.get(1).getId(),rows.get(3).getId());assertNotEquals(rows.get(0).getId(),rows.get(1).getId());
    }
}
