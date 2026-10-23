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
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.model.BinaryPrototypeFeatureSnapshot;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.BinaryPrototypeFeatureSnapshotRepository;
import lk.smartplay.adaptive.repository.ContentAssetRepository;
import lk.smartplay.adaptive.repository.StudentRepository;

@Service
public class BinaryPrototypeSnapshotImportService {
    public static final List<String> FEATURES = List.of(
            "Average_Attempts", "Total_Activities", "Completion_Rate", "Historical_Lessons",
            "Completed_Historical_Lessons", "Historical_Completion_Rate");
    public static final List<String> HEADERS;
    private final StudentRepository students;
    private final BinaryPrototypeFeatureSnapshotRepository snapshots;
    private final ContentAssetRepository materials;
    private final byte[] secret = new byte[32];

    static { var headers = new ArrayList<>(List.of("Student ID", "Student Name", "Grade", "Worksheet ID")); headers.addAll(FEATURES); HEADERS = List.copyOf(headers); }
    public record Row(int row, String studentId, String studentName, String grade, String status, String message, Map<String, Object> features, String worksheetId) {
        public Row(int row, String studentId, String studentName, String status, String message) {
            this(row, studentId, studentName, "", status, message, Map.of(), "");
        }
        public Row(int row, String studentId, String studentName, String status, String message, Map<String, Object> features) {
            this(row, studentId, studentName, "", status, message, features, "");
        }
        public Row(int row, String studentId, String studentName, String grade, String status, String message, Map<String, Object> features) {
            this(row, studentId, studentName, grade, status, message, features, "");
        }
    }
    public record Preview(String token, List<Row> rows, int valid, int invalid, int blank, int existing, int sampleOnly) {}
    public record ImportResult(List<Row> rows, int imported, int invalid, int blank, int existing) {}
    private record Parsed(Row row, Student student, Map<String,Object> features, String fingerprint) {}
    private record FeatureParse(Map<String, Object> features, String error) {}

    public BinaryPrototypeSnapshotImportService(StudentRepository students, BinaryPrototypeFeatureSnapshotRepository snapshots, ContentAssetRepository materials) {
        this.students = students; this.snapshots = snapshots; this.materials = materials; new SecureRandom().nextBytes(secret);
    }

    public byte[] template(String owner, String grade, String worksheetId) {
        worksheetId = worksheetId == null ? "" : worksheetId.trim();
        if (worksheetId.isBlank()) throw bad("Select a worksheet material before downloading the experimental model template.");
        materials.findByIdAndOwnerId(worksheetId, owner).orElseThrow(() -> bad("Selected worksheet material was not found."));
        var roster = roster(owner, grade);
        if (roster.isEmpty() || roster.size() > 500) throw bad("Choose a grade with 1–500 active students.");
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            addTemplateSheets(workbook, roster, worksheetId);
            workbook.write(output); return output.toByteArray();
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create the prototype feature template.", e); }
    }

