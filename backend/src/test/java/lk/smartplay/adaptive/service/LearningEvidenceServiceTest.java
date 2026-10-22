package lk.smartplay.adaptive.service;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.dto.*;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

@ExtendWith(MockitoExtension.class)
class LearningEvidenceServiceTest {
    @Mock StudentService students;
    @Mock TeacherObservationRepository observationRepository;
    @Mock HistoricalLessonRepository historyRepository;
    TeacherObservationService observations; HistoricalLessonService history;
    @BeforeEach void setup() {
        observations = new TeacherObservationService(students, observationRepository); history = new HistoricalLessonService(students, historyRepository);
        when(students.findById("s1", "owner")).thenReturn(new Student("Test", "Grade 1", "owner"));
    }
    CreateTeacherObservationRequest request(String participation) { return new CreateTeacherObservationRequest(participation,"Medium","High","Fair","None identified","Visual learner",List.of("Sound awareness"),null); }
    @Test void createUsesOwnerAndStudentAndCanonicalCategories() {
        when(observationRepository.save(any())).thenAnswer(call -> call.getArgument(0)); when(historyRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        var o = observations.create("s1","owner",request("Low")); assertEquals("owner",o.getOwnerId()); assertEquals("s1",o.getStudentId()); assertNotNull(o.getObservedAt());
        var h = history.create("s1","owner",new CreateHistoricalLessonRequest("LES001",1,"Completed",null)); assertEquals("Completed",h.getCompletionStatus()); assertEquals(h.getOccurredAt(),h.getCompletedAt());
        observations.findForStudent("s1","owner"); history.findForStudent("s1","owner");
        verify(observationRepository).findByStudentIdAndOwnerIdOrderByObservedAtDescRecordedAtDescIdDesc("s1","owner"); verify(historyRepository).findByStudentIdAndOwnerIdOrderByOccurredAtDescIdDesc("s1","owner");
    }
    @Test void invalidCategoriesSequenceAndFutureTimesDoNotWrite() {
        assertThrows(ResponseStatusException.class, () -> observations.create("s1","owner",request("Unknown")));
        assertThrows(ResponseStatusException.class, () -> history.create("s1","owner",new CreateHistoricalLessonRequest("LES001",0,"Completed",null)));
        assertThrows(ResponseStatusException.class, () -> history.create("s1","owner",new CreateHistoricalLessonRequest("LES001",1,"COMPLETED",null)));
        assertThrows(ResponseStatusException.class, () -> history.create("s1","owner",new CreateHistoricalLessonRequest("LES001",1,"Completed",Instant.now().plusSeconds(3600))));
        verifyNoInteractions(observationRepository,historyRepository);
    }
    @Test void ownershipAndArchiveRestrictionsPrecedeWrites() {
        when(students.findById("s1","owner")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThrows(ResponseStatusException.class, () -> observations.create("s1","owner",request("Low")));
        assertThrows(ResponseStatusException.class, () -> observations.findForStudent("s1","owner"));
        assertThrows(ResponseStatusException.class, () -> history.findForStudent("s1","owner"));
        var archived = new Student("Test","Grade 1","owner"); archived.setActive(false); doReturn(archived).when(students).findById("s1","owner");
        assertThrows(ResponseStatusException.class, () -> history.create("s1","owner",new CreateHistoricalLessonRequest("LES001",1,"Completed",null)));
        verifyNoInteractions(observationRepository,historyRepository);
    }
}
