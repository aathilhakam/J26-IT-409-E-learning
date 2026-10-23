package lk.smartplay.adaptive.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.model.ClassroomActivity;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.dto.CreateLearningRecordRequest;
import lk.smartplay.adaptive.repository.ClassroomActivityRepository;
import lk.smartplay.adaptive.repository.LearningRecordRepository;
import lk.smartplay.adaptive.repository.StudentRepository;

@Service
public class ClassroomActivityImportService {
    public static final List<String> HEADERS = List.of("Student ID", "Student Name", "Grade", "Assessment or Worksheet Title", "Subject or Learning Area", "Assessment Type", "Score Earned", "Maximum Score", "Assessment Date", "Attempts", "Time Taken (Minutes)", "Completion Status");
    public static final List<String> COMPLETION_STATUSES = List.of("COMPLETED", "IN_PROGRESS");
    private static final int MAX_STUDENT_ROWS = 500;
    private static final long MAX_FILE_BYTES = 5 * 1024 * 1024;
    private final StudentRepository students;
    private final LearningRecordRepository records;
    private final ClassroomActivityRepository classroomActivities;
    private final StudentService studentService;
    private final byte[] tokenSecret = new byte[32];

    public ClassroomActivityImportService(StudentRepository students, LearningRecordRepository records, StudentService studentService) {
        this(students, records, null, studentService);
    }

    @Autowired
    public ClassroomActivityImportService(StudentRepository students, LearningRecordRepository records, ClassroomActivityRepository classroomActivities, StudentService studentService) {
        this.students = students;
        this.records = records;
        this.classroomActivities = classroomActivities;
        this.studentService = studentService;
        new SecureRandom().nextBytes(tokenSecret);
    }

    public record ActivityRow(int row, String studentId, String studentName, String activityName,
            Double score, Double maximumScore, Integer attempts, Double timeTakenMinutes,
            String completionStatus, String status, String message, String recordId) {}
    public record Preview(String token, List<ActivityRow> rows, long valid, long invalid, long blank, long existing) {}
    public record ImportResult(List<ActivityRow> rows, long imported, long invalid, long blank, long existing) {}

