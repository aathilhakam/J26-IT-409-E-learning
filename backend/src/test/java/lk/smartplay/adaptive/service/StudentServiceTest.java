package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.dto.CreateLearningRecordRequest;
import lk.smartplay.adaptive.dto.StudentProfileResponse;
import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.LearningRecordRepository;
import lk.smartplay.adaptive.repository.StudentRepository;

@ExtendWith(MockitoExtension.class)
class StudentServiceTest {
    private static final String OWNER_ID = "teacher-1";
    @Mock
    private StudentRepository studentRepository;

    @Mock
    private LearningRecordRepository recordRepository;

    @InjectMocks
    private StudentService studentService;

    @Test
    void profileSummarizesLearningAreasAndChangeOverTime() {
        Student student = new Student("Amaya Silva", "Grade 1", OWNER_ID);
        LearningRecord recent = new LearningRecord("student-1", "Creativity", 4, 5, "");
        LearningRecord earlier = new LearningRecord("student-1", "Creativity", 2, 5, "");
        when(studentRepository.findByIdAndOwnerId("student-1", OWNER_ID)).thenReturn(Optional.of(student));
        when(recordRepository.findByStudentIdOrderByAssessedAtDesc("student-1"))
                .thenReturn(List.of(recent, earlier));

        StudentProfileResponse profile = studentService.profile("student-1", OWNER_ID);

        assertEquals(60.0, profile.overallAverage());
        assertEquals(2, profile.assessmentCount());
        assertEquals("Creativity", profile.areas().get(0).domain());
        assertEquals(60.0, profile.areas().get(0).averagePercentage());
        assertEquals("Improving", profile.areas().get(0).progress());
    }

    @Test
    void rejectsAnAssessmentScoreAboveItsMaximum() {
        when(studentRepository.findByIdAndOwnerId("student-1", OWNER_ID))
                .thenReturn(Optional.of(new Student("Amaya", "Grade 1", OWNER_ID)));

        assertThrows(
                ResponseStatusException.class,
                () -> studentService.addRecord(
                        "student-1",
                        new CreateLearningRecordRequest("Numeracy", 6.0, 5.0, ""),
                        OWNER_ID));
    }

    @Test
    void cannotAccessAnotherTeachersStudent() {
        when(studentRepository.findByIdAndOwnerId("student-1", OWNER_ID)).thenReturn(Optional.empty());

        assertThrows(
                ResponseStatusException.class,
                () -> studentService.profile("student-1", OWNER_ID));
    }
}