    public static void addTemplateSheets(XSSFWorkbook workbook, List<Student> roster, String worksheetId) {
            var sheet = workbook.createSheet("Prototype Features");
            var headerStyle = workbook.createCellStyle(); var font = workbook.createFont(); font.setBold(true); headerStyle.setFont(font);
            var inputStyle = workbook.createCellStyle();
            inputStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            inputStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            inputStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            inputStyle.setBottomBorderColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            var header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.size(); c++) { var cell = header.createCell(c); cell.setCellValue(HEADERS.get(c)); cell.setCellStyle(headerStyle); }
            for (int r = 0; r < roster.size(); r++) {
                Student student = roster.get(r); var row = sheet.createRow(r + 1);
                row.createCell(0).setCellValue(student.getId()); row.createCell(1).setCellValue(student.getName()); row.createCell(2).setCellValue(student.getGrade()); row.createCell(3).setCellValue(worksheetId);
                for (int c = 4; c < HEADERS.size(); c++) row.createCell(c).setCellStyle(inputStyle);
            }
            var validation = sheet.getDataValidationHelper();
            addWholeValidation(sheet, validation, 5, roster.size());
            addWholeValidation(sheet, validation, 7, roster.size());
            addWholeValidation(sheet, validation, 8, roster.size());
            addDecimalValidation(sheet, validation, 4, 0, 100000, roster.size());
            addDecimalValidation(sheet, validation, 6, 0, 100, roster.size());
            addDecimalValidation(sheet, validation, 9, 0, 100, roster.size());
            sheet.createFreezePane(0, 1); sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, roster.size(), 0, HEADERS.size() - 1));
            int[] widths = {28, 28, 16, 28, 20, 20, 20, 25, 30, 30};
            for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, widths[c] * 256);
            var instructions = workbook.createSheet("Instructions");
            instructions.createRow(0).createCell(0).setCellValue("Complete the six yellow feature cells for each student using real, available learning evidence before uploading.");
            instructions.createRow(1).createCell(0).setCellValue("The student ID, name, grade, and worksheet ID are prefilled. Adding a student does not automatically create historical feature values.");
            instructions.createRow(2).createCell(0).setCellValue("If a student has no recorded activity, do not guess or invent feature values; leave them blank and the row will remain invalid until evidence is available.");
            instructions.createRow(3).createCell(0).setCellValue("Participation, Attention_Level, Collaboration, and Learning_Difficulty are read from saved teacher observations when a prediction runs. Rates are 0–100.");
            instructions.setColumnWidth(0, 120 * 256);
    }

    public Preview preview(String owner, MultipartFile file) {
        byte[] bytes = bytes(file); var parsed = parse(owner, bytes); String token = sign(owner + "|" + sha256(bytes));
        return new Preview(token, parsed.stream().map(Parsed::row).toList(), (int)parsed.stream().filter(p -> p.row().status().equals("VALID")).count(),
                (int)parsed.stream().filter(p -> p.row().status().equals("INVALID")).count(), (int)parsed.stream().filter(p -> p.row().status().equals("BLANK")).count(),
                (int)parsed.stream().filter(p -> p.row().status().equals("EXISTING")).count(),
                (int)parsed.stream().filter(p -> p.row().status().equals("SAMPLE_ONLY")).count());
    }

    public ImportResult importFile(String owner, MultipartFile file, String token) {
        byte[] bytes = bytes(file);
        if (!verify(token, owner + "|" + sha256(bytes)))
            throw bad("The preview is out of date. Preview this exact workbook again before importing.");
        var parsed = parse(owner, bytes); var output = new ArrayList<Row>(); int imported = 0, invalid = 0, blank = 0, existing = 0;
        if (parsed.stream().anyMatch(item -> item.row().status().equals("SAMPLE_ONLY")))
            throw bad("This workbook contains synthetic DEMO-SYN sample students and is preview-only. Download a fresh template for your active class before importing real data.");
        for (Parsed item : parsed) {
            if (item.row().status().equals("BLANK")) { output.add(item.row()); blank++; }
            else if (item.row().status().equals("INVALID")) { output.add(item.row()); invalid++; }
            else if (item.row().status().equals("EXISTING")) { output.add(item.row()); existing++; }
            else {
                snapshots.save(new BinaryPrototypeFeatureSnapshot(item.student().getId(), owner, item.student().getName(), item.student().getGrade(), item.features(), item.fingerprint()));
                output.add(new Row(item.row().row(), item.student().getId(), item.student().getName(), item.student().getGrade(), "IMPORTED", "Six numeric template values saved. Four observation features are read from saved teacher feedback when prediction runs.", Map.of(), item.row().worksheetId())); imported++;
            }
        }
        return new ImportResult(List.copyOf(output), imported, invalid, blank, existing);
    }

    private List<Parsed> parse(String owner, byte[] bytes) {
        List<Student> currentRoster;
        try {
            currentRoster = roster(owner, "");
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not load the active student roster for preview. Check the database connection and retry.", exception);
        }
        var authorized = new HashMap<String,Student>(); currentRoster.forEach(s -> authorized.put(s.getId(), s));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (!(workbook instanceof XSSFWorkbook)) throw bad("Upload an Excel .xlsx workbook.");
            var sheet = workbook.getSheet("Prototype Features");
            int featureStart = 4;
            if (sheet == null) { sheet = workbook.getSheet("Worksheet Scores"); featureStart = WorksheetScoreService.HEADERS.size(); }
            if (sheet == null) throw bad("Use the downloaded Classroom Activity workbook and keep its feature headers unchanged.");
            final int featureColumnStart = featureStart;
            var header = sheet.getRow(0); var formatter = new DataFormatter(Locale.ROOT);
            boolean invalidHeaders = header == null;
            if (!invalidHeaders && featureStart == WorksheetScoreService.HEADERS.size())
                invalidHeaders = WorksheetScoreService.HEADERS.stream().anyMatch(h -> !h.equals(formatter.formatCellValue(header.getCell(WorksheetScoreService.HEADERS.indexOf(h)))));
            if (!invalidHeaders && featureStart == 4)
                invalidHeaders = List.of("Student ID", "Student Name", "Grade", "Worksheet ID").stream().anyMatch(h -> !h.equals(formatter.formatCellValue(header.getCell(List.of("Student ID", "Student Name", "Grade", "Worksheet ID").indexOf(h)))));
            if (!invalidHeaders)
                invalidHeaders = FEATURES.stream().anyMatch(h -> !h.equals(formatter.formatCellValue(header.getCell(featureColumnStart + FEATURES.indexOf(h)))));
            if (invalidHeaders) throw bad("Prototype feature headers or order changed. Download a fresh Classroom Activity template.");
            if (sheet.getLastRowNum() > 500) throw bad("A workbook can contain at most 500 student rows.");
            var seen = new HashSet<String>(); var out = new ArrayList<Parsed>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i); if (row == null) continue;
                var values = new String[(featureStart == 4 ? 4 : 3) + FEATURES.size()];
                for (int c = 0; c < 3; c++) values[c] = formatter.formatCellValue(row.getCell(c)).trim();
                if (featureStart == 4) values[3] = formatter.formatCellValue(row.getCell(3)).trim();
                for (int c = 0; c < FEATURES.size(); c++) values[c + (featureStart == 4 ? 4 : 3)] = formatter.formatCellValue(row.getCell(featureColumnStart + c)).trim();
                int rowNo = i + 1; String id = values[0];
                if (Arrays.stream(values).allMatch(String::isBlank)) { out.add(new Parsed(new Row(rowNo,"","","BLANK","Blank row skipped."),null,Map.of(),"")); continue; }
                Student student = authorized.get(id);
                if (id.matches("DEMO-SYN-\\d{3}") && values[1].matches("Demo Learner \\d{2}") && "Grade 1".equals(values[2])) {
                    FeatureParse parsedFeatures = parseFeatures(values, featureStart == 4 ? 4 : 3);
                    if (parsedFeatures.error() != null) {
                        out.add(new Parsed(new Row(rowNo, id, values[1], values[2], "INVALID", parsedFeatures.error(), parsedFeatures.features()), null, parsedFeatures.features(), ""));
                    } else {
                        out.add(new Parsed(new Row(rowNo, id, values[1], values[2], "SAMPLE_ONLY",
                                "Synthetic example only. Preview is available, but this row cannot be imported into student records.", parsedFeatures.features()),
                                null, parsedFeatures.features(), ""));
                    }
                    continue;
                }
                if (featureStart == 4 && (values[3].isBlank() || materials.findByIdAndOwnerId(values[3], owner).isEmpty())) {
                    out.add(new Parsed(new Row(rowNo, id, values[1], values[2], "INVALID",
                            "Worksheet ID is missing or does not belong to this teacher.", Map.of()), null, Map.of(), "")); continue;
                }
                if (student == null || !student.isActive() || !Objects.equals(student.getName(), values[1]) || !Objects.equals(student.getGrade(), values[2])) {
                    out.add(new Parsed(new Row(rowNo,id,values[1],"INVALID","Student ID, name, or grade does not match an active student owned by this teacher."),null,Map.of(),"")); continue;
                }
                if (!seen.add(id)) { out.add(new Parsed(new Row(rowNo,id,student.getName(),"INVALID","Student appears more than once in this workbook."),null,Map.of(),"")); continue; }
                FeatureParse featureParse = parseFeatures(values, featureStart == 4 ? 4 : 3);
                if (featureParse.error() != null) {
                    out.add(new Parsed(new Row(rowNo, id, student.getName(), student.getGrade(), "INVALID", featureParse.error(), featureParse.features(), featureStart == 4 ? values[3] : ""), null, featureParse.features(), ""));
                    continue;
                }
                try {
                    Map<String,Object> features = featureParse.features();
                    String fingerprint = sha256(id + "|" + HEADERS.subList(4, HEADERS.size()).stream().map(h -> String.valueOf(features.get(h))).reduce((a,b) -> a + "|" + b).orElse(""));
                    boolean exists = snapshots.findByStudentIdAndOwnerIdAndFingerprint(id, owner, fingerprint).isPresent();
                out.add(new Parsed(new Row(rowNo,id,student.getName(),student.getGrade(),exists ? "EXISTING" : "VALID",exists ? "This exact feature snapshot is already saved." : "All six template values are valid.", features, featureStart == 4 ? values[3] : ""),student,features,fingerprint));
                } catch (IllegalArgumentException error) { out.add(new Parsed(new Row(rowNo,id,student.getName(),"INVALID",error.getMessage()),null,Map.of(),"")); }
            }
            return List.copyOf(out);
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw bad("Could not read the workbook. Download a fresh Prototype Features template and keep its headers unchanged."); }
    }

    /** Keeps entered values visible in preview even when another required cell is missing or invalid. */
    private FeatureParse parseFeatures(String[] cells, int offset) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < FEATURES.size(); i++) {
            String name = FEATURES.get(i), raw = cells[i + offset];
            if (raw.isBlank()) { errors.add(name + " is blank"); continue; }
            double value;
            try { value = Double.parseDouble(raw); }
            catch (NumberFormatException error) { errors.add(name + " must be numeric"); continue; }
            if (!Double.isFinite(value) || value < 0) { errors.add(name + " must be a finite non-negative value"); continue; }
            if ((name.equals("Completion_Rate") || name.equals("Historical_Completion_Rate")) && value > 100) { errors.add(name + " must be between 0 and 100"); continue; }
            if (name.equals("Total_Activities") || name.equals("Historical_Lessons") || name.equals("Completed_Historical_Lessons")) {
                if (value != Math.rint(value)) { errors.add(name + " must be a whole number"); continue; }
                parsed.put(name, (long) value);
            } else parsed.put(name, value);
        }
        Object history = parsed.get("Historical_Lessons"), completed = parsed.get("Completed_Historical_Lessons");
        if (history instanceof Number h && completed instanceof Number c && c.longValue() > h.longValue())
            errors.add("Completed_Historical_Lessons cannot exceed Historical_Lessons");
        return new FeatureParse(Collections.unmodifiableMap(parsed), errors.isEmpty() ? null : String.join("; ", errors) + ".");
    }

    private static void addWholeValidation(org.apache.poi.ss.usermodel.Sheet sheet, org.apache.poi.ss.usermodel.DataValidationHelper helper, int column, int count) {
        var rule=helper.createIntegerConstraint(org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.BETWEEN,"0","100000"); var validation=helper.createValidation(rule,new org.apache.poi.ss.util.CellRangeAddressList(1,Math.max(1,count),column,column)); validation.setShowErrorBox(true); sheet.addValidationData(validation);
    }
    private static void addDecimalValidation(org.apache.poi.ss.usermodel.Sheet sheet, org.apache.poi.ss.usermodel.DataValidationHelper helper, int column, double min, double max, int count) {
        var rule=helper.createDecimalConstraint(org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.BETWEEN,Double.toString(min),Double.toString(max)); var validation=helper.createValidation(rule,new org.apache.poi.ss.util.CellRangeAddressList(1,Math.max(1,count),column,column)); validation.setShowErrorBox(true); sheet.addValidationData(validation);
    }

    private List<Student> roster(String owner, String grade) { return students.findByOwnerId(owner).stream().filter(Student::isActive).filter(s -> grade == null || grade.isBlank() || grade.equalsIgnoreCase(s.getGrade())).sorted(Comparator.comparing(Student::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList(); }
    private byte[] bytes(MultipartFile file) { try { if (file == null || file.isEmpty() || file.getSize() > 5L*1024*1024) throw bad("Upload a non-empty Excel workbook up to 5 MB."); return file.getBytes(); } catch (java.io.IOException e) { throw bad("Could not read the uploaded workbook."); } }
    private String sign(String value) { try { long expiry=Instant.now().plusSeconds(900).getEpochSecond(); String payload=expiry+"|"+value; Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256")); return expiry+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private boolean verify(String token, String value) { try { String[] parts=token.split("\\.",2); long expiry=Long.parseLong(parts[0]); if (parts.length != 2 || expiry < Instant.now().getEpochSecond()) return false; String payload=expiry+"|"+value; Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256")); String expected=Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))); return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),parts[1].getBytes(StandardCharsets.UTF_8)); } catch(Exception e) { return false; } }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private String sha256(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch(Exception e) { throw new IllegalStateException(e); } }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
