package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.*;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import static org.mockito.Mockito.*;

class WorksheetScoreServiceTest {
    @Test void templateContainsOnlyActualRosterAndWorksheetDetailsAndUnlocksSubjectScoreAndFeedback() throws Exception {
        var students=mock(StudentRepository.class);
        var first=student("stu-001","Ashen Wijesinghe","Grade 1",true);
        var second=student("stu-002","Ayesha Niyas","Grade 1",true);
        var otherGrade=student("stu-003","Other Grade","Grade 2",true);
        var archived=student("stu-004","Archived","Grade 1",false);
        when(students.findByOwnerId("teacher-1")).thenReturn(List.of(first,second,otherGrade,archived));
        var service=new WorksheetScoreService(students,mock(ContentAssetRepository.class),mock(AssessmentResultRepository.class),mock(AssessmentResultService.class),mock(StudentService.class),mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var context=new WorksheetScoreService.Context("worksheet-123","Identifying Fruits","English","Grade 1",LocalDate.parse("2026-10-20"));

        var bytes=service.template("teacher-1",context);
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
            assertEquals(1,workbook.getNumberOfSheets());
            assertEquals("Worksheet Scores",workbook.getSheetName(0));
            var sheet=workbook.getSheetAt(0);
            var header=sheet.getRow(0);
            assertEquals(8,header.getLastCellNum());
            for(int column=0;column<WorksheetScoreService.HEADERS.size();column++)assertEquals(WorksheetScoreService.HEADERS.get(column),header.getCell(column).getStringCellValue());
            assertEquals(2,sheet.getLastRowNum());
            var row=sheet.getRow(1);
            assertEquals("stu-001",row.getCell(0).getStringCellValue());
            assertEquals("Ashen Wijesinghe",row.getCell(1).getStringCellValue());
            assertEquals("Grade 1",row.getCell(2).getStringCellValue());
            assertEquals("worksheet-123",row.getCell(3).getStringCellValue());
            assertEquals("Identifying Fruits",row.getCell(4).getStringCellValue());
            assertEquals("English",row.getCell(5).getStringCellValue());
            assertEquals("",new DataFormatter().formatCellValue(row.getCell(6)));
            assertEquals("",new DataFormatter().formatCellValue(row.getCell(7)));
            for(int column=0;column<5;column++)assertTrue(row.getCell(column).getCellStyle().getLocked());
            assertFalse(row.getCell(5).getCellStyle().getLocked());
            assertFalse(row.getCell(6).getCellStyle().getLocked());
            assertFalse(row.getCell(7).getCellStyle().getLocked());
            var protection=((org.apache.poi.xssf.usermodel.XSSFSheet)sheet).getCTWorksheet().getSheetProtection();
            assertTrue(protection.getSheet());
            assertFalse(protection.isSetPassword());
        }
        var noSubjectBytes=service.template("teacher-1",new WorksheetScoreService.Context("worksheet-456","Worksheet without subject","","Grade 1",LocalDate.parse("2026-10-20")));
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(noSubjectBytes))){
            assertEquals(1,workbook.getNumberOfSheets());
            assertEquals("",new DataFormatter().formatCellValue(workbook.getSheetAt(0).getRow(1).getCell(5)));
            assertFalse(workbook.getSheetAt(0).getRow(1).getCell(6).getCellStyle().getLocked());
            assertFalse(workbook.getSheetAt(0).getRow(1).getCell(7).getCellStyle().getLocked());
        }
    }

    private static Student student(String id,String name,String grade,boolean active){
        var student=new Student(name,grade,"teacher-1");
        ReflectionTestUtils.setField(student,"id",id);
        student.setActive(active);
        return student;
    }

    @Test void acceptsOnlyTheExactEightHeadersInTheirRequiredOrder() throws Exception {
        try(var workbook=new XSSFWorkbook()){
            var sheet=workbook.createSheet();var correct=sheet.createRow(0);
            for(int column=0;column<WorksheetScoreService.HEADERS.size();column++)correct.createCell(column).setCellValue(WorksheetScoreService.HEADERS.get(column));
            assertTrue(WorksheetScoreService.hasExactHeaders(correct,WorksheetScoreService.HEADERS));
            var reordered=sheet.createRow(1);for(int column=0;column<WorksheetScoreService.HEADERS.size();column++)reordered.createCell(column).setCellValue(WorksheetScoreService.HEADERS.get((column+1)%WorksheetScoreService.HEADERS.size()));
            assertFalse(WorksheetScoreService.hasExactHeaders(reordered,WorksheetScoreService.HEADERS));
            var duplicated=sheet.createRow(2);for(int column=0;column<WorksheetScoreService.HEADERS.size();column++)duplicated.createCell(column).setCellValue(column==6?"Grade":WorksheetScoreService.HEADERS.get(column));
            assertFalse(WorksheetScoreService.hasExactHeaders(duplicated,WorksheetScoreService.HEADERS));
            var missing=sheet.createRow(3);for(int column=0;column<WorksheetScoreService.HEADERS.size()-1;column++)missing.createCell(column).setCellValue(WorksheetScoreService.HEADERS.get(column));
            assertFalse(WorksheetScoreService.hasExactHeaders(missing,WorksheetScoreService.HEADERS));
            var extra=sheet.createRow(4);for(int column=0;column<WorksheetScoreService.HEADERS.size();column++)extra.createCell(column).setCellValue(WorksheetScoreService.HEADERS.get(column));extra.createCell(8).setCellValue("Score / 10");
            assertFalse(WorksheetScoreService.hasExactHeaders(extra,WorksheetScoreService.HEADERS));
        }
    }

    @Test void readsNumericCellsWithoutApplyingExcelDisplayFormatting() throws Exception {
        try(var workbook=new XSSFWorkbook()){
            var row=workbook.createSheet().createRow(0);var score=row.createCell(6);score.setCellValue(7.5);
            var percentage=workbook.createCellStyle();percentage.setDataFormat(workbook.createDataFormat().getFormat("0%"));score.setCellStyle(percentage);
            assertEquals(7.5,WorksheetScoreService.readScore(row));
        }
    }

    @Test void keepsZeroAndBlankDistinctAndReadsNumericFormulaScores() throws Exception {
        try(var workbook=new XSSFWorkbook()){
            var sheet=workbook.createSheet();var zero=sheet.createRow(0);zero.createCell(6).setCellValue(0.0);
            var blank=sheet.createRow(1);var formula=sheet.createRow(2);formula.createCell(6).setCellFormula("8/2");
            assertEquals(0.0,WorksheetScoreService.readScore(zero));assertNull(WorksheetScoreService.readScore(blank));
            assertEquals(4.0,WorksheetScoreService.readScore(formula));
        }
    }

    @Test void leavesOutOfRangeNumericValueAvailableForRowValidationAndPreview() throws Exception {
        try(var workbook=new XSSFWorkbook()){
            var row=workbook.createSheet().createRow(0);row.createCell(6).setCellValue(11.0);
            assertEquals(11.0,WorksheetScoreService.readScore(row));
        }
    }

    @Test void retainsRawInvalidScoreTextForThePreviewAndLeavesBlankCellsEmpty() throws Exception {
        try(var workbook=new XSSFWorkbook()){
            var sheet=workbook.createSheet();var invalid=sheet.createRow(0);invalid.createCell(6).setCellValue("not a score");var blank=sheet.createRow(1);
            assertEquals("not a score",WorksheetScoreService.readScoreValue(invalid));assertEquals("",WorksheetScoreService.readScoreValue(blank));
            assertThrows(ResponseStatusException.class,()->WorksheetScoreService.readScore(invalid));assertNull(WorksheetScoreService.readScore(blank));
        }
    }
}
