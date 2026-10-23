package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.dao.DuplicateKeyException;
import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.LearningRecordRepository;
import lk.smartplay.adaptive.repository.StudentRepository;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ClassroomActivityImportServiceTest {
    @Mock StudentRepository students;
    @Mock LearningRecordRepository records;
    private ClassroomActivityImportService service;
    private final Map<String, LearningRecord> saved = new HashMap<>();

    @BeforeEach void setUp() {
        service = new ClassroomActivityImportService(students, records, new StudentService(students, records));
        var ash = student("stu-1", "Ashen Wijesinghe", "Grade 1", "teacher-1", true);
        var ayesha = student("stu-2", "Ayesha Niyas", "Grade 1", "teacher-1", true);
        lenient().when(students.findByOwnerId("teacher-1")).thenReturn(List.of(ash, ayesha));
        lenient().when(students.findByIdAndOwnerId(anyString(), eq("teacher-1"))).thenAnswer(call ->
                List.of(ash, ayesha).stream().filter(value -> value.getId().equals(call.getArgument(0))).findFirst());
        lenient().when(records.findById(anyString())).thenAnswer(call -> java.util.Optional.ofNullable(saved.get(call.getArgument(0))));
        lenient().when(records.findByStudentIdOrderByAssessedAtDesc(anyString())).thenAnswer(call -> saved.values().stream()
                .filter(record -> record.getStudentId().equals(call.getArgument(0))).toList());
        lenient().when(records.insert(any(LearningRecord.class))).thenAnswer(call -> {
            LearningRecord record = call.getArgument(0);
            if (saved.containsKey(record.getId())) throw new DuplicateKeyException("duplicate");
            saved.put(record.getId(), record);
            return record;
        });
    }

    @Test void extendsTheExistingAssessmentTemplateWithActivityFieldsAndPrefillsTeacherRoster() throws Exception {
        byte[] bytes = service.template("teacher-1", "Grade 1", "Fruit naming");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(2, workbook.getNumberOfSheets());
            var sheet = workbook.getSheet("Assessment Scores");
            assertNotNull(sheet);
            for (int col = 0; col < ClassroomActivityImportService.HEADERS.size(); col++) {
                assertEquals(ClassroomActivityImportService.HEADERS.get(col), sheet.getRow(0).getCell(col).getStringCellValue());
            }
            assertEquals("stu-1", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Ashen Wijesinghe", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("Grade 1", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("Fruit naming", sheet.getRow(1).getCell(3).getStringCellValue());
            for (int col = 0; col < 3; col++) assertTrue(sheet.getRow(1).getCell(col).getCellStyle().getLocked());
            for (int col = 3; col < 12; col++) assertFalse(sheet.getRow(1).getCell(col).getCellStyle().getLocked());
            var protection = ((org.apache.poi.xssf.usermodel.XSSFSheet) sheet).getCTWorksheet().getSheetProtection();
            assertTrue(protection.getSheet());
            assertFalse(protection.isSetPassword());
            assertTrue(sheet.getDataValidations().stream().anyMatch(validation -> validation.getValidationConstraint().getExplicitListValues() != null));
            assertTrue(sheet.getDataValidations().size() >= 4);
        }
    }

    @Test void previewsWithoutWritingThenImportsExactValidatedActivityFields() throws Exception {
        MockMultipartFile file = workbook(row("stu-1", "Ashen Wijesinghe", "Fruit naming", "8", "10", "2", "12.5", "COMPLETED"));
        var preview = service.preview("teacher-1", file);
        assertEquals(1, preview.valid());
        assertEquals("VALID", preview.rows().get(0).status());
        verify(records, never()).insert(any(LearningRecord.class));

        var result = service.importActivities("teacher-1", file, preview.token());
        assertEquals(1, result.imported());
        LearningRecord savedRecord = saved.values().iterator().next();
        assertEquals("Fruit naming", savedRecord.getDomain());
        assertEquals(8.0, savedRecord.getScore());
        assertEquals(10.0, savedRecord.getMaxScore());
        assertEquals(2, savedRecord.getAttempts());
        assertEquals(12.5, savedRecord.getTimeTakenMinutes());
        assertEquals("COMPLETED", savedRecord.getCompletionStatus());
        assertEquals("teacher-1", savedRecord.getTeacherOwnerId());
        assertNotNull(savedRecord.getActivityId());

        var repeat = service.preview("teacher-1", file);
        assertEquals(1, repeat.existing());
        assertEquals(0, service.importActivities("teacher-1", file, repeat.token()).imported());
        assertEquals(1, saved.size());
    }

    @Test void invalidScoresAttemptsDurationsStatusesAndUnauthorizedStudentsAreRejected() throws Exception {
        var file = workbook(
                row("stu-1", "Ashen Wijesinghe", "Bad score", "11", "10", "1", "2", "COMPLETED"),
                row("stu-1", "Ashen Wijesinghe", "Bad attempts", "8", "10", "1.5", "2", "COMPLETED"),
                row("stu-1", "Ashen Wijesinghe", "Bad time", "8", "10", "1", "-1", "COMPLETED"),
                row("stu-1", "Ashen Wijesinghe", "Bad status", "8", "10", "1", "2", "DONE"),
                row("foreign", "Another Student", "Unauthorized", "8", "10", "1", "2", "COMPLETED"));
        var preview = service.preview("teacher-1", file);
        assertEquals(0, preview.valid());
        assertEquals(5, preview.invalid());
        assertTrue(preview.rows().stream().allMatch(row -> row.status().equals("INVALID")));
        assertTrue(preview.rows().get(0).message().contains("Score"));
        verify(records, never()).insert(any(LearningRecord.class));
    }

    @Test void skipsFullyBlankRowsAndDetectsDuplicateStudentActivityAndConflictingRepeat() throws Exception {
        var file = workbook(
                row("stu-1", "Ashen Wijesinghe", "Fruit naming", "8", "10", "1", "0", "COMPLETED"),
                row("stu-1", "Ashen Wijesinghe", "Fruit naming", "8", "10", "1", "0", "COMPLETED"),
                row("", "", "", "", "", "", "", ""));
        var preview = service.preview("teacher-1", file);
        assertEquals(1, preview.valid());
        assertEquals(1, preview.invalid());
        assertEquals(1, preview.blank());
        assertTrue(preview.rows().get(1).message().contains("Duplicate"));
        service.importActivities("teacher-1", file, preview.token());

        var changed = service.preview("teacher-1", workbook(row("stu-1", "Ashen Wijesinghe", "Fruit naming", "9", "10", "1", "0", "COMPLETED")));
        assertEquals(1, changed.invalid());
        assertEquals("CONFLICT", changed.rows().get(0).status());
        assertEquals(1, saved.size());
    }

    @Test void rejectsTokenForDifferentWorkbook() throws Exception {
        var preview = service.preview("teacher-1", workbook(row("stu-1", "Ashen Wijesinghe", "Activity A", "8", "10", "1", "4", "IN_PROGRESS")));
        var changed = workbook(row("stu-1", "Ashen Wijesinghe", "Activity B", "8", "10", "1", "4", "IN_PROGRESS"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.importActivities("teacher-1", changed, preview.token()));
    }

    private static Student student(String id, String name, String grade, String owner, boolean active) {
        Student value = new Student(name, grade, owner);
        ReflectionTestUtils.setField(value, "id", id);
        value.setActive(active);
        return value;
    }

    private static String[] row(String... values) { return values; }
    private static MockMultipartFile workbook(String[]... values) throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Assessment Scores");
            var header = sheet.createRow(0);
            for (int col = 0; col < ClassroomActivityImportService.HEADERS.size(); col++) header.createCell(col).setCellValue(ClassroomActivityImportService.HEADERS.get(col));
            for (int index = 0; index < values.length; index++) {
                var excelRow = sheet.createRow(index + 1);
                String[] activity = values[index];
                boolean blank = java.util.Arrays.stream(activity).allMatch(String::isBlank);
                String[] mapped = blank ? new String[ClassroomActivityImportService.HEADERS.size()]
                        : new String[] {activity[0], activity[1], "Grade 1", activity[2], "", "Classroom Activity", activity[3], activity[4], "", activity[5], activity[6], activity[7]};
                for (int col = 0; col < mapped.length; col++) excelRow.createCell(col).setCellValue(mapped[col]);
            }
            workbook.write(output);
            return new MockMultipartFile("file", "activities.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
