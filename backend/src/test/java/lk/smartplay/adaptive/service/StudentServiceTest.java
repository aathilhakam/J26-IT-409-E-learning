package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

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
import lk.smartplay.adaptive.model.CreativityAssessment;
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

    private void givenStudent() {
        when(studentRepository.findByIdAndOwnerId("student-1", OWNER_ID))
                .thenReturn(Optional.of(new Student("Amaya", "Grade 1", OWNER_ID)));
    }

    private CreativityAssessment assessment(Map<String, Double> scores, int scale) {
        return new CreativityAssessment(scores, scale, "Creative Coloring", "Treehouse",
                Map.of("scores", Map.of("originality", 2.3), "score_scale", 5), null, null, null, false);
    }

    @Test
    void storesAndRetrievesTeacherRubricSeparatelyFromModelScores() {
        givenStudent();
        var scores = Map.of("originality", 10.0, "imagination", 1.0, "elaboration", 6.0, "storytelling", 8.0);
        var observation = assessment(scores, 10);
        when(recordRepository.save(any(LearningRecord.class))).thenAnswer(call -> call.getArgument(0));
        var saved = studentService.addRecord("student-1",
                new CreateLearningRecordRequest("Creativity", 6.25, 10.0, "Teacher notes", observation), OWNER_ID);
        when(recordRepository.findByStudentIdOrderByAssessedAtDesc("student-1")).thenReturn(List.of(saved));
        var reloaded = studentService.findRecords("student-1", OWNER_ID).get(0);
        assertEquals(scores, reloaded.getCreativityAssessment().teacherScores());
        assertEquals(10, reloaded.getCreativityAssessment().teacherScoreScale());
        assertEquals(observation.imageAnalysis(), reloaded.getCreativityAssessment().imageAnalysis());
        assertEquals(62.5, StudentService.percentage(reloaded));
    }

    @Test
    void rejectsFractionalOutOfRangeNullAndIncompleteTeacherScores() {
        givenStudent();
        for (Double invalid : new Double[] { 0.0, 11.0, 3.5, Double.NaN, Double.POSITIVE_INFINITY, null }) {
            var scores = new HashMap<>(Map.of("originality", 10.0, "imagination", 1.0, "elaboration", 6.0, "storytelling", 8.0));
            scores.put("originality", invalid);
            assertThrows(ResponseStatusException.class, () -> studentService.addRecord("student-1",
                    new CreateLearningRecordRequest("Creativity", 6.25, 10.0, "", assessment(scores, 10)), OWNER_ID));
        }
        assertThrows(ResponseStatusException.class, () -> studentService.addRecord("student-1",
                new CreateLearningRecordRequest("Creativity", 6.25, 10.0, "", assessment(Map.of("originality", 10.0), 10)), OWNER_ID));
    }

    @Test
    void rejectsWrongTeacherScaleOrAverage() {
        givenStudent();
        var scores = Map.of("originality", 10.0, "imagination", 1.0, "elaboration", 6.0, "storytelling", 8.0);
        assertThrows(ResponseStatusException.class, () -> studentService.addRecord("student-1",
                new CreateLearningRecordRequest("Creativity", 6.25, 10.0, "", assessment(scores, 5)), OWNER_ID));
        assertThrows(ResponseStatusException.class, () -> studentService.addRecord("student-1",
                new CreateLearningRecordRequest("Creativity", 5.0, 10.0, "", assessment(scores, 10)), OWNER_ID));
    }

    @Test
    void historicalFivePointRecordsRemainUnmodifiedInMixedScaleProfiles() {
        givenStudent();
        var old = new LearningRecord("student-1", "Creativity", 4.0, 5.0, "Original historical notes");
        var recent = new LearningRecord("student-1", "Creativity", 6.0, 10.0, "New observation");
        when(recordRepository.findByStudentIdOrderByAssessedAtDesc("student-1")).thenReturn(List.of(recent, old));
        assertEquals(70.0, studentService.profile("student-1", OWNER_ID).overallAverage());
        assertEquals(5.0, old.getMaxScore());
        assertEquals(4.0, old.getScore());
        assertNull(old.getCreativityAssessment());
    }
}
