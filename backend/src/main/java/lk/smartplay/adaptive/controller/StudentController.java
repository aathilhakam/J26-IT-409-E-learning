
package lk.smartplay.adaptive.controller;

import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;

import jakarta.validation.Valid;

import lk.smartplay.adaptive.dto.ArchiveStudentRequest;
import lk.smartplay.adaptive.dto.CreateLearningRecordRequest;
import lk.smartplay.adaptive.dto.CreateStudentRequest;
import lk.smartplay.adaptive.dto.StudentImportResponse;
import lk.smartplay.adaptive.dto.StudentProfileResponse;

import lk.smartplay.adaptive.model.LearningRecord;
import lk.smartplay.adaptive.model.LearningRecommendation;
import lk.smartplay.adaptive.model.Student;

import lk.smartplay.adaptive.service.RecommendationService;
import lk.smartplay.adaptive.service.StudentExcelImportService;
import lk.smartplay.adaptive.service.StudentService;
import lk.smartplay.adaptive.service.TeacherAccountService;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.Authentication;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@RestController
@RequestMapping("/api")
public class StudentController {

    private final StudentService studentService;
    private final RecommendationService recommendationService;
    private final TeacherAccountService accountService;
    private final StudentExcelImportService excelImportService;

    public StudentController(
            StudentService studentService,
            RecommendationService recommendationService,
            TeacherAccountService accountService,
            StudentExcelImportService excelImportService) {

        this.studentService = studentService;
        this.recommendationService = recommendationService;
        this.accountService = accountService;
        this.excelImportService = excelImportService;
    }

    // =====================================================
    // STUDENT MANAGEMENT
    // =====================================================

    // Return active students for the authenticated teacher.
    @GetMapping("/students")
    public List<Student> getStudents(Authentication authentication) {
        return studentService.findAll(ownerId(authentication));
    }

    @GetMapping("/students/export")
    public ResponseEntity<byte[]> exportStudentDetails(Authentication authentication) {
        var roster = studentService.findAll(ownerId(authentication)).stream()
                .sorted(Comparator.comparing(Student::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        var headers = List.of("Student ID", "School ID", "Student Name", "Grade", "Classroom", "Gender",
                "Address", "Guardian Name", "Guardian Relationship", "Guardian Phone", "Added Date");
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Student Details");
            Row header = sheet.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                header.createCell(column).setCellValue(headers.get(column));
            }
            int rowIndex = 1;
            for (Student student : roster) {
                Row row = sheet.createRow(rowIndex++);
                String[] values = {student.getId(), student.getExternalStudentId(), student.getName(), student.getGrade(),
                        student.getClassroom(), student.getGender(), student.getAddress(), student.getGuardianName(),
                        student.getGuardianRelationship(), student.getGuardianPhoneNumber(),
                        student.getCreatedAt() == null ? "" : student.getCreatedAt().toString()};
                for (int column = 0; column < values.length; column++) {
                    row.createCell(column).setCellValue(values[column] == null ? "" : values[column]);
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, headers.size() - 1));
            for (int column = 0; column < headers.size(); column++) sheet.autoSizeColumn(column);
            workbook.write(output);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=SmartPlay_Student_Details.xlsx")
                    .body(output.toByteArray());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not export student details", exception);
        }
    }

    @PostMapping("/students")
    public Student createStudent(
            @Valid @RequestBody CreateStudentRequest request,
            Authentication authentication) {

        return studentService.create(
                request,
                ownerId(authentication)
        );
    }

    @GetMapping("/students/{studentId}")
    public Student getStudent(
            @PathVariable String studentId,
            Authentication authentication) {

        return studentService.findById(
                studentId,
                ownerId(authentication)
        );
    }

    // =====================================================
    // ARCHIVE STUDENT — VERIFY FULL NAME
    // =====================================================

    @PatchMapping("/students/{studentId}/archive")
    public Student archiveStudent(
            @PathVariable String studentId,
            @Valid @RequestBody ArchiveStudentRequest request,
            Authentication authentication) {

        return studentService.archiveStudent(
                studentId,
                request,
                ownerId(authentication)
        );
    }

    // =====================================================
    // VIEW ARCHIVED STUDENTS
    // =====================================================

    @GetMapping("/students/archived")
    public List<Student> getArchivedStudents(
            Authentication authentication) {

        return studentService.findArchivedStudents(
                ownerId(authentication)
        );
    }