    public byte[] template(String ownerId, String grade, String activityName) {
        String defaultActivityName = activityName == null ? "" : activityName.trim();
        if (defaultActivityName.length() > 200) throw bad("Activity Name must be 200 characters or fewer.");
        List<Student> roster = students.findByOwnerId(ownerId).stream()
                .filter(Student::isActive)
                .filter(student -> grade == null || grade.isBlank() || grade.equalsIgnoreCase(student.getGrade()))
                .sorted(Comparator.comparing(Student::getGrade, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Student::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Student::getId, Comparator.nullsLast(String::compareTo)))
                .toList();
        if (roster.isEmpty() || roster.size() > MAX_STUDENT_ROWS) throw bad("Choose a grade with 1–500 active students.");
        try (InputStream template = new ClassPathResource("templates/SmartPlay_Simple_Assessment_Template.xlsx").getInputStream();
                var workbook = (XSSFWorkbook) WorkbookFactory.create(template);
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("Assessment Scores");
            if (sheet == null) throw new IOException("Assessment Scores sheet is missing from the base template.");
            var editableStyle = workbook.createCellStyle();
            editableStyle.setLocked(false);
            var header = sheet.getRow(0);
            if (header == null) throw new IOException("Assessment Scores header row is missing from the base template.");
            for (int column = 0; column < HEADERS.size(); column++) {
                var cell = header.getCell(column, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                String expected = HEADERS.get(column);
                if (column < 9 && !expected.equals(cell.getStringCellValue())) throw new IOException("Base assessment template headers have changed.");
                if (column >= 9) {
                    cell.setCellValue(expected);
                    var headerStyle = workbook.createCellStyle();
                    headerStyle.cloneStyleFrom(header.getCell(0).getCellStyle());
                    cell.setCellStyle(headerStyle);
                }
            }
            int rowNumber = 1;
            for (Student student : roster) {
                var row = sheet.createRow(rowNumber++);
                String[] values = {student.getId(), student.getName(), student.getGrade(), defaultActivityName, "", "", "", "", "", "", "", ""};
                for (int column = 0; column < values.length; column++) {
                    var cell = row.createCell(column);
                    cell.setCellValue(values[column]);
                    if (column >= 3) cell.setCellStyle(editableStyle);
                }
            }
            DataValidationHelper validation = sheet.getDataValidationHelper();
            var statusConstraint = validation.createExplicitListConstraint(COMPLETION_STATUSES.toArray(String[]::new));
            var statusValidation = validation.createValidation(statusConstraint, new org.apache.poi.ss.util.CellRangeAddressList(1, 500, 11, 11));
            statusValidation.setShowErrorBox(true);
            statusValidation.setErrorStyle(org.apache.poi.ss.usermodel.DataValidation.ErrorStyle.STOP);
            statusValidation.createErrorBox("Choose a supported status", "Select COMPLETED or IN_PROGRESS.");
            sheet.addValidationData(statusValidation);
            addDecimalValidation(sheet, validation, 6, 0, 1_000_000, 1, 500);
            addDecimalValidation(sheet, validation, 7, 0.0000001, 1_000_000, 1, 500);
            addWholeValidation(sheet, validation, 9, 0, 100_000, 1, 500);
            addDecimalValidation(sheet, validation, 10, 0, 1_000_000, 1, 500);
            var protection = sheet.getCTWorksheet().isSetSheetProtection()
                    ? sheet.getCTWorksheet().getSheetProtection() : sheet.getCTWorksheet().addNewSheetProtection();
            protection.setSheet(true);
            protection.setObjects(true);
            protection.setScenarios(true);
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, rowNumber - 1), 0, HEADERS.size() - 1));
            int[] widths = {26, 28, 16, 32, 28, 22, 16, 20, 18, 14, 28, 24};
            for (int column = 0; column < widths.length; column++) sheet.setColumnWidth(column, widths[column] * 256);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create the classroom activity template.", exception);
        }
    }

    public Preview preview(String ownerId, MultipartFile file) {
        byte[] bytes = workbookBytes(file);
        List<ActivityRow> rows = read(ownerId, bytes, false);
        String token = sign(ownerId + "|" + sha256(bytes));
        long valid = rows.stream().filter(row -> row.status().equals("VALID")).count();
        long invalid = rows.stream().filter(row -> row.status().equals("INVALID") || row.status().equals("CONFLICT")).count();
        long blank = rows.stream().filter(row -> row.status().equals("BLANK")).count();
        long existing = rows.stream().filter(row -> row.status().equals("EXISTING")).count();
        return new Preview(token, rows, valid, invalid, blank, existing);
    }

    public ImportResult importActivities(String ownerId, MultipartFile file, String token) {
        byte[] bytes = workbookBytes(file);
        if (token == null || !MessageDigest.isEqual(sign(ownerId + "|" + sha256(bytes)).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw bad("The preview is out of date. Preview this exact workbook again before importing.");
        }
        List<ActivityRow> previewRows = read(ownerId, bytes, false);
        List<ActivityRow> importedRows = new ArrayList<>();
        long imported = 0, invalid = 0, blank = 0, existing = 0;
        for (ActivityRow row : previewRows) {
            if (row.status().equals("BLANK")) { importedRows.add(row); blank++; continue; }
            if (row.status().equals("INVALID") || row.status().equals("CONFLICT")) { importedRows.add(row); invalid++; continue; }
            if (row.status().equals("EXISTING")) { importedRows.add(row); existing++; continue; }
            try {
                Student student = students.findByIdAndOwnerId(row.studentId(), ownerId)
                        .filter(Student::isActive)
                        .orElseThrow(() -> bad("Student is no longer active or is not owned by this teacher."));
                var request = new CreateLearningRecordRequest(row.activityName(), row.score(), row.maximumScore(), "",
                        row.attempts(), row.timeTakenMinutes(), row.completionStatus());
                LearningRecord validated = studentService.prepareRecord(student.getId(), request, ownerId);
                ClassroomActivity record = new ClassroomActivity(student.getId(), validated.getDomain(), validated.getScore(), validated.getMaxScore(), validated.getNotes());
                record.setId(row.recordId());
                record.activityDetails(row.attempts(), row.timeTakenMinutes(), row.completionStatus());
                record.setActivityIdentity(row.recordId(), ownerId);
                if (classroomActivities == null) records.insert(record);
                else classroomActivities.insert(record);
                importedRows.add(withStatus(row, "IMPORTED", "Activity saved.", row.recordId()));
                imported++;
            } catch (DuplicateKeyException duplicate) {
                importedRows.add(withStatus(row, "EXISTING", "This student and activity were already imported; existing data was not changed.", row.recordId()));
                existing++;
            } catch (ResponseStatusException exception) {
                importedRows.add(withStatus(row, "FAILED", exception.getReason(), row.recordId()));
                invalid++;
            } catch (RuntimeException exception) {
                importedRows.add(withStatus(row, "FAILED", "Could not save this row because of a persistence error.", row.recordId()));
                invalid++;
            }
        }
        return new ImportResult(List.copyOf(importedRows), imported, invalid, blank, existing);
    }

    private List<ActivityRow> read(String ownerId, byte[] bytes, boolean ignored) {
        Map<String, Student> authorized = new HashMap<>();
        students.findByOwnerId(ownerId).stream().filter(Student::isActive).forEach(student -> authorized.put(student.getId(), student));
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (!(workbook instanceof XSSFWorkbook)) throw bad("Upload an Excel .xlsx workbook.");
            var sheet = workbook.getSheet("Assessment Scores");
            if (sheet == null || !hasExactHeaders(sheet.getRow(0))) throw bad("Use the Assessment Scores sheet with the exact downloaded headers and order.");
            if (sheet.getLastRowNum() > MAX_STUDENT_ROWS) throw bad("A workbook can contain at most 500 activity rows.");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<ActivityRow> output = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (row == null) continue;
                int excelRow = index + 1;
                String[] values = new String[HEADERS.size()];
                Arrays.fill(values, "");
                for (int column = 0; column < values.length; column++) {
                    var cell = row.getCell(column, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell != null) values[column] = formatter.formatCellValue(cell, evaluator).trim();
                }
                if (Arrays.stream(values).allMatch(String::isBlank)) {
                    output.add(new ActivityRow(excelRow, "", "", "", null, null, null, null, "", "BLANK", "Blank row; nothing imported.", null));
                    continue;
                }
                try {
                    String studentId = values[0], studentName = values[1], activityName = values[3];
                    if (studentId.isBlank() || studentName.isBlank() || activityName.isBlank()) throw bad("Student ID, Student Name, and Activity Name are required.");
                    if (activityName.length() > 200) throw bad("Activity Name must be 200 characters or fewer.");
                    Student student = authorized.get(studentId);
                    if (student == null) throw bad("Unknown, archived, or unauthorized Student ID.");
                    if (!student.getName().equals(studentName)) throw bad("Student Name does not match this Student ID.");
                    Double score = parseFinite(values[6], "Score Earned");
                    Double maximum = parseFinite(values[7], "Maximum Score");
                    Integer attempts = parseInteger(values[9], "Attempts");
                    Double minutes = parseFinite(values[10], "Time Taken (Minutes)");
                    String status = values[11];
                    if (score == null || maximum == null || attempts == null || minutes == null || status.isBlank()) throw bad("Score, Maximum Score, Attempts, Time Taken, and Completion Status are required.");
                    if (maximum <= 0) throw bad("Maximum Score must be greater than zero.");
                    if (score < 0 || score > maximum) throw bad("Score must be between zero and Maximum Score.");
                    if (attempts < 0) throw bad("Attempts must be a nonnegative whole number.");
                    if (minutes < 0) throw bad("Time Taken must be a nonnegative number of minutes.");
                    if (!COMPLETION_STATUSES.contains(status)) throw bad("Completion Status must be COMPLETED or IN_PROGRESS.");
                    String key = identity(ownerId, studentId, activityName);
                    if (!seen.add(key)) throw bad("Duplicate student and Activity Name row in this workbook.");
                    Optional<? extends LearningRecord> existing = classroomActivities == null ? Optional.empty() : classroomActivities.findById(key);
                    if (existing.isEmpty()) existing = records.findById(key);
                    if (existing.isEmpty()) {
                        existing = records.findByStudentIdOrderByAssessedAtDesc(studentId).stream()
                                .filter(prior -> prior.getDomain() != null && prior.getDomain().trim().equalsIgnoreCase(activityName.trim()))
                                .findFirst();
                    }
                    if (existing.isPresent()) {
                        LearningRecord prior = existing.get();
                        boolean same = Double.compare(prior.getScore(), score) == 0
                                && Double.compare(prior.getMaxScore(), maximum) == 0
                                && java.util.Objects.equals(prior.getAttempts(), attempts)
                                && prior.getTimeTakenMinutes() != null && Double.compare(prior.getTimeTakenMinutes(), minutes) == 0
                                && java.util.Objects.equals(prior.getCompletionStatus(), status);
                        output.add(new ActivityRow(excelRow, studentId, studentName, activityName, score, maximum, attempts, minutes, status,
                                same ? "EXISTING" : "CONFLICT", same ? "Already imported; unchanged." : "A different result already exists for this student and activity. Nothing was overwritten.", key));
                    } else {
                        output.add(new ActivityRow(excelRow, studentId, studentName, activityName, score, maximum, attempts, minutes, status, "VALID", "Ready to import.", key));
                    }
                } catch (ResponseStatusException exception) {
                    output.add(new ActivityRow(excelRow, values[0], values[1], values[3], parseOrNull(values[6]), parseOrNull(values[7]), parseIntegerOrNull(values[9]), parseOrNull(values[10]), values[11], "INVALID", exception.getReason(), null));
                }
            }
            if (output.isEmpty()) throw bad("No activity rows found in the Classroom Activities sheet.");
            return List.copyOf(output);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw bad("Could not read this workbook. Use the downloaded SmartPlay assessment template.");
        }
    }

    public static boolean hasExactHeaders(org.apache.poi.ss.usermodel.Row row) {
        if (row == null || row.getLastCellNum() != HEADERS.size()) return false;
        for (int column = 0; column < HEADERS.size(); column++) {
            var cell = row.getCell(column);
            if (cell == null || !HEADERS.get(column).equals(cell.getStringCellValue())) return false;
        }
        return true;
    }

    private static void addDecimalValidation(org.apache.poi.ss.usermodel.Sheet sheet, DataValidationHelper helper, int column, double min, double max, int firstRow, int lastRow) {
        var constraint = helper.createDecimalConstraint(DataValidationConstraint.OperatorType.BETWEEN, Double.toString(min), Double.toString(max));
        var validation = helper.createValidation(constraint, new org.apache.poi.ss.util.CellRangeAddressList(firstRow, lastRow, column, column));
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    private static void addWholeValidation(org.apache.poi.ss.usermodel.Sheet sheet, DataValidationHelper helper, int column, int min, int max, int firstRow, int lastRow) {
        var validation = helper.createValidation(helper.createIntegerConstraint(DataValidationConstraint.OperatorType.BETWEEN, Integer.toString(min), Integer.toString(max)), new org.apache.poi.ss.util.CellRangeAddressList(firstRow, lastRow, column, column));
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    private byte[] workbookBytes(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx") || file.getSize() > MAX_FILE_BYTES) throw bad("Select a valid .xlsx workbook (maximum 5 MB).");
        try { return file.getBytes(); } catch (IOException exception) { throw bad("Could not read the uploaded workbook."); }
    }

    private static Double parseFinite(String value, String label) {
        if (value == null || value.isBlank()) return null;
        try { double parsed = Double.parseDouble(value); if (!Double.isFinite(parsed)) throw bad(label + " must be a finite number."); return parsed; }
        catch (NumberFormatException exception) { throw bad(label + " must be numeric."); }
    }
    private static Integer parseInteger(String value, String label) {
        Double parsed = parseFinite(value, label);
        if (parsed == null) return null;
        if (parsed < 0 || parsed > Integer.MAX_VALUE || parsed != Math.rint(parsed)) throw bad(label + " must be a nonnegative whole number.");
        return parsed.intValue();
    }
    private static Double parseOrNull(String value) { try { return value == null || value.isBlank() ? null : Double.valueOf(value); } catch (NumberFormatException exception) { return null; } }
    private static Integer parseIntegerOrNull(String value) { try { double d = Double.parseDouble(value); return d >= 0 && d <= Integer.MAX_VALUE && d == Math.rint(d) ? (int)d : null; } catch (Exception exception) { return null; } }
    private static String identity(String ownerId, String studentId, String activityName) {
        return "activity-" + sha256((ownerId + "\n" + studentId + "\n" + activityName.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }
    private static ActivityRow withStatus(ActivityRow row, String status, String message, String id) {
        return new ActivityRow(row.row(), row.studentId(), row.studentName(), row.activityName(), row.score(), row.maximumScore(), row.attempts(), row.timeTakenMinutes(), row.completionStatus(), status, message, id);
    }
    private String sign(String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(tokenSecret, "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("Could not sign activity import preview", exception); }
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
