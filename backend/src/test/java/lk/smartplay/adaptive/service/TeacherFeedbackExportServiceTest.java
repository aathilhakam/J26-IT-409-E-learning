package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import lk.smartplay.adaptive.dto.CreateTeacherObservationRequest;
import lk.smartplay.adaptive.model.LessonFeedback;
import lk.smartplay.adaptive.repository.LessonFeedbackRepository;

class TeacherFeedbackExportServiceTest {
    @Test void exportsOneLessonScopedRowPerAssignedStudentWithSavedFieldsAndBlanks() throws Exception {
        var lessons=mock(LessonFeedbackService.class);var repository=mock(LessonFeedbackRepository.class);
        var recipients=List.of(new TeacherDashboardService.Recipient("s1","Asha","Grade 1",true,"COMPLETED",null,null),new TeacherDashboardService.Recipient("s2","Banu","Grade 1",true,"NOT_STARTED",null,null),new TeacherDashboardService.Recipient("s1","Asha","Grade 1",true,"COMPLETED",null,null));
        var lesson=new TeacherDashboardService.Lesson("lesson-a","Song Book – Grade 1","","Music","Grade 1","Song Book","STUDENTS","",null,null,3,1,0,2,0,null,recipients,"song.pdf",null);
        when(lessons.workspace("teacher-a","lesson-a")).thenReturn(new LessonFeedbackService.Workspace(lesson,1,false,List.of()));
        var observation=new CreateTeacherObservationRequest("Often","High","Growing","Cooperates","None","Visual",List.of(),null);
        var record=new LessonFeedback("teacher-a","lesson-a","Song Book – Grade 1","s1","Asha","Grade 1","rev-2");
        record.update(new lk.smartplay.adaptive.dto.LessonFeedbackRequest.Entry("s1","COMPLETED",observation,"Q","A","Improved","Practice next", "rev-2",null),"fingerprint");record.submit();
        when(repository.findByOwnerIdAndMaterialIdAndSubmissionStatusOrderBySubmittedAtDescUpdatedAtDesc("teacher-a","lesson-a","SUBMITTED")).thenReturn(List.of(record,record));
        var result=new TeacherFeedbackExportService(lessons,repository).export("teacher-a","lesson-a");
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(result.bytes()))) {
            var sheet=workbook.getSheetAt(0);assertEquals(3,sheet.getPhysicalNumberOfRows());
            var header=sheet.getRow(0);assertEquals(17,header.getLastCellNum());assertEquals("Participation",header.getCell(5).getStringCellValue());assertEquals("Learning Style",header.getCell(10).getStringCellValue());assertEquals("Improvement Observed",header.getCell(11).getStringCellValue());assertEquals("Feedback / Next Step",header.getCell(12).getStringCellValue());
            var submitted=sheet.getRow(1);assertEquals("s1",submitted.getCell(0).getStringCellValue());assertEquals("Often",submitted.getCell(5).getStringCellValue());assertEquals("High",submitted.getCell(6).getStringCellValue());assertEquals("Growing",submitted.getCell(7).getStringCellValue());assertEquals("Cooperates",submitted.getCell(8).getStringCellValue());assertEquals("None",submitted.getCell(9).getStringCellValue());assertEquals("Visual",submitted.getCell(10).getStringCellValue());assertEquals("Improved",submitted.getCell(11).getStringCellValue());assertEquals("Practice next",submitted.getCell(12).getStringCellValue());assertEquals("Submitted",submitted.getCell(13).getStringCellValue());assertEquals("teacher-a",submitted.getCell(16).getStringCellValue());
            var noFeedback=sheet.getRow(2);assertEquals("s2",noFeedback.getCell(0).getStringCellValue());assertEquals("No Feedback",noFeedback.getCell(13).getStringCellValue());assertNull(noFeedback.getCell(5));assertNull(noFeedback.getCell(14));
            assertEquals("A1:Q3",sheet.getCTWorksheet().getAutoFilter().getRef());assertEquals(1,sheet.getPaneInformation().getHorizontalSplitPosition());assertTrue(sheet.getColumnWidth(12)>sheet.getColumnWidth(0));
        }
        assertEquals(1,result.submittedCount());assertTrue(result.filename().endsWith("_Teacher_Feedback.xlsx"));
    }
    @Test void checksLessonAuthorizationBeforeReadingAnyFeedback() {
        var lessons=mock(LessonFeedbackService.class);var repository=mock(LessonFeedbackRepository.class);
        when(lessons.workspace("teacher-b","lesson-a")).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new TeacherFeedbackExportService(lessons,repository).export("teacher-b","lesson-a"));
        verifyNoInteractions(repository);
    }
    @Test void emptyRosterProducesAValidHeaderOnlyWorkbook() throws Exception {
        var lessons=mock(LessonFeedbackService.class);var repository=mock(LessonFeedbackRepository.class);
        var lesson=new TeacherDashboardService.Lesson("empty","Empty class","","","Grade 1","Book","STUDENTS","",null,null,0,0,0,0,0,null,List.of(),"",null);
        when(lessons.workspace("owner","empty")).thenReturn(new LessonFeedbackService.Workspace(lesson,1,false,List.of()));
        when(repository.findByOwnerIdAndMaterialIdAndSubmissionStatusOrderBySubmittedAtDescUpdatedAtDesc("owner","empty","SUBMITTED")).thenReturn(List.of());
        var result=new TeacherFeedbackExportService(lessons,repository).export("owner","empty");
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(result.bytes()))) { assertEquals(1,workbook.getSheetAt(0).getPhysicalNumberOfRows());assertEquals(17,workbook.getSheetAt(0).getRow(0).getLastCellNum()); }
        assertEquals(0,result.submittedCount());
    }
}
