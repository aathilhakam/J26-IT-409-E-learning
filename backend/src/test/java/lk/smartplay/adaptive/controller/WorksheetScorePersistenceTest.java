package lk.smartplay.adaptive.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="MONGODB_TEST_URI",matches=".+")
class WorksheetScorePersistenceTest {
    static final String DATABASE="smartplay_worksheet_test_"+UUID.randomUUID().toString().replace("-","");
    static final Path DIRECTORY=Path.of(System.getProperty("java.io.tmpdir"),DATABASE);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.data.mongodb.uri",()->System.getenv("MONGODB_TEST_URI"));p.add("spring.data.mongodb.database",()->DATABASE);
        p.add("smartplay.content.storage-dir",()->DIRECTORY.resolve("stored").toString());
    }
    @Autowired MockMvc mvc; @Autowired MongoTemplate mongo; @Autowired ObjectMapper mapper;
    @Autowired TeacherAccountRepository accounts; @Autowired StudentRepository students; @Autowired ContentAssetRepository materials; @Autowired AssessmentResultRepository assessmentResults;
    String email="worksheet@example.test",owner,materialId; Student first,second,third,foreign;
    static final String DATE="2026-10-20";
    static final List<String> HEADERS=List.of("Student ID","Student Name","Grade","Worksheet ID","Worksheet Title","Subject","Score / 10","Attempts","Time Taken (Minutes)","Status","Teacher Feedback");

    @BeforeEach void setup() {
        assertEquals(DATABASE,mongo.getDb().getName());
        owner=accounts.save(new TeacherAccount("Teacher",email,"unused")).getId();
        first=students.save(new Student("Oshadi Madushanka","Grade 1",owner));
        second=students.save(new Student("Bimsara Gunawardena","Grade 1",owner));
        third=students.save(new Student("Ayesha Niyas","Grade 1",owner));
        var archived=new Student("Archived Student","Grade 1",owner);archived.setActive(false);students.save(archived);
        String other=accounts.save(new TeacherAccount("Other","worksheet-other@example.test","unused")).getId();
        foreign=students.save(new Student("Foreign Student","Grade 1",other));
        var material=new ContentAsset(owner,"Fruits Worksheet","Worksheet","","fruits.pdf","stored-fruits.pdf","application/pdf",100);
        material.updateMetadata("Fruits Worksheet","Worksheet","","English","Grade 1","Worksheet",List.of(),"TEACHER_ONLY");
        material.assign("LIBRARY","",List.of());materialId=materials.save(material).getId();
    }
    @AfterEach void cleanup() throws Exception {
        mongo.getDb().drop();if(java.nio.file.Files.exists(DIRECTORY))try(var paths=java.nio.file.Files.walk(DIRECTORY)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(path);}
    }
    String query(){return "materialId="+materialId+"&grade=Grade%201&date="+DATE;}
    MockMultipartFile workbook(List<List<String>> rows)throws Exception {
        try(var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=wb.createSheet("Worksheet Scores");var header=sheet.createRow(0);for(int i=0;i<HEADERS.size();i++)header.createCell(i).setCellValue(HEADERS.get(i));
            int index=1;for(var values:rows){var row=sheet.createRow(index++);for(int i=0;i<values.size();i++)row.createCell(i).setCellValue(values.get(i));}
            wb.write(out);return new MockMultipartFile("file","scores.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
        }
    }
    MockMultipartFile numericWorkbook(double firstScore,double secondScore)throws Exception {
        try(var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=wb.createSheet("Worksheet Scores");var header=sheet.createRow(0);for(int i=0;i<HEADERS.size();i++)header.createCell(i).setCellValue(HEADERS.get(i));
            Student[] roster={first,second,third};double[] scores={firstScore,secondScore,Double.NaN};
            for(int index=0;index<roster.length;index++){
                var student=roster[index];var row=sheet.createRow(index+1);String[] values={student.getId(),student.getName(),student.getGrade(),materialId,"Fruits Worksheet","English","","2","15","COMPLETED","Feedback "+student.getName()};
                for(int column=0;column<values.length;column++)if(column!=6)row.createCell(column).setCellValue(values[column]);
                if(!Double.isNaN(scores[index]))row.createCell(6).setCellValue(scores[index]);
            }
            wb.write(out);return new MockMultipartFile("file","scores.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
        }
    }
    List<String> row(Student s,String score,String feedback){return Arrays.asList(s.getId(),s.getName(),s.getGrade(),materialId,"Fruits Worksheet","English",score,"2","15","COMPLETED",feedback);}
    JsonNode request(String action,MockMultipartFile file,String token,int expected)throws Exception {
        if("import".equals(action)&&token==null&&expected==200){var preview=request("preview",file,null,200);token=preview.get("token").asText();}
        var request=multipart("/api/worksheet-scores/"+action).file(file).param("materialId",materialId).param("grade","Grade 1").param("date",DATE).with(user(email).roles("TEACHER")).with(csrf());
        if(token!=null)request.param("token",token);if("import".equals(action))request.param("completionVerified","true");
        var response=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank()?mapper.createObjectNode():mapper.readTree(response);
    }
    @Test void templateUsesTheAuthenticatedTeachersSelectedMaterialAndActiveGradeRoster()throws Exception {
        var response=mvc.perform(get("/api/worksheet-scores/template").param("materialId",materialId).param("grade","Grade 1").param("date",DATE).with(user(email).roles("TEACHER"))).andReturn().getResponse();assertEquals(200,response.getStatus(),response.getContentAsString());
        assertTrue(response.getContentType().contains("spreadsheetml.sheet"));
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(response.getContentAsByteArray()))){var sheet=wb.getSheet("Worksheet Scores");assertEquals(3,sheet.getLastRowNum());var headers=sheet.getRow(0);for(int i=0;i<HEADERS.size();i++)assertEquals(HEADERS.get(i),headers.getCell(i).getStringCellValue());assertEquals(HEADERS.size(),headers.getLastCellNum());var ids=Set.of(sheet.getRow(1).getCell(0).getStringCellValue(),sheet.getRow(2).getCell(0).getStringCellValue(),sheet.getRow(3).getCell(0).getStringCellValue());assertEquals(Set.of(first.getId(),second.getId(),third.getId()),ids);assertEquals(materialId,sheet.getRow(1).getCell(3).getStringCellValue());assertEquals("Fruits Worksheet",sheet.getRow(1).getCell(4).getStringCellValue());assertEquals("English",sheet.getRow(1).getCell(5).getStringCellValue());assertEquals("1",sheet.getRow(1).getCell(7).getStringCellValue());assertEquals("COMPLETED",sheet.getRow(1).getCell(9).getStringCellValue());assertEquals("",new DataFormatter().formatCellValue(sheet.getRow(1).getCell(6)));assertEquals("",new DataFormatter().formatCellValue(sheet.getRow(1).getCell(10)));assertTrue(sheet.getProtect());assertFalse(((org.apache.poi.xssf.usermodel.XSSFSheet)sheet).getCTWorksheet().getSheetProtection().isSetPassword());for(int column=0;column<6;column++)assertTrue(sheet.getRow(1).getCell(column).getCellStyle().getLocked());for(int column=6;column<HEADERS.size();column++)assertFalse(sheet.getRow(1).getCell(column).getCellStyle().getLocked());}
        mvc.perform(get("/api/worksheet-scores/template").param("materialId",materialId).param("grade","Grade 2").param("date",DATE).with(user(email).roles("TEACHER"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/worksheet-scores/template").param("grade","Grade 1").param("date",DATE).with(user(email).roles("TEACHER"))).andExpect(status().isBadRequest());
    }
    @Test void uploadRequiresSelectedWorksheetAndRejectsMismatchedWorksheetIds()throws Exception {
        mvc.perform(get("/api/worksheet-scores/template").param("grade","Grade 1").param("date",DATE).with(user(email).roles("TEACHER"))).andExpect(status().isBadRequest());
        var mismatched=workbook(List.of(Arrays.asList(first.getId(),first.getName(),first.getGrade(),"other-material","Fruits Worksheet","English","8","1","5","COMPLETED","Feedback")));
        var preview=request("preview",mismatched,null,200);
        assertEquals(1,preview.get("invalid").asInt());
        assertEquals("Worksheet ID does not match the selected worksheet material",preview.get("rows").get(0).get("message").asText());
        assertEquals(0,mongo.getCollection("assessment_results").countDocuments());
    }
    @Test void importSavesSeparateAssessmentDocumentsIdempotently()throws Exception {
        var file=workbook(List.of(row(first,"8","Good observation"),row(second,"6",""),row(third,"","")));
        var saved=request("import",file,null,200);assertEquals(2,saved.get("valid").asInt());assertEquals(1,saved.get("blank").asInt());assertEquals(2,mongo.getCollection("assessment_results").countDocuments());assertEquals(0,mongo.getCollection("learning_records").countDocuments());
        var firstResult=mongo.getCollection("assessment_results").find(new org.bson.Document("studentId",first.getId())).first();assertNotNull(firstResult);assertEquals("Fruits Worksheet",firstResult.getString("worksheetTitle"));assertEquals(owner,firstResult.getString("teacherOwnerId"));assertEquals(10.0,firstResult.getDouble("maxScore"));assertEquals(80.0,firstResult.getDouble("score")*100/firstResult.getDouble("maxScore"));assertTrue(firstResult.getBoolean("worksheet"));assertEquals("WORKSHEET",firstResult.getString("assessmentType"));assertEquals("Good observation",firstResult.getString("notes"));assertEquals(2,firstResult.getInteger("attempts"));assertEquals(15.0,firstResult.getDouble("timeTakenMinutes"));assertEquals("COMPLETED",firstResult.getString("completionStatus"));
        var again=request("import",file,null,200);assertEquals(2,again.get("existing").asInt());assertEquals(0,again.get("valid").asInt());assertEquals(2,mongo.getCollection("assessment_results").countDocuments());
    }
    @Test void importPreservesRawDecimalAndZeroScoresAndUsesTheUploadedFile()throws Exception {
        var file=numericWorkbook(7.5,0.0);var saved=request("import",file,null,200);var rows=saved.get("rows");
        assertEquals(2,saved.get("valid").asInt());assertEquals(1,saved.get("blank").asInt());
        assertEquals(first.getId(),rows.get(0).get("studentId").asText());assertEquals(7.5,rows.get(0).get("score").asDouble());assertEquals("7.5",rows.get(0).get("scoreValue").asText());
        assertEquals("WORKSHEET",rows.get(0).get("assessmentType").asText());assertEquals(10.0,rows.get(0).get("maxScore").asDouble());assertEquals(DATE,rows.get(0).get("assessmentDate").asText());
        assertEquals(second.getId(),rows.get(1).get("studentId").asText());assertEquals(0.0,rows.get(1).get("score").asDouble());assertTrue(rows.get(2).get("score").isNull());
        assertEquals(7.5,assessmentResults.findById(rows.get(0).get("resultId").asText()).orElseThrow().getScore());
        var persisted=mongo.getCollection("assessment_results").find(new org.bson.Document("studentId",first.getId())).first();
        var persistedResult=assessmentResults.findById(rows.get(0).get("resultId").asText()).orElseThrow();
        assertEquals(10.0,persisted.getDouble("maxScore"));assertEquals("WORKSHEET",persistedResult.getAssessmentType());assertEquals(2,persistedResult.getAttempts());assertEquals(15.0,persistedResult.getTimeTakenMinutes());assertEquals("COMPLETED",persistedResult.getCompletionStatus());assertEquals("Fruits Worksheet",persistedResult.getWorksheetTitle());assertEquals("English",persistedResult.getSubject());assertEquals(java.time.LocalDate.parse(DATE),persistedResult.getAssessmentDate());assertEquals(first.getId(),persistedResult.getStudentId());
    }
    @Test void invalidScoreUnknownOrForeignStudentAndDuplicateRowsAreReportedWithoutWrites()throws Exception {
        var file=workbook(List.of(row(first,"11",""),row(second,"-1",""),row(foreign,"8",""),Arrays.asList("unknown","Unknown","Grade 1",materialId,"Fruits Worksheet","English","8","1","5","COMPLETED",""),row(first,"7","")));
        var result=request("import",file,null,200);assertEquals(5,result.get("invalid").asInt());assertEquals(0,result.get("valid").asInt());assertEquals(0,mongo.getCollection("assessment_results").countDocuments());
        var rows=result.get("rows");assertEquals(11.0,rows.get(0).get("score").asDouble());assertEquals("Duplicate student ID in this workbook",rows.get(0).get("message").asText());assertTrue(rows.get(2).get("message").asText().contains("unauthorized"));assertTrue(rows.get(3).get("message").asText().contains("student ID"));
    }
    @Test void rejectsNonExcelOversizedAndMalformedWorkbooks()throws Exception {
        request("import",new MockMultipartFile("file","scores.csv","text/csv","student,score".getBytes()),null,400);
        request("import",new MockMultipartFile("file","scores.xlsx","application/octet-stream",new byte[5*1024*1024+1]),null,400);
        request("import",new MockMultipartFile("file","scores.xlsx","application/octet-stream","not an excel file".getBytes()),null,400);
    }
    @Test void protectsAuthenticationCsrfRoleOwnershipAndExplicitCorrections()throws Exception {
        var file=workbook(List.of(row(first,"8","")));var saved=request("import",file,null,200);var id=saved.get("rows").get(0).get("resultId").asText();
        mvc.perform(get("/api/worksheet-scores/template?"+query())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/worksheet-scores/template?"+query()).with(user(email).roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/worksheet-scores/template?"+query()).with(user("worksheet-other@example.test").roles("TEACHER"))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/worksheet-scores/results/"+id).with(user(email).roles("TEACHER")).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        var updateBody="{\"score\":9,\"feedback\":\"rechecked\",\"attempts\":3,\"timeTakenMinutes\":20,\"completionStatus\":\"COMPLETED\",\"reason\":\"checked again\",\"expectedRevision\":0}";
        mvc.perform(patch("/api/worksheet-scores/results/"+id).with(user("worksheet-other@example.test").roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(updateBody)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/worksheet-scores/results/"+id).with(user(email).roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(updateBody)).andExpect(status().isOk());
        var updated=mongo.getCollection("assessment_results").find(new org.bson.Document("_id",id)).first();assertEquals(9.0,updated.getDouble("score"));assertEquals(3,updated.getInteger("attempts"));assertEquals(20.0,updated.getDouble("timeTakenMinutes"));assertEquals("COMPLETED",updated.getString("completionStatus"));assertEquals(1,updated.getInteger("worksheetRevision"));assertEquals(1,((List<?>)updated.get("corrections")).size());assertEquals(2,((org.bson.Document)((List<?>)updated.get("corrections")).get(0)).getInteger("previousAttempts"));
    }
}
