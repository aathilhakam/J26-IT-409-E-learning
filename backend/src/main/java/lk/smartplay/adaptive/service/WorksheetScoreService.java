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
    public static final List<String> HEADERS=List.of("Student ID","Student Name","Grade","Worksheet ID","Worksheet Title","Subject","Score / 10","Teacher Feedback");
    private static final List<String> STUDENT_DETAIL_HEADERS=List.of("Student ID","School ID","Student Name","Grade","Classroom","Gender","Address","Guardian Name","Guardian Relationship","Guardian Phone","Added Date");
    private final StudentRepository students;private final ContentAssetRepository materials;private final AssessmentResultRepository results;private final AssessmentResultService assessments;private final StudentService studentService;private final MongoTemplate mongo;
    private final byte[] secret=new byte[32];
    public WorksheetScoreService(StudentRepository students,ContentAssetRepository materials,AssessmentResultRepository results,AssessmentResultService assessments,StudentService studentService,MongoTemplate mongo){this.students=students;this.materials=materials;this.results=results;this.assessments=assessments;this.studentService=studentService;this.mongo=mongo;new SecureRandom().nextBytes(secret);}
    public record Context(String materialId,String title,String subject,String grade,LocalDate date) {}
    public record RowResult(int row,String studentId,String studentName,String grade,String worksheetId,String title,String subject,Double score,String scoreValue,Double maxScore,String assessmentType,LocalDate assessmentDate,String feedback,String status,String message,String resultId) {}
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
            var sheet=wb.createSheet("Worksheet Scores");var header=sheet.createRow(0);for(int i=0;i<HEADERS.size();i++)header.createCell(i).setCellValue(HEADERS.get(i));int n=1;var editable=wb.createCellStyle();editable.setLocked(false);
            for(var s:roster){var row=sheet.createRow(n++);String[] values={s.getId(),s.getName(),s.getGrade(),c.materialId(),c.title(),c.subject(),"",""};for(int i=0;i<values.length;i++){var cell=row.createCell(i);cell.setCellValue(values[i]);if(i>=5)cell.setCellStyle(editable);}}
            // Protect identity columns A–E; cells in F–H carry the unlocked style
            // so teachers can enter Subject, Score, and Teacher Feedback. Set the
            // OOXML protection flags directly so no password verifier is created.
            var protection=sheet.getCTWorksheet().addNewSheetProtection();protection.setSheet(true);protection.setObjects(true);protection.setScenarios(true);
            sheet.createFreezePane(0,1);for(int i=0;i<HEADERS.size();i++)sheet.setColumnWidth(i,(i==4||i==7?35:24)*256);wb.write(out);return out.toByteArray();
        }catch(IOException e){throw bad("Could not create the score template");}
    }
    private String identity(String owner,Context c,String student){return "worksheet-"+hash((owner+"\n"+student+"\n"+(c.materialId().isBlank()?c.title().toLowerCase(Locale.ROOT)+"\n"+c.subject().toLowerCase(Locale.ROOT):c.materialId())+"\n"+c.date()).getBytes(StandardCharsets.UTF_8));}
    public PreviewResult preview(String owner,Context c,MultipartFile file){requireMaterial(c);return read(owner,c,file,false,null);}
    public ImportResult importScores(String owner,Context c,MultipartFile file,String token){requireMaterial(c);var result=read(owner,c,file,true,token);return new ImportResult(result.rows(),result.valid(),result.invalid(),result.blank(),result.existing());}
    public List<AssessmentResult> findForTeacher(String owner){return results.findByTeacherOwnerIdAndWorksheetTrueOrderByAssessedAtDesc(owner);}
    private PreviewResult read(String owner,Context c,MultipartFile file,boolean save,String token){
        if(file==null||file.isEmpty()||file.getSize()>5*1024*1024||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw bad("Select a valid .xlsx file up to 5 MB");
        try {
            byte[] bytes=file.getBytes();String binding=owner+"|"+c.toString()+"|"+hash(bytes);if(save)verify(token,binding);
            var out=new ArrayList<RowResult>();var allowed=new HashMap<String,Student>();students.findByOwnerId(owner).forEach(s->allowed.put(s.getId(),s));
            try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
                if(!(wb instanceof XSSFWorkbook))throw bad("Use an Excel .xlsx workbook");
                var sheet=findSheetWithHeaders(wb,HEADERS);
                if(sheet==null){
                    var details=findSheetWithHeaders(wb,STUDENT_DETAIL_HEADERS);
                    if(details!=null)throw bad("This is a Student Details workbook. Upload a completed Worksheet Scores template instead");
                    throw bad("Could not find a worksheet with the required score columns. Keep the template headers unchanged and upload again");
                }
                if(sheet.getRow(0)==null||sheet.getLastRowNum()>500)throw bad("Use the Worksheet Scores sheet with at most 500 student rows");
                var evaluator=wb.getCreationHelper().createFormulaEvaluator();
                var counts=new HashMap<String,Integer>();for(int i=1;i<=sheet.getLastRowNum();i++){var r=sheet.getRow(i);if(r!=null)try{counts.merge(cell(r,0),1,Integer::sum);}catch(ResponseStatusException ignored){/* The row reports its own formula error below. */}}
                for(int i=1;i<=sheet.getLastRowNum();i++){
                    var row=sheet.getRow(i);if(row==null)continue;String studentId="",name="",grade="",worksheetId="",rowTitle="",subject="",feedback="",scoreValue="";Double score=null;
                    try {
                        String[] values=new String[8];Arrays.fill(values,"");for(int col=0;col<8;col++)if(col!=6)values[col]=cell(row,col);
                        studentId=values[0];name=values[1];grade=values[2];worksheetId=values[3];rowTitle=values[4];subject=values[5];feedback=values[7];
                        scoreValue=readScoreValue(row);score=readScore(row,evaluator);if(score!=null)scoreValue=Double.toString(score);values[6]=scoreValue;
                        if(Arrays.stream(values).allMatch(String::isBlank))continue;
                        if(studentId.isBlank())throw bad("Student ID is required");
                        if(name.isBlank())throw bad("Student Name is required");
                        if(grade.isBlank())throw bad("Grade is required");
                        if(rowTitle.isBlank())throw bad("Worksheet Title is required");
                        if(subject.length()>180)throw bad("Subject must be at most 180 characters");
                        if(feedback.length()>2000)throw bad("Feedback must be at most 2000 characters");
                        var s=allowed.get(studentId);
                        if(s==null||!s.isActive()||(!c.grade().isBlank()&&!c.grade().equals(s.getGrade())))throw bad("Unknown, archived, unauthorized or wrong-grade student ID");
                        if(counts.getOrDefault(studentId,0)>1)throw bad("Duplicate student ID in this workbook");
                        if(!values[1].equals(s.getName())||!values[2].equals(s.getGrade()))throw bad("Student name/grade does not match this student ID");
                        if(!values[3].equals(c.materialId())||!values[4].equals(c.title()))throw bad("Worksheet metadata does not match the selected assessment");
                        // Validate only after extracting the raw value, so an out-of-range score is still visible.
                        if(score!=null&&(!Double.isFinite(score)||score<0||score>10))throw bad("Score must be numeric between 0 and 10");
                        if(score==null){out.add(new RowResult(i+1,studentId,name,grade,worksheetId,rowTitle,subject,null,scoreValue,10.0,"WORKSHEET",c.date(),feedback,"BLANK","No score entered; unchanged",null));continue;}
                        String id=identity(owner,c,studentId);var prior=results.findById(id);
                        if(prior.isPresent()){boolean same=Double.compare(prior.get().getScore(),score)==0 && Objects.equals(prior.get().getNotes(),feedback);out.add(new RowResult(i+1,studentId,name,grade,worksheetId,rowTitle,subject,score,scoreValue,10.0,"WORKSHEET",c.date(),feedback,same?"EXISTING":"CONFLICT",same?"Already imported; unchanged":"Existing score differs. Use explicit correction; nothing overwritten",id));continue;}
                        String status="VALID",message="Ready to import";
                        if(save){status="IMPORTED";message="Assessment saved";try{assessments.createWorksheet(studentId,owner,id,c.materialId(),c.title(),subject,c.date(),score,feedback);}catch(org.springframework.dao.DuplicateKeyException race){status="EXISTING";message="Already imported; unchanged";}catch(ResponseStatusException failure){status="FAILED";message="Could not save this row: "+failure.getReason();}catch(RuntimeException failure){status="FAILED";message="Could not save this row because of a persistence error";}}
                        out.add(new RowResult(i+1,studentId,name,grade,worksheetId,rowTitle,subject,score,scoreValue,10.0,"WORKSHEET",c.date(),feedback,status,message,id));
                    }catch(ResponseStatusException e){out.add(new RowResult(i+1,studentId,name,grade,worksheetId,rowTitle,subject,score,scoreValue,10.0,"WORKSHEET",c.date(),feedback,"INVALID",e.getReason(),null));}
                }
            }
            return new PreviewResult(save?null:sign(binding),List.copyOf(out),out.stream().filter(r->Set.of("VALID","IMPORTED").contains(r.status())).count(),out.stream().filter(r->Set.of("INVALID","CONFLICT","FAILED").contains(r.status())).count(),out.stream().filter(r->"BLANK".equals(r.status())).count(),out.stream().filter(r->"EXISTING".equals(r.status())).count());
        }catch(ResponseStatusException e){throw e;}catch(Exception e){throw bad("Malformed workbook. Use the downloaded .xlsx template and check any formula cells");}
    }
    public record Correction(double score,String feedback,int expectedRevision,String reason) {}
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
        return readScore(row,null);
    }
    static Double readScore(Row row,FormulaEvaluator evaluator){
        Cell scoreCell=row.getCell(6);
        if(scoreCell==null||scoreCell.getCellType()==CellType.BLANK)return null;
        if(scoreCell.getCellType()==CellType.FORMULA){
            CellValue value=null;try{var formulaEvaluator=evaluator==null?row.getSheet().getWorkbook().getCreationHelper().createFormulaEvaluator():evaluator;value=formulaEvaluator.evaluate(scoreCell);}catch(RuntimeException ignored){/* Fall back to Excel's cached result. */}
            if(value!=null){if(value.getCellType()==CellType.NUMERIC)return finiteScore(value.getNumberValue());if(value.getCellType()==CellType.STRING){String text=value.getStringValue().trim();if(text.isEmpty())return null;try{return finiteScore(Double.parseDouble(text));}catch(NumberFormatException ignored){throw bad("Score must be numeric between 0 and 10");}}if(value.getCellType()==CellType.ERROR)throw bad("Excel error cells are not supported for scores");}
            CellType cached=scoreCell.getCachedFormulaResultType();if(cached==CellType.NUMERIC)return finiteScore(scoreCell.getNumericCellValue());if(cached==CellType.STRING){String text=scoreCell.getStringCellValue().trim();if(text.isEmpty())return null;try{return finiteScore(Double.parseDouble(text));}catch(NumberFormatException ignored){throw bad("Score must be numeric between 0 and 10");}}throw bad("Enter a numeric score value between 0 and 10");
        }
        if(scoreCell.getCellType()==CellType.ERROR)throw bad("Excel error cells are not supported for scores");
        double score;
        if(scoreCell.getCellType()==CellType.NUMERIC)score=scoreCell.getNumericCellValue();
        else if(scoreCell.getCellType()==CellType.STRING){String value=scoreCell.getStringCellValue().trim();if(value.isEmpty())return null;try{score=Double.parseDouble(value);}catch(NumberFormatException e){throw bad("Score must be numeric between 0 and 10");}}
        else throw bad("Score must be numeric between 0 and 10");
        return finiteScore(score);
    }
    private static Double finiteScore(double score){if(!Double.isFinite(score))throw bad("Score must be numeric between 0 and 10");return score;}
    static String readScoreValue(Row row){
        Cell scoreCell=row.getCell(6);if(scoreCell==null||scoreCell.getCellType()==CellType.BLANK)return "";
        return switch(scoreCell.getCellType()){
            case NUMERIC->Double.toString(scoreCell.getNumericCellValue());
            case STRING->scoreCell.getStringCellValue();
            case FORMULA->"="+scoreCell.getCellFormula();
            case ERROR->"Excel error";
            default->"";
        };
    }
    public AssessmentResult correct(String owner,String id,Correction request){
        if(request==null)throw bad("Provide the corrected score, feedback and reason");
        var before=results.findById(id).filter(AssessmentResult::isWorksheet).filter(r->owner.equals(r.getTeacherOwnerId())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Worksheet result not found"));studentService.findById(before.getStudentId(),owner);
        if(!Double.isFinite(request.score())||request.score()<0||request.score()>10||text(request.feedback()).length()>2000||text(request.reason()).isBlank()||request.reason().length()>1000)throw bad("Use a score from 0 to 10, feedback up to 2000 characters and a correction reason");
        var query=Query.query(Criteria.where("_id").is(id).and("teacherOwnerId").is(owner).and("worksheetRevision").is(request.expectedRevision()));
        var audit=new LinkedHashMap<String,Object>();audit.put("previousScore",before.getScore());audit.put("previousFeedback",before.getNotes());audit.put("teacher",owner);audit.put("reason",request.reason());audit.put("at",Instant.now());
        var changed=mongo.findAndModify(query,new Update().set("score",request.score()).set("notes",text(request.feedback())).inc("worksheetRevision",1).push("corrections",audit),org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),AssessmentResult.class);
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
