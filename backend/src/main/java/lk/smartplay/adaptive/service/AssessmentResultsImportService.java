package lk.smartplay.adaptive.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.dto.CreateAssessmentResultRequest;
import lk.smartplay.adaptive.model.AssessmentResult;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.AssessmentResultRepository;
import lk.smartplay.adaptive.repository.StudentRepository;

@Service
public class AssessmentResultsImportService {
    public static final List<String> HEADERS = List.of(
            "Student ID", "Student Name", "Grade", "Assessment Identifier", "Assessment Type",
            "Source Component", "Score", "Maximum Score", "Completion Status", "Attempts",
            "Time Taken (Minutes)", "Pre_Test", "Post_Test", "Unit_Test", "Monthly_Test", "Term_Result",
            "Motor_Skills_Score", "Creativity_Score", "Interactive_Engagement");
    private static final List<String> COMPONENTS = List.of("MOTOR_SKILLS", "CREATIVITY_THINKING", "TEACHER_ASSESSMENT", "CLASSROOM_ACTIVITY", "WORKSHEET_ASSESSMENT", "PARENT_REINFORCEMENT", "OTHER");
    private static final List<String> STATUSES = List.of("COMPLETED", "IN_PROGRESS", "NOT_COMPLETED");
    private static final int MAX_ROWS = 500;
    private static final long MAX_BYTES = 5 * 1024 * 1024;

    private final StudentRepository students;
    private final AssessmentResultRepository results;
    private final AssessmentResultService assessments;
    private final byte[] secret = new byte[32];

    public AssessmentResultsImportService(StudentRepository students, AssessmentResultRepository results, AssessmentResultService assessments) {
        this.students = students;
        this.results = results;
        this.assessments = assessments;
        new SecureRandom().nextBytes(secret);
    }

    public record RowResult(int row, String studentId, String studentName, String grade, String assessmentId,
            String assessmentType, String sourceComponent, Double score, Double maxScore, String status,
            Integer attempts, Double timeTakenMinutes, Map<String, Double> measures, String notes,
            String resultStatus, String message) {}
    public record Preview(String token, List<RowResult> rows, long valid, long invalid, long blank, long existing) {}
    public record ImportResult(List<RowResult> rows, long imported, long invalid, long blank, long existing) {}

