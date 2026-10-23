package lk.smartplay.adaptive.service;

import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import lk.smartplay.adaptive.model.LessonFeedback;
import lk.smartplay.adaptive.repository.LessonFeedbackRepository;

@Service
public class TeacherFeedbackExportService {
    private static final String[] HEADERS = {"Student ID", "Student Name", "Grade", "Lesson ID", "Lesson Name",
        "Participation", "Attention Level", "Confidence", "Collaboration", "Learning Difficulty", "Learning Style",
        "Improvement Observed", "Feedback / Next Step", "Feedback Status", "Feedback Submission Date and Time",
        "Last Updated Date and Time", "Teacher ID"};
    private final LessonFeedbackService lessons;
    private final LessonFeedbackRepository feedback;
    public TeacherFeedbackExportService(LessonFeedbackService lessons, LessonFeedbackRepository feedback) { this.lessons=lessons;this.feedback=feedback; }
    public record Export(byte[] bytes,int submittedCount,String filename) {}

    public Export export(String owner,String materialId) {
        var workspace=lessons.workspace(owner,materialId);
        var latest=new LinkedHashMap<String,LessonFeedback>();
        feedback.findByOwnerIdAndMaterialIdAndSubmissionStatusOrderBySubmittedAtDescUpdatedAtDesc(owner,materialId,"SUBMITTED")
            .forEach(row -> latest.putIfAbsent(row.getStudentId(),row));
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("Teacher Feedback");
            var header=sheet.createRow(0);var headerStyle=book.createCellStyle();var font=book.createFont();font.setBold(true);font.setColor(IndexedColors.WHITE.getIndex());headerStyle.setFont(font);headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);headerStyle.setWrapText(true);
            for(int c=0;c<HEADERS.length;c++){var cell=header.createCell(c);cell.setCellValue(HEADERS[c]);cell.setCellStyle(headerStyle);}
            int rowIndex=1,submittedCount=0;var seen=new HashSet<String>();
            for(var student:workspace.lesson().recipients()) {
                if(!seen.add(student.studentId()))continue;
                var record=latest.get(student.studentId());var row=sheet.createRow(rowIndex++);
                if(record!=null)submittedCount++;
                put(row,0,student.studentId());put(row,1,record==null?student.name():record.getStudentName());put(row,2,record==null?student.grade():record.getGrade());put(row,3,materialId);put(row,4,workspace.lesson().title());
                var observation=record==null?null:record.getObservation();
                put(row,5,observation==null?null:observation.participation());put(row,6,observation==null?null:observation.attentionLevel());put(row,7,observation==null?null:observation.confidence());put(row,8,observation==null?null:observation.collaboration());put(row,9,observation==null?null:observation.learningDifficulty());put(row,10,observation==null?null:observation.learningStyle());
                put(row,11,record==null?null:record.getImprovement());put(row,12,record==null?null:record.getFeedback());put(row,13,record==null?"No Feedback":"Submitted");put(row,14,record==null||record.getSubmittedAt()==null?null:record.getSubmittedAt().toString());put(row,15,record==null||record.getUpdatedAt()==null?null:record.getUpdatedAt().toString());put(row,16,record==null?null:owner);
            }
            sheet.createFreezePane(0,1);sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,rowIndex-1),0,HEADERS.length-1));
            int[] widths={18,26,14,24,30,20,20,18,20,24,22,36,40,18,28,28,24};for(int c=0;c<widths.length;c++)sheet.setColumnWidth(c,widths[c]*256);
            book.write(out);String name=workspace.lesson().title().replaceAll("[^A-Za-z0-9]+","_").replaceAll("^_+|_+$","");if(name.isBlank())name="Lesson";
            return new Export(out.toByteArray(),submittedCount,name+"_Teacher_Feedback.xlsx");
        } catch(Exception ex){throw new IllegalStateException("Could not generate teacher feedback workbook",ex);}
    }
    private static void put(Row row,int column,String value){if(value!=null&&!value.isBlank())row.createCell(column).setCellValue(value);}
}
