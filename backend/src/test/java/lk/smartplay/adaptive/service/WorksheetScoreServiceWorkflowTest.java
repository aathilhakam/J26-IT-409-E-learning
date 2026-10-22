package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.*;
import org.springframework.data.mongodb.core.MongoTemplate;

class WorksheetScoreServiceWorkflowTest {
    private final StudentRepository students=mock(StudentRepository.class);
    private final ContentAssetRepository materials=mock(ContentAssetRepository.class);
    private final AssessmentResultRepository results=mock(AssessmentResultRepository.class);
    private final AssessmentResultService assessments=mock(AssessmentResultService.class);
    private final StudentService studentService=mock(StudentService.class);
    private final MongoTemplate mongo=mock(MongoTemplate.class);
    private WorksheetScoreService service;
    private Student ashen,ayesha,blank;
    private final String owner="teacher-1",materialId="material-1";
    private final WorksheetScoreService.Context context=new WorksheetScoreService.Context(materialId,"Fruits Worksheet","English","Grade 1",LocalDate.parse("2026-10-20"));

    @BeforeEach void setup(){
        service=new WorksheetScoreService(students,materials,results,assessments,studentService,mongo);
        ashen=student("student-ashen","Ashen Wijesinghe");ayesha=student("student-ayesha","Ayesha Niyas");blank=student("student-blank","Blank Student");
        when(students.findByOwnerId(owner)).thenReturn(List.of(ashen,ayesha,blank));
        when(results.findById(anyString())).thenReturn(Optional.empty());
    }

    private Student student(String id,String name){var student=new Student(name,"Grade 1",owner);ReflectionTestUtils.setField(student,"id",id);return student;}

    private MockMultipartFile workbook(double ashenScore,double ayeshaScore)throws Exception{
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("Worksheet Scores");var header=sheet.createRow(0);
            for(int column=0;column<WorksheetScoreService.HEADERS.size();column++)header.createCell(column).setCellValue(WorksheetScoreService.HEADERS.get(column));
            addRow(sheet.createRow(1),ashen,ashenScore," Good work ");addRow(sheet.createRow(2),ayesha,ayeshaScore,"Excellent");
            addRow(sheet.createRow(3),blank,Double.NaN,"");
            workbook.write(output);return new MockMultipartFile("file","completed-scores.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",output.toByteArray());
        }
    }

    private void addRow(org.apache.poi.ss.usermodel.Row row,Student student,double score,String feedback){
        String[] cells={student.getId(),student.getName(),student.getGrade(),materialId,context.title(),context.subject(),"",feedback};
        for(int column=0;column<cells.length;column++)if(column!=6)row.createCell(column).setCellValue(cells[column]);
        if(!Double.isNaN(score))row.createCell(6).setCellValue(score);
    }

    @Test void importUsesUploadedRowsAndSavesTheMatchingValidatedValues()throws Exception{
        var file=workbook(8.0,7.5);var preview=service.preview(owner,context,file);verifyNoInteractions(assessments);
        assertEquals(2,preview.valid());assertEquals(0,preview.invalid());assertEquals(1,preview.blank());
        var imported=service.importScores(owner,context,file,preview.token());
        assertEquals(2,imported.valid());assertEquals(0,imported.invalid());assertEquals(1,imported.blank());
        var first=imported.rows().get(0);assertEquals("student-ashen",first.studentId());assertEquals("Ashen Wijesinghe",first.studentName());assertEquals("Grade 1",first.grade());assertEquals(materialId,first.worksheetId());assertEquals("Fruits Worksheet",first.title());assertEquals("English",first.subject());assertEquals(8.0,first.score());assertEquals(" Good work ",first.feedback());assertEquals("IMPORTED",first.status());
        var second=imported.rows().get(1);assertEquals("student-ayesha",second.studentId());assertEquals(7.5,second.score());
        var blank=imported.rows().get(2);assertNull(blank.score());assertEquals("",blank.scoreValue());assertEquals("BLANK",blank.status());
        verify(assessments).createWorksheet(eq("student-ashen"),eq(owner),anyString(),eq(materialId),eq("Fruits Worksheet"),eq("English"),eq(context.date()),eq(8.0),eq(" Good work "));
        verify(assessments).createWorksheet(eq("student-ayesha"),eq(owner),anyString(),eq(materialId),eq("Fruits Worksheet"),eq("English"),eq(context.date()),eq(7.5),eq("Excellent"));
    }

    @Test void importReadsTheCurrentlyUploadedFileRatherThanAnyEarlierFile()throws Exception{
        var secondFile=workbook(4.5,6.25);var preview=service.preview(owner,context,secondFile);var result=service.importScores(owner,context,secondFile,preview.token());
        assertEquals(4.5,result.rows().get(0).score());assertEquals(6.25,result.rows().get(1).score());
        verify(assessments).createWorksheet(eq("student-ashen"),eq(owner),anyString(),eq(materialId),eq("Fruits Worksheet"),eq("English"),eq(context.date()),eq(4.5),eq(" Good work "));
    }

    @Test void malformedHeadersAndUnauthorizedStudentIdsAreRejectedWithoutSaving()throws Exception{
        var file=workbook(8.0,7.5);
        try(var source=org.apache.poi.ss.usermodel.WorkbookFactory.create(file.getInputStream());var output=new ByteArrayOutputStream()){
            source.getSheetAt(0).getRow(0).getCell(6).setCellValue("Grade");source.write(output);
            var malformed=new MockMultipartFile("file","wrong-headers.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",output.toByteArray());
            assertThrows(ResponseStatusException.class,()->service.preview(owner,context,malformed));
        }
        var unknownFile=workbook(8.0,7.5);
        try(var source=org.apache.poi.ss.usermodel.WorkbookFactory.create(unknownFile.getInputStream());var output=new ByteArrayOutputStream()){
            source.getSheetAt(0).getRow(1).getCell(0).setCellValue("some-other-teachers-student");source.write(output);
            var unknown=new MockMultipartFile("file","unknown-student.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",output.toByteArray());
            var imported=service.preview(owner,context,unknown);assertEquals("INVALID",imported.rows().get(0).status());assertTrue(imported.rows().get(0).message().contains("student ID"));
        }
    }
}