    public byte[] template(String owner, String grade) {
        var roster = students.findByOwnerId(owner).stream().filter(Student::isActive)
                .filter(s -> clean(grade).isBlank() || clean(grade).equalsIgnoreCase(clean(s.getGrade())))
                .sorted(Comparator.comparing(Student::getGrade, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Student::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Student::getId, Comparator.nullsLast(String::compareTo))).toList();
        if (roster.isEmpty() || roster.size() > MAX_ROWS) throw bad("Choose a grade with 1–500 active students.");
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Assessment Results");
            var headerStyle = workbook.createCellStyle(); var font = workbook.createFont(); font.setBold(true); headerStyle.setFont(font);
            var header = sheet.createRow(0);
            for (int col = 0; col < HEADERS.size(); col++) { var cell = header.createCell(col); cell.setCellValue(HEADERS.get(col)); cell.setCellStyle(headerStyle); }
            var editable = workbook.createCellStyle(); editable.setLocked(false);
            int rowIndex = 1;
            for (var student : roster) {
                var row = sheet.createRow(rowIndex++);
                String[] fixed = {student.getId(), student.getName(), student.getGrade()};
                for (int col = 0; col < fixed.length; col++) row.createCell(col).setCellValue(fixed[col]);
                for (int col = 3; col < HEADERS.size(); col++) row.createCell(col).setCellStyle(editable);
            }
            var helper = sheet.getDataValidationHelper();
            addList(sheet, helper, 5, COMPONENTS, 1, MAX_ROWS);
            addList(sheet, helper, 8, STATUSES, 1, MAX_ROWS);
            for (int col : new int[]{6, 7, 10, 11, 12, 13, 14, 15, 16, 17, 18}) addDecimal(sheet, helper, col, col >= 11 ? 0 : 0, col >= 11 ? 100 : 1_000_000, 1, MAX_ROWS);
            addWhole(sheet, helper, 9, 0, Integer.MAX_VALUE, 1, MAX_ROWS);
            var protection = sheet.getCTWorksheet().addNewSheetProtection(); protection.setSheet(true); protection.setObjects(true); protection.setScenarios(true);
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, rowIndex - 1, 0, HEADERS.size() - 1));
            int[] widths = {28, 28, 16, 28, 22, 24, 16, 18, 22, 14, 24, 16, 16, 16, 18, 16, 22, 22, 25, 36};
            for (int col = 0; col < widths.length; col++) sheet.setColumnWidth(col, widths[col] * 256);
            workbook.write(output); return output.toByteArray();
        } catch (Exception e) { if (e instanceof ResponseStatusException response) throw response; throw bad("Could not create the assessment results template."); }
    }

    public Preview preview(String owner, MultipartFile file) { return read(owner, file, false, null); }
    public ImportResult importFile(String owner, MultipartFile file, String token) {
        var result = read(owner, file, true, token);
        return new ImportResult(result.rows(), result.valid(), result.invalid(), result.blank(), result.existing());
    }

    private Preview read(String owner, MultipartFile file, boolean save, String token) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw bad("Select a valid .xlsx file up to 5 MB.");
        try {
            byte[] bytes = file.getBytes(); String binding = owner + "|" + sha(bytes);
            if (save) verify(token, binding);
            var roster = new HashMap<String, Student>(); students.findByOwnerId(owner).forEach(s -> roster.put(s.getId(), s));
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                if (!(workbook instanceof XSSFWorkbook)) throw bad("Use an Excel .xlsx workbook.");
                Sheet sheet = exactSheet(workbook);
                if (sheet.getLastRowNum() > MAX_ROWS) throw bad("Use the Assessment Results sheet with at most 500 student rows.");
                var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                var identityCounts = new HashMap<String, Integer>();
                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i); if (row == null) continue;
                    String studentId = cell(row, 0, evaluator), assessmentId = cell(row, 3, evaluator);
                    if (!studentId.isBlank() && !assessmentId.isBlank()) identityCounts.merge(studentId + "\n" + assessmentId, 1, Integer::sum);
                }
                var rows = new ArrayList<RowResult>();
                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i); if (row == null) continue;
                    String[] v = new String[HEADERS.size()]; Arrays.fill(v, "");
                    try {
                        for (int col = 0; col < v.length; col++) v[col] = cell(row, col, evaluator);
                        if (Arrays.stream(v).allMatch(String::isBlank)) continue;
                        String studentId = v[0], name = v[1], grade = v[2], assessmentId = v[3], assessmentType = v[4], component = normalizeComponent(v[5]), completion = v[8].toUpperCase(Locale.ROOT), notes = "";
                        if (studentId.isBlank() || name.isBlank() || grade.isBlank()) throw bad("Student ID, Student Name, and Grade are required.");
                        Student student = roster.get(studentId);
                        if (student == null || !student.isActive() || !Objects.equals(grade, student.getGrade()) || !Objects.equals(name, student.getName())) throw bad("Student ID/name/grade does not match an active student in your classroom.");
                        if (assessmentId.isBlank() || assessmentId.length() > 180) throw bad("Assessment Identifier is required and must be at most 180 characters.");
                        if (assessmentType.length() > 120) throw bad("Assessment Type exceeds the allowed length.");
                        if (!COMPONENTS.contains(component)) throw bad("Source Component must be Teacher Assessment, Classroom Activity, Worksheet Assessment, Motor Skills, Creativity Thinking, Parent Reinforcement, or Other.");
                        if (!STATUSES.contains(completion)) throw bad("Completion Status must be COMPLETED, IN_PROGRESS, or NOT_COMPLETED.");
                        if (identityCounts.getOrDefault(studentId + "\n" + assessmentId, 0) > 1) throw bad("Duplicate student and assessment identifier in this workbook.");
                        Double score = number(v[6], "Score", false), maxScore = number(v[7], "Maximum Score", false);
                        if (score < 0 || maxScore <= 0 || score > maxScore) throw bad("Score must be between 0 and Maximum Score.");
                        Integer attempts = integer(v[9]); if (attempts != null && attempts < 0) throw bad("Attempts must be a nonnegative whole number.");
                        Double minutes = number(v[10], "Time Taken (Minutes)", true); if (minutes != null && minutes < 0) throw bad("Time Taken must be nonnegative.");
                        var measures = new LinkedHashMap<String, Double>();
                        for (int m = 0; m < LiveFeatureService.ASSESSMENT_MEASURES.size(); m++) {
                            String key = LiveFeatureService.ASSESSMENT_MEASURES.get(m); Double value = number(v[11 + m], key, true);
                            if (value != null) { if (value < 0 || value > 100) throw bad(key + " must be between 0 and 100."); measures.put(key, value); }
                        }
                        if (measures.isEmpty()) throw bad("Enter at least one measured value (0–100).");
                        String id = identity(owner, studentId, assessmentId);
                        Optional<AssessmentResult> existing = results.findById(id);
                        if (existing.isPresent()) {
                            var old = existing.get();
                            boolean same = old.getScore() == score && old.getMaxScore() == maxScore && Objects.equals(old.getMeasures(), measures) && Objects.equals(old.getAttempts(), attempts) && Objects.equals(old.getTimeTakenMinutes(), minutes) && Objects.equals(old.getCompletionStatus(), completion) && Objects.equals(old.getNotes(), notes);
                            rows.add(result(i + 1, v, score, maxScore, completion, attempts, minutes, measures, same ? "EXISTING" : "CONFLICT", same ? "Already imported; unchanged." : "A result with this student and identifier already exists; nothing was overwritten."));
                            continue;
                        }
                        String resultStatus = "VALID", message = "Ready to import.";
                        if (save) {
                            try {
                                var request = new CreateAssessmentResultRequest(component, assessmentType, assessmentId, null, score, maxScore, attempts, minutes, completion, notes, measures);
                                assessments.createImported(studentId, owner, request, id);
                                resultStatus = "IMPORTED"; message = "Assessment result saved.";
                            } catch (org.springframework.dao.DuplicateKeyException duplicate) { resultStatus = "EXISTING"; message = "Already imported; unchanged."; }
                            catch (RuntimeException failure) { resultStatus = "FAILED"; message = "Could not save this row."; }
                        }
                        rows.add(result(i + 1, v, score, maxScore, completion, attempts, minutes, measures, resultStatus, message));
                    } catch (ResponseStatusException failure) {
                        rows.add(new RowResult(i + 1, v[0], v[1], v[2], v[3], v[4], v[5], parseForPreview(v[6]), parseForPreview(v[7]), v[8], parseIntegerForPreview(v[9]), parseForPreview(v[10]), measuresForPreview(v), "", "INVALID", failure.getReason()));
                    }
                }
                return new Preview(save ? null : sign(binding), List.copyOf(rows), rows.stream().filter(r -> Set.of("VALID", "IMPORTED").contains(r.resultStatus())).count(), rows.stream().filter(r -> Set.of("INVALID", "CONFLICT", "FAILED").contains(r.resultStatus())).count(), rows.stream().filter(r -> "BLANK".equals(r.resultStatus())).count(), rows.stream().filter(r -> "EXISTING".equals(r.resultStatus())).count());
            }
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw bad("Could not read the workbook. Download a fresh Assessment Results template and keep its headers unchanged."); }
    }

    private RowResult result(int row, String[] v, Double score, Double max, String status, Integer attempts, Double minutes, Map<String, Double> measures, String resultStatus, String message) {
        return new RowResult(row, v[0], v[1], v[2], v[3], v[4], v[5], score, max, status, attempts, minutes, Map.copyOf(measures), "", resultStatus, message);
    }
    private String normalizeComponent(String raw) {
        String normalized = clean(raw).toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return switch (normalized) {
            case "TEACHER_ASSESSMENT" -> "TEACHER_ASSESSMENT";
            case "CLASSROOM_ACTIVITY", "WORKSHEET_ASSESSMENT", "OTHER" -> normalized;
            case "MOTOR_SKILLS" -> "MOTOR_SKILLS";
            case "CREATIVITY_THINKING", "CREATIVITY_AND_THINKING" -> "CREATIVITY_THINKING";
            case "PARENT_REINFORCEMENT" -> "PARENT_REINFORCEMENT";
            default -> normalized;
        };
    }
    private Double parseForPreview(String value) { try { return value == null || value.isBlank() ? null : Double.valueOf(value.trim()); } catch (RuntimeException ignored) { return null; } }
    private Integer parseIntegerForPreview(String value) { Double number = parseForPreview(value); return number != null && number >= 0 && number <= Integer.MAX_VALUE && number == Math.rint(number) ? number.intValue() : null; }
    private Map<String, Double> measuresForPreview(String[] values) {
        var measures = new LinkedHashMap<String, Double>();
        for (int index = 0; index < LiveFeatureService.ASSESSMENT_MEASURES.size(); index++) {
            Double value = parseForPreview(values[11 + index]);
            if (value != null) measures.put(LiveFeatureService.ASSESSMENT_MEASURES.get(index), value);
        }
        return measures;
    }
    private Sheet exactSheet(Workbook workbook) {
        Sheet found = null;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i); Row header = sheet.getRow(0); if (header == null) continue;
            var formatter = new DataFormatter(Locale.ROOT); boolean matches = true;
            for (int col = 0; col < HEADERS.size(); col++) if (header.getCell(col) == null || !HEADERS.get(col).equals(formatter.formatCellValue(header.getCell(col)))) { matches = false; break; }
            for (int col = HEADERS.size(); matches && col < header.getLastCellNum(); col++) {
                if (header.getCell(col) != null && !formatter.formatCellValue(header.getCell(col)).trim().isBlank()) matches = false;
            }
            if (matches) { if (found != null) throw bad("Workbook contains more than one Assessment Results sheet."); found = sheet; }
        }
        if (found == null) throw bad("Required Assessment Results columns were not found. Keep the downloaded template headers unchanged.");
        return found;
    }
    private String cell(Row row, int col, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(col); if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() == CellType.ERROR) throw bad("Excel error cells are not supported.");
        if (cell.getCellType() == CellType.FORMULA) {
            CellValue value = evaluator.evaluate(cell); if (value == null || value.getCellType() == CellType.ERROR) throw bad("Invalid formula cell.");
            return value.getCellType() == CellType.NUMERIC ? Double.toString(value.getNumberValue()) : value.getStringValue();
        }
        return new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
    }
    private Double number(String raw, String label, boolean optional) {
        if (raw.isBlank() && optional) return null;
        try { double value = Double.parseDouble(raw); if (!Double.isFinite(value)) throw bad(label + " must be a finite number."); return value; }
        catch (NumberFormatException e) { throw bad(label + " must be numeric."); }
    }
    private Integer integer(String raw) {
        if (raw.isBlank()) return null;
        double value = number(raw, "Attempts", false);
        if (value != Math.rint(value) || value > Integer.MAX_VALUE) throw bad("Attempts must be a whole number."); return (int) value;
    }
    private void addList(Sheet sheet, DataValidationHelper helper, int col, List<String> values, int first, int last) {
        var constraint = helper.createExplicitListConstraint(values.toArray(String[]::new));
        var validation = helper.createValidation(constraint, new org.apache.poi.ss.util.CellRangeAddressList(first, last, col, col)); validation.setShowErrorBox(true); sheet.addValidationData(validation);
    }
    private void addDecimal(Sheet sheet, DataValidationHelper helper, int col, double min, double max, int first, int last) {
        var constraint = helper.createDecimalConstraint(DataValidationConstraint.OperatorType.BETWEEN, Double.toString(min), Double.toString(max));
        var validation = helper.createValidation(constraint, new org.apache.poi.ss.util.CellRangeAddressList(first, last, col, col)); validation.setShowErrorBox(true); sheet.addValidationData(validation);
    }
    private void addWhole(Sheet sheet, DataValidationHelper helper, int col, int min, int max, int first, int last) {
        var constraint = helper.createIntegerConstraint(DataValidationConstraint.OperatorType.BETWEEN, Integer.toString(min), Integer.toString(max));
        var validation = helper.createValidation(constraint, new org.apache.poi.ss.util.CellRangeAddressList(first, last, col, col)); validation.setShowErrorBox(true); sheet.addValidationData(validation);
    }
    private String identity(String owner, String studentId, String assessmentId) { return sha(("assessment-result\n" + owner + "\n" + studentId + "\n" + assessmentId.trim()).getBytes(StandardCharsets.UTF_8)); }
    private String sha(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String sign(String binding) { long expires = Instant.now().plusSeconds(1800).getEpochSecond(); return expires + "." + mac(expires + "|" + binding); }
    private void verify(String token, String binding) {
        try { String[] parts = token.split("\\."); long expires = Long.parseLong(parts[0]); if (parts.length != 2 || expires < Instant.now().getEpochSecond() || !MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8), mac(expires + "|" + binding).getBytes(StandardCharsets.UTF_8))) throw bad("Preview expired or the file changed. Upload and preview the template again."); }
        catch (Exception e) { throw bad("Preview expired or the file changed. Upload and preview the template again."); }
    }
    private String mac(String value) { try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
