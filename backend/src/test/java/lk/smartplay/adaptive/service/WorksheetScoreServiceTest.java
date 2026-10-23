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
    @Test void templateOmitsSubjectAndLeavesWorksheetScoreSheetUnprotected() throws Exception {
        var students=mock(StudentRepository.class);
        var first=student("stu-001","Ashen Wijesinghe","Grade 1",true);
        var second=student("stu-002","Ayesha Niyas","Grade 1",true);
        var otherGrade=student("stu-003","Other Grade","Grade 2",true);
        var archived=student("stu-004","Archived","Grade 1",false);
        when(students.findByOwnerId("teacher-1")).thenReturn(List.of(first,second,otherGrade,archived));
        var service=new WorksheetScoreService(students,mock(ContentAssetRepository.class),mock(AssessmentResultRepository.class),mock(AssessmentResultService.class),mock(StudentService.class),mock(org.springframework.data.mongodb.core.MongoTemplate.class),mock(LearningRecommendationRepository.class));
        var context=new WorksheetScoreService.Context("worksheet-123","Identifying Fruits","English","Grade 1",LocalDate.parse("2026-10-20"));

        var bytes=service.template("teacher-1",context);
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
            assertEquals(1,workbook.getNumberOfSheets());
            assertEquals("Worksheet Scores",workbook.getSheetName(0));
            var sheet=workbook.getSheetAt(0);
            var header=sheet.getRow(0);
            assertEquals(WorksheetScoreService.HEADERS.size(),header.getLastCellNum());
            for(int column=0;column<WorksheetScoreService.HEADERS.size();column++)assertEquals(WorksheetScoreService.HEADERS.get(column),header.getCell(column).getStringCellValue());
            assertEquals(2,sheet.getLastRowNum());
            var row=sheet.getRow(1);
            assertEquals("stu-001",row.getCell(0).getStringCellValue());
            assertEquals("Ashen Wijesinghe",row.getCell(1).getStringCellValue());
            assertEquals("Grade 1",row.getCell(2).getStringCellValue());
            assertEquals("worksheet-123",row.getCell(3).getStringCellValue());
            assertEquals("Identifying Fruits",row.getCell(4).getStringCellValue());
            assertFalse(WorksheetScoreService.HEADERS.contains("Subject"));
            assertEquals("1",row.getCell(6).getStringCellValue());
            assertEquals("COMPLETED",row.getCell(7).getStringCellValue());
            assertEquals("",new DataFormatter().formatCellValue(row.getCell(5)));
            assertFalse(WorksheetScoreService.HEADERS.contains("Teacher Feedback"));
            assertTrue(((org.apache.poi.xssf.usermodel.XSSFSheet)sheet).getCTWorksheet().getSheetProtection() == null
                    || !((org.apache.poi.xssf.usermodel.XSSFSheet)sheet).getCTWorksheet().getSheetProtection().getSheet());
            assertFalse(WorksheetScoreService.HEADERS.contains("Time Taken (Minutes)"));
            for(int column=5;column<8;column++)assertFalse(row.getCell(column).getCellStyle().getLocked());
        }
        var noSubjectBytes=service.template("teacher-1",new WorksheetScoreService.Context("worksheet-456","Worksheet without subject","","Grade 1",LocalDate.parse("2026-10-20")));
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(noSubjectBytes))){
            assertEquals(1,workbook.getNumberOfSheets());
            assertEquals("",new DataFormatter().formatCellValue(workbook.getSheetAt(0).getRow(1).getCell(5)));
            assertFalse(workbook.getSheetAt(0).getRow(1).getCell(5).getCellStyle().getLocked());
            assertFalse(workbook.getSheetAt(0).getRow(1).getCell(6).getCellStyle().getLocked());
        }
    }

    private static Student student(String id,String name,String grade,boolean active){
        var student=new Student(name,grade,"teacher-1");
        ReflectionTestUtils.setField(student,"id",id);
        student.setActive(active);
        return student;
    }

    @Test void attemptsAdvanceOnlyForATeacherApprovedAssignedPersonalizedRepeatAfterACompletedResult() throws Exception {
        var students=mock(StudentRepository.class);var first=student("stu-001","Ashen Wijesinghe","Grade 1",true);
        when(students.findByOwnerId("teacher-1")).thenReturn(List.of(first));
        var results=mock(AssessmentResultRepository.class);var previous=new lk.smartplay.adaptive.model.AssessmentResult();
        previous.setStudentId(first.getId());previous.setScore(8);previous.setMaxScore(10);previous.setAttempts(2);previous.setCompletionStatus("COMPLETED");
        previous.worksheetDetails("score-old","teacher-1",first.getName(),"Identifying Fruits","English","Grade 1","worksheet-123",LocalDate.parse("2026-10-18"));
        ReflectionTestUtils.setField(previous,"recordedAt",java.time.Instant.now().minusSeconds(86400));
        when(results.findByStudentIdOrderByAssessedAtDesc(first.getId())).thenReturn(List.of(previous));
        var recommendation=new lk.smartplay.adaptive.model.LearningRecommendation(first.getId(),"English","Repeat fruits","More practice","practice gap",0.8);
        recommendation.liveDetails("lesson-1","rf-1","Needs support","PERSONALIZED",java.util.Map.of("Needs support",0.8),"assessment:score-old","request-1","worksheet-123");
        ReflectionTestUtils.setField(recommendation,"teacherOwnerId","teacher-1");recommendation.review(lk.smartplay.adaptive.model.LearningRecommendation.Status.APPROVED,null,null,"approved");recommendation.markAssigned();
        var recommendations=mock(LearningRecommendationRepository.class);when(recommendations.findByStudentIdOrderByCreatedAtDesc(first.getId())).thenReturn(List.of(recommendation));
        var service=new WorksheetScoreService(students,mock(ContentAssetRepository.class),results,mock(AssessmentResultService.class),mock(StudentService.class),mock(org.springframework.data.mongodb.core.MongoTemplate.class),recommendations);
        var context=new WorksheetScoreService.Context("worksheet-123","Identifying Fruits","English","Grade 1",LocalDate.parse("2026-10-20"));
        for(int download=0;download<2;download++)try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(service.template("teacher-1",context)))){assertEquals("3",workbook.getSheetAt(0).getRow(1).getCell(6).getStringCellValue());}
    }

    @Test void incompletePreviousResultsDoNotAdvanceAttempts() throws Exception {
        var students=mock(StudentRepository.class);var first=student("stu-001","Ashen Wijesinghe","Grade 1",true);when(students.findByOwnerId("teacher-1")).thenReturn(List.of(first));
        var results=mock(AssessmentResultRepository.class);var previous=new lk.smartplay.adaptive.model.AssessmentResult();previous.setStudentId(first.getId());previous.setScore(8);previous.setMaxScore(10);previous.setAttempts(2);previous.setCompletionStatus("IN_PROGRESS");previous.worksheetDetails("score-old","teacher-1",first.getName(),"Identifying Fruits","English","Grade 1","worksheet-123",LocalDate.parse("2026-10-18"));when(results.findByStudentIdOrderByAssessedAtDesc(first.getId())).thenReturn(List.of(previous));
        var recommendations=mock(LearningRecommendationRepository.class);var service=new WorksheetScoreService(students,mock(ContentAssetRepository.class),results,mock(AssessmentResultService.class),mock(StudentService.class),mock(org.springframework.data.mongodb.core.MongoTemplate.class),recommendations);
        var context=new WorksheetScoreService.Context("worksheet-123","Identifying Fruits","English","Grade 1",LocalDate.parse("2026-10-20"));
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(service.template("teacher-1",context)))){assertEquals("1",workbook.getSheetAt(0).getRow(1).getCell(6).getStringCellValue());}
    }

    @Test void completedResultsForAnotherWorksheetDoNotChangeTheSelectedMaterialsAttemptSequence() throws Exception {
        var students=mock(StudentRepository.class);var first=student("stu-001","Ashen Wijesinghe","Grade 1",true);when(students.findByOwnerId("teacher-1")).thenReturn(List.of(first));
        var results=mock(AssessmentResultRepository.class);var other=new lk.smartplay.adaptive.model.AssessmentResult();other.setStudentId(first.getId());other.setScore(8);other.setMaxScore(10);other.setAttempts(4);other.setCompletionStatus("COMPLETED");other.worksheetDetails("score-other","teacher-1",first.getName(),"Birds","English","Grade 1","worksheet-other",LocalDate.parse("2026-10-18"));when(results.findByStudentIdOrderByAssessedAtDesc(first.getId())).thenReturn(List.of(other));
        var service=new WorksheetScoreService(students,mock(ContentAssetRepository.class),results,mock(AssessmentResultService.class),mock(StudentService.class),mock(org.springframework.data.mongodb.core.MongoTemplate.class),mock(LearningRecommendationRepository.class));
        var context=new WorksheetScoreService.Context("worksheet-123","Identifying Fruits","English","Grade 1",LocalDate.parse("2026-10-20"));
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(service.template("teacher-1",context)))){assertEquals("1",workbook.getSheetAt(0).getRow(1).getCell(6).getStringCellValue());}
    }

    @Test void acceptsOnlyTheExactScoreHeadersInTheirRequiredOrder() throws Exception {
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
            var extra=sheet.createRow(4);for(int column=0;column<WorksheetScoreService.HEADERS.size();column++)extra.createCell(column).setCellValue(WorksheetScoreService.HEADERS.get(column));extra.createCell(8).setCellValue("Teacher Feedback");
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
