package lk.smartplay.adaptive.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import lk.smartplay.adaptive.dto.CreateStudentRequest;
import lk.smartplay.adaptive.dto.StudentImportResponse;
import lk.smartplay.adaptive.model.Student;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudentExcelImportService {
    private static final String[] HEADERS = {
        "Student Index Number", "Student Name", "Grade", "Class", "Gender", "Address",
        "Guardian Name", "Guardian Relationship", "Guardian Phone Number"
    };
    private static final int MAX_ROWS = 500;
    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private final StudentService studentService;
    private final MongoTemplate mongoTemplate;

    public StudentExcelImportService(StudentService studentService, MongoTemplate mongoTemplate) {
        this.studentService = studentService;
        this.mongoTemplate = mongoTemplate;
    }

    public StudentImportResponse importWorkbook(MultipartFile file, String ownerId) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null ||
                !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx") ||
                file.getSize() > MAX_BYTES) {
            throw bad("Select a valid .xlsx workbook (maximum 5 MB).");
        }

        List<ImportRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Student student : studentService.findAll(ownerId)) {
            if (student.getExternalStudentId() != null) {
                seen.add(student.getExternalStudentId().trim().toLowerCase(Locale.ROOT));
            }
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheet("Students");
            if (sheet == null) throw bad("The workbook must contain a sheet named Students.");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Row header = sheet.getRow(0);
            if (header == null) throw bad("The Students sheet has no header row.");
            for (int col = 0; col < HEADERS.length; col++) {
                if (!HEADERS[col].equals(cell(header, col, formatter, evaluator))) {
                    throw bad("Incorrect Excel headers. Please use the downloaded SmartPlay template.");
                }
            }

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String[] values = new String[HEADERS.length];
                boolean hasDetails = false;
                for (int col = 0; col < values.length; col++) {
                    values[col] = cell(row, col, formatter, evaluator);
                    if (col != 2 && !values[col].isBlank()) hasDetails = true;
                }
                // The template pre-fills Grade 1 on otherwise empty rows.
                if (!hasDetails) continue;
                if (rows.size() >= MAX_ROWS) throw bad("A workbook can contain at most 500 students.");
                int excelRow = i + 1;
                if (values[0].isBlank() || values[1].isBlank()) {
                    throw bad("Row " + excelRow + ": Student Index Number and Student Name are required.");
                }
                String grade = values[2].isBlank() ? "Grade 1" : values[2];
                if (!"Grade 1".equalsIgnoreCase(grade)) {
                    throw bad("Row " + excelRow + ": this portal currently supports Grade 1 only.");
                }
                String key = values[0].toLowerCase(Locale.ROOT);
                if (!seen.add(key)) throw bad("Row " + excelRow + ": duplicate Student Index Number " + values[0]);
                if (!values[8].isBlank() && !values[8].matches("[+0-9()\\s-]{7,20}")) {
                    throw bad("Row " + excelRow + ": invalid guardian phone number.");
                }
                rows.add(new ImportRow(values[0], values[1], "Grade 1", values[3], values[4], values[5], values[6], values[7], values[8]));
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw bad("Could not read the workbook. Use the SmartPlay .xlsx template.");
        }
        if (rows.isEmpty()) throw bad("No students found. Fill in the Students sheet and try again.");

        // Validate all workbook rows before creating any student records.
        // MongoDB writes are not transactional here: unexpected write failures may partially import.
        for (ImportRow row : rows) {
            Student student = studentService.create(
                new CreateStudentRequest(row.name(), row.grade(), row.index()), ownerId);
            student.setClassroom(row.classroom());
            student.setGender(row.gender());
            student.setAddress(row.address());
            student.setGuardianName(row.guardianName());
            student.setGuardianRelationship(row.guardianRelationship());
            student.setGuardianPhoneNumber(row.guardianPhone());
            // Preserve the ownership assigned by StudentService.create when updating additional fields.
            mongoTemplate.save(student);
        }
        return new StudentImportResponse(rows.size());
    }

    private String cell(Row row, int index, DataFormatter formatter, FormulaEvaluator evaluator) {
        Cell value = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return value == null ? "" : formatter.formatCellValue(value, evaluator).trim();
    }
    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private record ImportRow(String index, String name, String grade, String classroom,
        String gender, String address, String guardianName, String guardianRelationship, String guardianPhone) {}
}