    // =====================================================
    // RESTORE ARCHIVED STUDENT
    // =====================================================

    @PatchMapping("/students/{studentId}/restore")
    public Student restoreStudent(
            @PathVariable String studentId,
            Authentication authentication) {

        return studentService.restoreStudent(
                studentId,
                ownerId(authentication)
        );
    }

    // =====================================================
    // EXCEL TEMPLATE DOWNLOAD
    // =====================================================

    @GetMapping("/students/template")
    public ResponseEntity<byte[]> downloadStudentTemplate(Authentication authentication) {

        Resource resource = new ClassPathResource(
                "templates/student-import-template.xlsx"
        );

        if (!resource.exists() || !resource.isReadable()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Student Excel template not found. "
                    + "Check backend/src/main/resources/templates/"
                    + "student-import-template.xlsx"
            );
        }

        try (var input = resource.getInputStream();
                var workbook = new XSSFWorkbook(input);
                var output = new ByteArrayOutputStream()) {
            Sheet rosterSheet = workbook.getSheet("Current Students");
            if (rosterSheet != null) workbook.removeSheetAt(workbook.getSheetIndex(rosterSheet));
            rosterSheet = workbook.createSheet("Current Students");
            Row header = rosterSheet.createRow(0);
            header.createCell(0).setCellValue("Student ID");
            header.createCell(1).setCellValue("Student Name");
            var roster = studentService.findAll(ownerId(authentication)).stream()
                    .sorted(Comparator.comparing(Student::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .toList();
            int rowIndex = 1;
            for (Student student : roster) {
                Row row = rosterSheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(student.getId() == null ? "" : student.getId());
                row.createCell(1).setCellValue(student.getName() == null ? "" : student.getName());
            }
            rosterSheet.createFreezePane(0, 1);
            rosterSheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, 1));
            rosterSheet.autoSizeColumn(0);
            rosterSheet.autoSizeColumn(1);
            workbook.write(output);
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument."
                        + "spreadsheetml.sheet"
                ))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                        + "SmartPlay_Student_Profile_Import_Template.xlsx\""
                )
                .body(output.toByteArray());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare the student template", exception);
        }
    }

    // =====================================================
    // EXCEL BULK STUDENT IMPORT
    // =====================================================

    @PostMapping(
            value = "/students/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public StudentImportResponse importStudents(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {

        if (file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Please select an Excel file to upload."
            );
        }

        String filename = file.getOriginalFilename();

        if (filename == null
                || !filename.toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".xlsx")) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only .xlsx Excel files are supported."
            );
        }

        return excelImportService.importWorkbook(
                file,
                ownerId(authentication)
        );
    }

    // =====================================================
    // STUDENT PROFILE
    // =====================================================

    @GetMapping("/students/{studentId}/profile")
    public StudentProfileResponse getProfile(
            @PathVariable String studentId,
            Authentication authentication) {

        return studentService.profile(
                studentId,
                ownerId(authentication)
        );
    }

    // =====================================================
    // STUDENT LEARNING RECORDS
    // =====================================================

    @GetMapping("/students/{studentId}/records")
    public List<LearningRecord> getRecords(
            @PathVariable String studentId,
            Authentication authentication) {

        return studentService.findRecords(
                studentId,
                ownerId(authentication)
        );
    }

    @PostMapping("/students/{studentId}/records")
    public LearningRecord createRecord(
            @PathVariable String studentId,
            @Valid @RequestBody CreateLearningRecordRequest request,
            Authentication authentication) {

        return studentService.addRecord(
                studentId,
                request,
                ownerId(authentication)
        );
    }

    // =====================================================
    // LEARNING RECOMMENDATIONS
    // =====================================================

    @GetMapping("/students/{studentId}/recommendations")
    public List<LearningRecommendation> getRecommendations(
            @PathVariable String studentId,
            Authentication authentication) {

        return recommendationService.findForStudent(
                studentId,
                ownerId(authentication)
        );
    }

    @PostMapping("/students/{studentId}/recommendations")
    public LearningRecommendation generateRecommendation(
            @PathVariable String studentId,
            Authentication authentication) {

        return recommendationService.generate(
                studentId,
                ownerId(authentication)
        );
    }

    // =====================================================
    // AUTHENTICATED TEACHER OWNERSHIP
    // =====================================================

    private String ownerId(Authentication authentication) {
        return accountService.accountIdForEmail(
                authentication.getName()
        );
    }
}
