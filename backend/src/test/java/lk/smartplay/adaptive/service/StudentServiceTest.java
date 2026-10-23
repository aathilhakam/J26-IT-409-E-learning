package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
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
import lk.smartplay.adaptive.dto.ArchiveStudentRequest;
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
        org.springframework.test.util.ReflectionTestUtils.setField(recent, "assessedAt", java.time.Instant.parse("2026-10-21T00:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(earlier, "assessedAt", java.time.Instant.parse("2026-10-20T00:00:00Z"));
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

    @Test
    void archivesStudentWhenTeacherConfirmsFullName() {
        Student student = new Student("Amaya Silva", "Grade 1", OWNER_ID);
        when(studentRepository.findByIdAndOwnerId("student-1", OWNER_ID))
                .thenReturn(Optional.of(student));
        when(studentRepository.save(student)).thenReturn(student);

        Student archived = studentService.archiveStudent(
                "student-1",
                new ArchiveStudentRequest("  Amaya   Silva "),
                OWNER_ID);

        assertFalse(archived.isActive());
        verify(studentRepository).save(student);
    }

    @Test
    void rejectsArchiveWhenConfirmedFullNameDoesNotMatch() {
        when(studentRepository.findByIdAndOwnerId("student-1", OWNER_ID))
                .thenReturn(Optional.of(new Student("Amaya Silva", "Grade 1", OWNER_ID)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> studentService.archiveStudent(
                        "student-1",
                        new ArchiveStudentRequest("Amaya Perera"),
                        OWNER_ID));

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void createsLearningRecordWithItsStudentAndOriginalScoreModel() {
        when(studentRepository.findByIdAndOwnerId("student-1", OWNER_ID))
                .thenReturn(Optional.of(new Student("Amaya", "Grade 1", OWNER_ID)));
        when(recordRepository.save(org.mockito.ArgumentMatchers.any(LearningRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LearningRecord record = studentService.addRecord("student-1",
                new CreateLearningRecordRequest(" Numeracy ", 4.0, 5.0, " Good progress "), OWNER_ID);
        assertEquals("student-1", record.getStudentId());
        assertEquals("Numeracy", record.getDomain());
        assertEquals(4.0, record.getScore());
        assertEquals(5.0, record.getMaxScore());
        assertEquals("Good progress", record.getNotes());
        verify(recordRepository).save(record);
    }
}
