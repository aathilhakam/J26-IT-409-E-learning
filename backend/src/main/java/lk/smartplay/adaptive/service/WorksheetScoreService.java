package lk.smartplay.adaptive.service;

import java.io.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

@Service
public class WorksheetScoreService {
    public static final List<String> HEADERS=List.of("Student ID","Student Name","Grade","Worksheet ID","Worksheet Title","Score / 10","Attempts","Status");
    private static final List<String> LEGACY_FEEDBACK_HEADERS=List.of("Student ID","Student Name","Grade","Worksheet ID","Worksheet Title","Score / 10","Attempts","Status","Teacher Feedback");
    private static final List<String> LEGACY_HEADERS=List.of("Student ID","Student Name","Grade","Worksheet ID","Worksheet Title","Score / 10","Attempts","Time Taken (Minutes)","Status","Teacher Feedback");
    private static final List<String> LEGACY_SUBJECT_HEADERS=List.of("Student ID","Student Name","Grade","Worksheet ID","Worksheet Title","Subject","Score / 10","Attempts","Time Taken (Minutes)","Status","Teacher Feedback");
    private static final List<String> LEGACY_SUBJECT_BASIC_HEADERS=List.of("Student ID","Student Name","Grade","Worksheet ID","Worksheet Title","Subject","Score / 10","Teacher Feedback");
    private static final List<String> COMPLETION_STATUSES=List.of("COMPLETED","IN_PROGRESS","NOT_COMPLETED");
    private static final List<String> STUDENT_DETAIL_HEADERS=List.of("Student ID","School ID","Student Name","Grade","Classroom","Gender","Address","Guardian Name","Guardian Relationship","Guardian Phone","Added Date");
    private final StudentRepository students;private final ContentAssetRepository materials;private final AssessmentResultRepository results;private final AssessmentResultService assessments;private final StudentService studentService;private final MongoTemplate mongo;private final LearningRecommendationRepository recommendations;
    private final byte[] secret=new byte[32];
    public WorksheetScoreService(StudentRepository students,ContentAssetRepository materials,AssessmentResultRepository results,AssessmentResultService assessments,StudentService studentService,MongoTemplate mongo,LearningRecommendationRepository recommendations){this.students=students;this.materials=materials;this.results=results;this.assessments=assessments;this.studentService=studentService;this.mongo=mongo;this.recommendations=recommendations;new SecureRandom().nextBytes(secret);}
    public record Context(String materialId,String title,String subject,String grade,LocalDate date) {}
    public record RowResult(int row,String studentId,String studentName,String grade,String worksheetId,String title,String subject,Double score,String scoreValue,Double maxScore,String assessmentType,LocalDate assessmentDate,Integer attempts,Double timeTakenMinutes,String completionStatus,String feedback,String status,String message,String resultId) {}
    public record PreviewResult(String token,List<RowResult> rows,long valid,long invalid,long blank,long existing) {}
    public record ImportResult(List<RowResult> rows,long valid,long invalid,long blank,long existing) {}
    public Context context(String owner,String materialId,String title,String subject,String grade,String date){
        materialId=text(materialId);title=text(title);subject=text(subject);grade=text(grade);
        if(materialId.isBlank())throw bad("Please select a worksheet material before downloading or uploading the score template");
        var m=materials.findByIdAndOwnerId(materialId,owner).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Worksheet material not found"));title=m.getTitle();subject=text(m.getSubject());String materialGrade=text(m.getGrade());if(!materialGrade.isBlank()){if(grade.isBlank())grade=materialGrade;else if(!grade.equalsIgnoreCase(materialGrade))throw bad("Selected grade does not match the worksheet material");}
        if(title.length()>200||subject.length()>180||grade.length()>60)throw bad("Worksheet title, subject or grade exceeds the allowed length");
        LocalDate day;try{day=LocalDate.parse(date);}catch(Exception e){throw bad("Choose a valid assessment date");}
        if(day.isAfter(LocalDate.now(ZoneId.of("Asia/Colombo"))))throw bad("Assessment date cannot be in the future");
        return new Context(materialId,title,subject,grade,day);
    }
    public byte[] template(String owner,Context c){
        requireMaterial(c);
        var roster=students.findByOwnerId(owner).stream().filter(Student::isActive).filter(s->c.grade().isBlank()||c.grade().equals(s.getGrade())).sorted(Comparator.comparing(Student::getGrade,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).thenComparing(Student::getName).thenComparing(Student::getId)).toList();
        if(roster.isEmpty()||roster.size()>500)throw bad("Choose a grade with 1–500 active students");
        try(var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=wb.createSheet("Worksheet Scores");var header=sheet.createRow(0);var headerStyle=wb.createCellStyle();var headerFont=wb.createFont();headerFont.setBold(true);headerStyle.setFont(headerFont);for(int i=0;i<HEADERS.size();i++){var cell=header.createCell(i);cell.setCellValue(HEADERS.get(i));cell.setCellStyle(headerStyle);}int n=1;var editable=wb.createCellStyle();editable.setLocked(false);
            for(var s:roster){var row=sheet.createRow(n++);String[] values={s.getId(),s.getName(),s.getGrade(),c.materialId(),c.title(),"",Integer.toString(nextAttempt(owner,s.getId(),c)),"COMPLETED"};for(int i=0;i<values.length;i++){var cell=row.createCell(i);cell.setCellValue(values[i]);if(i>=5)cell.setCellStyle(editable);}}
            DataValidationHelper validation=sheet.getDataValidationHelper();
            var status=validation.createValidation(validation.createExplicitListConstraint(COMPLETION_STATUSES.toArray(String[]::new)),new org.apache.poi.ss.util.CellRangeAddressList(1,500,7,7));status.setShowErrorBox(true);sheet.addValidationData(status);
            addDecimalValidation(sheet,validation,5,0,10,1,500);addWholeValidation(sheet,validation,6,0,100_000,1,500);
            // Keep the roster worksheet editable in Excel; import validation still checks all identifiers and values.
            sheet.createFreezePane(0,1);sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(1,n-1),0,HEADERS.size()-1));int[] widths={26,28,16,28,36,16,14,22};for(int i=0;i<HEADERS.size();i++)sheet.setColumnWidth(i,widths[i]*256);
            wb.write(out);return out.toByteArray();
        }catch(IOException e){throw bad("Could not create the score template");}
    }
    private String identity(String owner,Context c,String student,LocalDate date){return "worksheet-"+hash((owner+"\n"+student+"\n"+c.materialId()+"\n"+date).getBytes(StandardCharsets.UTF_8));}
    private int nextAttempt(String owner,String student,Context context){
        var completed=results.findByStudentIdOrderByAssessedAtDesc(student).stream().filter(AssessmentResult::isWorksheet)
                .filter(row->owner.equals(row.getTeacherOwnerId())&&context.materialId().equals(row.getMaterialId())&&"COMPLETED".equals(row.getCompletionStatus()))
                .filter(row->Double.isFinite(row.getScore())&&Double.isFinite(row.getMaxScore())&&row.getMaxScore()>0&&row.getScore()>=0&&row.getScore()<=row.getMaxScore())
                .filter(row->(row.getAttempts()==null||row.getAttempts()>=0)&&(row.getTimeTakenMinutes()==null||Double.isFinite(row.getTimeTakenMinutes())&&row.getTimeTakenMinutes()>=0))
                .sorted(Comparator.comparing((AssessmentResult row)->row.getRecordedAt()==null?row.getAssessedAt():row.getRecordedAt(),Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        if(completed.isEmpty())return 1;
        var latest=completed.get(0);var latestAt=latest.getRecordedAt()==null?latest.getAssessedAt():latest.getRecordedAt();
        boolean practicePending=Optional.ofNullable(recommendations.findByStudentIdOrderByCreatedAtDesc(student)).orElse(List.of()).stream()
                .anyMatch(rec->owner.equals(rec.getTeacherOwnerId())&&context.materialId().equals(rec.getMaterialId())
                        &&rec.getStatus()==LearningRecommendation.Status.APPROVED&&rec.getAssignedAt()!=null
                        &&"RANDOM_FOREST".equals(rec.getSource())&&"PERSONALIZED".equals(rec.getPathwayType())
                        &&(latestAt==null||rec.getAssignedAt().isAfter(latestAt)));
        if(!practicePending)return 1;
        int previous=latest.getAttempts()==null?completed.size():Math.max(1,latest.getAttempts());
        return previous==Integer.MAX_VALUE?Integer.MAX_VALUE:previous+1;
    }
    public PreviewResult preview(String owner,Context c,MultipartFile file){requireMaterial(c);return read(owner,c,file,false,null);}
    public ImportResult importScores(String owner,Context c,MultipartFile file,String token,boolean completionVerified){requireMaterial(c);var result=read(owner,c,file,true,token,completionVerified);return new ImportResult(result.rows(),result.valid(),result.invalid(),result.blank(),result.existing());}
    public ImportResult importScores(String owner,Context c,MultipartFile file,String token){return importScores(owner,c,file,token,false);}
    public List<AssessmentResult> findForTeacher(String owner){return results.findByTeacherOwnerIdAndWorksheetTrueOrderByAssessedAtDesc(owner);}
    private PreviewResult read(String owner,Context c,MultipartFile file,boolean save,String token){return read(owner,c,file,save,token,false);}
    private PreviewResult read(String owner,Context c,MultipartFile file,boolean save,String token,boolean completionVerified){
        if(file==null||file.isEmpty()||file.getSize()>5*1024*1024||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw bad("Select a valid .xlsx file up to 5 MB");
        try {
            byte[] bytes=file.getBytes();String binding=owner+"|"+c.toString()+"|"+hash(bytes);if(save)verify(token,binding);
            var out=new ArrayList<RowResult>();var allowed=new HashMap<String,Student>();students.findByOwnerId(owner).forEach(s->allowed.put(s.getId(),s));
            try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
                if(!(wb instanceof XSSFWorkbook))throw bad("Use an Excel .xlsx workbook");
                var sheet=findSheetWithHeaders(wb,HEADERS);
                boolean legacySubjectColumn=false,legacyTimeColumn=false,hasFeedback=false,hasAttempts=true,hasStatus=true;
                if(sheet==null){sheet=findSheetWithHeaders(wb,LEGACY_FEEDBACK_HEADERS);hasFeedback=sheet!=null;}
                if(sheet==null){sheet=findSheetWithHeaders(wb,LEGACY_HEADERS);legacyTimeColumn=sheet!=null;hasFeedback=sheet!=null;}
                if(sheet==null){sheet=findSheetWithHeaders(wb,LEGACY_SUBJECT_HEADERS);legacySubjectColumn=sheet!=null;legacyTimeColumn=sheet!=null;hasFeedback=sheet!=null;}
                if(sheet==null){sheet=findSheetWithHeaders(wb,LEGACY_SUBJECT_BASIC_HEADERS);legacySubjectColumn=sheet!=null;hasFeedback=sheet!=null;hasAttempts=false;hasStatus=false;}
                if(sheet==null){
                    var details=findSheetWithHeaders(wb,STUDENT_DETAIL_HEADERS);
                    if(details!=null)throw bad("This is a Student Details workbook. Upload a completed Worksheet Scores template instead");
                    throw bad("Could not find a worksheet with the required score columns. Keep the template headers unchanged and upload again");
                }
                if(sheet.getRow(0)==null||sheet.getLastRowNum()>500)throw bad("Use the Worksheet Scores sheet with at most 500 student rows");
                var evaluator=wb.getCreationHelper().createFormulaEvaluator();
                var counts=new HashMap<String,Integer>();for(int i=1;i<=sheet.getLastRowNum();i++){var r=sheet.getRow(i);if(r!=null)try{counts.merge(cell(r,0),1,Integer::sum);}catch(ResponseStatusException ignored){/* The row reports its own formula error below. */}}
                for(int i=1;i<=sheet.getLastRowNum();i++){
                    var row=sheet.getRow(i);if(row==null)continue;String studentId="",name="",grade="",worksheetId="",rowTitle="",subject=c.subject(),feedback="",scoreValue="";Double score=null;
                    try {
                    int scoreColumn=legacySubjectColumn?6:5, attemptsColumn=hasAttempts?scoreColumn+1:-1, timeColumn=legacyTimeColumn?attemptsColumn+1:-1, statusColumn=hasStatus?(legacyTimeColumn?timeColumn+1:attemptsColumn+1):-1, feedbackColumn=hasFeedback?(hasStatus?statusColumn+1:scoreColumn+1):-1;
                    int valueCount=legacySubjectColumn?(hasAttempts?11:8):legacyTimeColumn?10:hasFeedback?9:8;String[] values=new String[valueCount];Arrays.fill(values,"");for(int col=0;col<values.length;col++)if(col!=scoreColumn)values[col]=cell(row,col);
                        studentId=values[0];name=values[1];grade=values[2];worksheetId=values[3];rowTitle=values[4];if(legacySubjectColumn)subject=values[5];if(hasFeedback)feedback=values[feedbackColumn];
                        scoreValue=readScoreValue(row,scoreColumn);score=readScore(row,evaluator,scoreColumn);if(score!=null)scoreValue=Double.toString(score);values[scoreColumn]=scoreValue;
                        if(Arrays.stream(values).allMatch(String::isBlank))continue;
                        if(name.isBlank())throw bad("Student Name is required");
                        if(grade.isBlank())throw bad("Grade is required");
                        if(worksheetId.isBlank())throw bad("Worksheet ID is required");
                        if(!worksheetId.equals(c.materialId()))throw bad("Worksheet ID does not match the selected worksheet material");
                        if(rowTitle.isBlank())throw bad("Worksheet Title is required");
                        if(!rowTitle.equals(c.title()))throw bad("Worksheet Title does not match the selected worksheet material");
                        if(!subject.equals(c.subject()))throw bad("Subject does not match the selected worksheet material");
                        if(subject.length()>180)throw bad("Subject must be at most 180 characters");
                        if(feedback.length()>2000)throw bad("Teacher Feedback must be at most 2000 characters");
                        if(studentId.isBlank())throw bad("Student ID is required");
                        var s=allowed.get(studentId);
                        if(s==null||!s.isActive()||(!c.grade().isBlank()&&!c.grade().equals(s.getGrade())))throw bad("Unknown, archived, unauthorized or wrong-grade student ID");
                        if(counts.getOrDefault(studentId,0)>1)throw bad("Duplicate student ID in this workbook");
                        if(!name.equals(s.getName())||!grade.equals(s.getGrade()))throw bad("Student name/grade does not match this student ID");
                        // Validate only after extracting the raw value, so an out-of-range score is still visible.
                        Integer attempts=hasAttempts?parseOptionalInteger(values[attemptsColumn],"Attempts"):null;if(hasAttempts&&(attempts==null||attempts<0))throw bad("Attempts must be a nonnegative whole number");Double minutes=legacyTimeColumn?parseOptionalFinite(values[timeColumn],"Time Taken (Minutes)"):null;if(minutes!=null&&minutes<0)throw bad("Time Taken must be nonnegative");
                        String completionStatus=hasStatus?values[statusColumn].trim().toUpperCase(Locale.ROOT):"COMPLETED";if(!COMPLETION_STATUSES.contains(completionStatus))throw bad("Status must be COMPLETED, IN_PROGRESS, or NOT_COMPLETED");
                        if(score==null){out.add(new RowResult(i+1,studentId,name,grade,worksheetId,rowTitle,subject,null,scoreValue,10.0,"WORKSHEET",c.date(),attempts,minutes,completionStatus,feedback,"BLANK","No score entered; unchanged",null));continue;}
                        if(!Double.isFinite(score)||score<0||score>10)throw bad("Score must be numeric between 0 and 10");
                        if(save&&completionStatus.equals("COMPLETED")&&!completionVerified)throw bad("Confirm that you verified completed worksheet statuses before importing");
                        String id=identity(owner,c,studentId,c.date());var prior=results.findById(id);
                        if(prior.isPresent()){boolean same=Double.compare(prior.get().getScore(),score)==0&&Double.compare(prior.get().getMaxScore(),10.0)==0&&Objects.equals(prior.get().getAttempts(),attempts)&&Objects.equals(prior.get().getTimeTakenMinutes(),minutes)&&Objects.equals(prior.get().getCompletionStatus(),completionStatus)&&Objects.equals(prior.get().getNotes(),text(feedback));out.add(new RowResult(i+1,studentId,name,grade,worksheetId,rowTitle,subject,score,scoreValue,10.0,"WORKSHEET",c.date(),attempts,minutes,completionStatus,feedback,same?"EXISTING":"CONFLICT",same?"Already imported; unchanged":"Existing assessment differs. Nothing was overwritten",id));continue;}
                        String status="VALID",message="Ready to import";
                        if(save){status="IMPORTED";message="Assessment saved";try{assessments.createWorksheet(studentId,owner,id,c.materialId(),c.title(),subject,c.date(),score,feedback,attempts,minutes,completionStatus);}catch(org.springframework.dao.DuplicateKeyException race){status="EXISTING";message="Already imported; unchanged";}catch(ResponseStatusException failure){status="FAILED";message="Could not save this row: "+failure.getReason();}catch(RuntimeException failure){status="FAILED";message="Could not save this row because of a persistence error";}}
                        out.add(new RowResult(i+1,studentId,name,grade,worksheetId,rowTitle,subject,score,scoreValue,10.0,"WORKSHEET",c.date(),attempts,minutes,completionStatus,feedback,status,message,id));
                    }catch(ResponseStatusException e){out.add(new RowResult(i+1,studentId,name,grade,worksheetId,rowTitle,subject,score,scoreValue,10.0,"WORKSHEET",c.date(),null,null,"",feedback,"INVALID",e.getReason(),null));}
                }
            }
            return new PreviewResult(save?null:sign(binding),List.copyOf(out),out.stream().filter(r->Set.of("VALID","IMPORTED").contains(r.status())).count(),out.stream().filter(r->Set.of("INVALID","CONFLICT","FAILED").contains(r.status())).count(),out.stream().filter(r->"BLANK".equals(r.status())).count(),out.stream().filter(r->"EXISTING".equals(r.status())).count());
        }catch(ResponseStatusException e){throw e;}catch(Exception e){throw bad("Malformed workbook. Use the downloaded .xlsx template and check any formula cells");}
    }
    public record Correction(double score,String feedback,Integer attempts,Double timeTakenMinutes,String completionStatus,int expectedRevision,String reason) {}
    private Sheet findSheetWithHeaders(Workbook workbook,List<String> headers){
        Sheet match=null;
        for(int index=0;index<workbook.getNumberOfSheets();index++){
            var sheet=workbook.getSheetAt(index);if(!hasExactHeaders(sheet.getRow(0),headers))continue;
            if(match!=null)throw bad("Workbook has more than one sheet with the required headers");
            match=sheet;
        }
        return match;
    }
    static boolean hasExactHeaders(Row row,List<String> headers){
        if(row==null)return false;
        var formatter=new DataFormatter(Locale.ROOT);
        for(int column=0;column<headers.size();column++){
            var headerCell=row.getCell(column);if(headerCell==null||headerCell.getCellType()==CellType.ERROR||headerCell.getCellType()==CellType.FORMULA)return false;
            if(!headers.get(column).equals(formatter.formatCellValue(headerCell)))return false;
        }
        for(int column=headers.size();column<row.getLastCellNum();column++){
            var extra=row.getCell(column);if(extra!=null&&!formatter.formatCellValue(extra).trim().isBlank())return false;
        }
        return true;
    }
    static Double readScore(Row row){
        return readScore(row,null,6);
    }
    static Double readScore(Row row,FormulaEvaluator evaluator){
        return readScore(row,evaluator,6);
    }
    private static Double readScore(Row row,FormulaEvaluator evaluator,int column){
        Cell scoreCell=row.getCell(column);
        if(scoreCell==null||scoreCell.getCellType()==CellType.BLANK)return null;
        if(scoreCell.getCellType()==CellType.FORMULA){
            CellValue value=null;try{var formulaEvaluator=evaluator==null?row.getSheet().getWorkbook().getCreationHelper().createFormulaEvaluator():evaluator;value=formulaEvaluator.evaluate(scoreCell);}catch(RuntimeException ignored){/* Fall back to Excel's cached result. */}
            if(value!=null){if(value.getCellType()==CellType.NUMERIC)return finiteScore(value.getNumberValue());if(value.getCellType()==CellType.STRING){String text=value.getStringValue().trim();if(text.isEmpty())return null;try{return finiteScore(Double.parseDouble(text));}catch(NumberFormatException ignored){throw bad("Score Earned must be numeric");}}if(value.getCellType()==CellType.ERROR)throw bad("Excel error cells are not supported for scores");}
            CellType cached=scoreCell.getCachedFormulaResultType();if(cached==CellType.NUMERIC)return finiteScore(scoreCell.getNumericCellValue());if(cached==CellType.STRING){String text=scoreCell.getStringCellValue().trim();if(text.isEmpty())return null;try{return finiteScore(Double.parseDouble(text));}catch(NumberFormatException ignored){throw bad("Score Earned must be numeric");}}throw bad("Enter a numeric Score Earned value");
        }
        if(scoreCell.getCellType()==CellType.ERROR)throw bad("Excel error cells are not supported for scores");
        double score;
        if(scoreCell.getCellType()==CellType.NUMERIC)score=scoreCell.getNumericCellValue();
        else if(scoreCell.getCellType()==CellType.STRING){String value=scoreCell.getStringCellValue().trim();if(value.isEmpty())return null;try{score=Double.parseDouble(value);}catch(NumberFormatException e){throw bad("Score Earned must be numeric");}}
        else throw bad("Score Earned must be numeric");
        return finiteScore(score);
    }
    private static Double finiteScore(double score){if(!Double.isFinite(score))throw bad("Score Earned must be numeric");return score;}
    static String readScoreValue(Row row){return readScoreValue(row,6);}
    private static String readScoreValue(Row row,int column){
        Cell scoreCell=row.getCell(column);if(scoreCell==null||scoreCell.getCellType()==CellType.BLANK)return "";
        return switch(scoreCell.getCellType()){
            case NUMERIC->Double.toString(scoreCell.getNumericCellValue());
            case STRING->scoreCell.getStringCellValue();
            case FORMULA->"="+scoreCell.getCellFormula();
            case ERROR->"Excel error";
            default->"";
        };
    }
    private static void addDecimalValidation(Sheet sheet,DataValidationHelper helper,int column,double min,double max,int firstRow,int lastRow){var validation=helper.createValidation(helper.createDecimalConstraint(DataValidationConstraint.OperatorType.BETWEEN,Double.toString(min),Double.toString(max)),new org.apache.poi.ss.util.CellRangeAddressList(firstRow,lastRow,column,column));validation.setShowErrorBox(true);sheet.addValidationData(validation);}
    private static void addWholeValidation(Sheet sheet,DataValidationHelper helper,int column,int min,int max,int firstRow,int lastRow){var validation=helper.createValidation(helper.createIntegerConstraint(DataValidationConstraint.OperatorType.BETWEEN,Integer.toString(min),Integer.toString(max)),new org.apache.poi.ss.util.CellRangeAddressList(firstRow,lastRow,column,column));validation.setShowErrorBox(true);sheet.addValidationData(validation);}
    private static Double parseOptionalFinite(String value,String label){if(value==null||value.isBlank())return null;try{double number=Double.parseDouble(value.trim());if(!Double.isFinite(number))throw bad(label+" must be a finite number");return number;}catch(NumberFormatException e){throw bad(label+" must be numeric");}}
    private static Integer parseOptionalInteger(String value,String label){Double number=parseOptionalFinite(value,label);if(number==null)return null;if(number<0||number>Integer.MAX_VALUE||number!=Math.rint(number))throw bad(label+" must be a nonnegative whole number");return number.intValue();}
    public AssessmentResult correct(String owner,String id,Correction request){
        if(request==null)throw bad("Provide the corrected score, feedback and reason");
        var before=results.findById(id).filter(AssessmentResult::isWorksheet).filter(r->owner.equals(r.getTeacherOwnerId())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Worksheet result not found"));studentService.findById(before.getStudentId(),owner);
        String completionStatus=text(request.completionStatus()).toUpperCase(Locale.ROOT);
        if(!Double.isFinite(request.score())||request.score()<0||request.score()>before.getMaxScore()||text(request.feedback()).length()>2000||text(request.reason()).isBlank()||request.reason().length()>1000||request.attempts()==null||request.attempts()<0||request.timeTakenMinutes()!=null&&(!Double.isFinite(request.timeTakenMinutes())||request.timeTakenMinutes()<0)||!COMPLETION_STATUSES.contains(completionStatus))throw bad("Enter a valid score, nonnegative attempts and time, a supported status, feedback up to 2000 characters and a correction reason");
        var query=Query.query(Criteria.where("_id").is(id).and("teacherOwnerId").is(owner).and("worksheetRevision").is(request.expectedRevision()));
        var audit=new LinkedHashMap<String,Object>();audit.put("previousScore",before.getScore());audit.put("previousFeedback",before.getNotes());audit.put("previousAttempts",before.getAttempts());audit.put("previousTimeTakenMinutes",before.getTimeTakenMinutes());audit.put("previousCompletionStatus",before.getCompletionStatus());audit.put("teacher",owner);audit.put("reason",request.reason());audit.put("at",Instant.now());
        var changed=mongo.findAndModify(query,new Update().set("score",request.score()).set("notes",text(request.feedback())).set("attempts",request.attempts()).set("timeTakenMinutes",request.timeTakenMinutes()).set("completionStatus",completionStatus).inc("worksheetRevision",1).push("corrections",audit),org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),AssessmentResult.class);
        if(changed==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Result changed. Reload before correcting");return changed;
    }
    private String cell(Row r,int col){Cell c=r.getCell(col);if(c==null)return "";if(c.getCellType()==CellType.ERROR||(c.getCellType()==CellType.FORMULA&&c.getCachedFormulaResultType()==CellType.ERROR))throw bad("Excel error cells are not supported");var formatter=new DataFormatter(Locale.ROOT);formatter.setUseCachedValuesForFormulaCells(true);String value;try{value=formatter.formatCellValue(c);}catch(RuntimeException error){throw bad("Could not read this cell. Save formula results in Excel and upload again");}if(value.length()>2100)throw bad("Cell value exceeds allowed length");return value;}
    private static String text(String s){return s==null?"":s.trim();}private static ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    private static void requireMaterial(Context c){if(c==null||c.materialId().isBlank())throw bad("Please select a worksheet material before downloading or uploading the score template");}
    private String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private String sign(String binding){long expires=Instant.now().plusSeconds(1800).getEpochSecond();return expires+"."+mac(expires+"|"+binding);}
    private void verify(String token,String binding){try{String[] parts=token.split("\\.");long expiry=Long.parseLong(parts[0]);if(parts.length!=2||expiry<Instant.now().getEpochSecond()||!MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8),mac(expiry+"|"+binding).getBytes(StandardCharsets.UTF_8)))throw bad("Upload changed or expired during import. Upload the workbook again");}catch(Exception e){throw bad("Upload changed or expired during import. Upload the workbook again");}}
    private String mac(String value){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
