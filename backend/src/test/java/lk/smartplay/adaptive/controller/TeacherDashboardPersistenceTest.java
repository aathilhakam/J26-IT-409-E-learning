package lk.smartplay.adaptive.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.mongodb.core.MongoTemplate;
import com.fasterxml.jackson.databind.*;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

@SpringBootTest @AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="MONGODB_TEST_URI",matches=".+")
class TeacherDashboardPersistenceTest {
    static final String DATABASE="smartplay_dashboard_test_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.data.mongodb.uri",()->System.getenv("MONGODB_TEST_URI")); p.add("spring.data.mongodb.database",()->DATABASE);
        p.add("smartplay.content.storage-dir",()->System.getProperty("java.io.tmpdir")+"/"+DATABASE);
    }
    @Autowired MockMvc mvc; @Autowired MongoTemplate mongo; @Autowired ObjectMapper mapper;
    @Autowired TeacherAccountRepository accounts; @Autowired StudentRepository students; @Autowired ContentAssetRepository materials;
    @Autowired LearningRecordRepository records; @Autowired AssessmentResultRepository assessments; @Autowired LearningRecommendationRepository recommendations;
    String email="dashboard@example.test", owner; Student first,second,otherGrade,foreign;
    @BeforeEach void setup() {
        assertEquals(DATABASE,mongo.getDb().getName());
        owner=accounts.save(new TeacherAccount("Teacher",email,"unused")).getId();
        first=students.save(new Student("First","Grade 1",owner)); second=students.save(new Student("Second","Grade 1",owner)); otherGrade=students.save(new Student("Other grade","Grade 2",owner));
        var archived=new Student("Archived","Grade 1",owner); archived.setActive(false); students.save(archived);
        var outsider=accounts.save(new TeacherAccount("Outsider","other@example.test","unused")); foreign=students.save(new Student("Foreign","Grade 1",outsider.getId()));
    }
    @AfterEach void cleanup() throws Exception {
        mongo.getDb().drop(); var dir=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),DATABASE);
        if(java.nio.file.Files.exists(dir)) try(var paths=java.nio.file.Files.walk(dir)) { for(var path:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(path); }
    }
    JsonNode getJson(String path) throws Exception { return mapper.readTree(mvc.perform(get(path).with(user(email).roles("TEACHER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    JsonNode upload(String mode,List<String> ids,String grade,String key,int status) throws Exception {
        var req=multipart("/api/content").file(new MockMultipartFile("file","lesson.pdf","application/pdf","%PDF-1.7 dashboard test".getBytes()))
            .param("title","Shared lesson").param("category","Worksheet").param("grade","Grade 1").param("materialType","Worksheet")
            .param("assignmentMode",mode).param("assignmentGrade",grade).with(user(email).roles("TEACHER")).with(csrf());
        for(String id:ids)req.param("studentIds",id); if(key!=null)req.header("Idempotency-Key",key);
        return mapper.readTree(mvc.perform(req).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    void change(String material,String student,String status,int expected) throws Exception { mvc.perform(patch("/api/dashboard/lessons/"+material+"/students/"+student).with(user(email).roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("status",status)))).andExpect(status().is(expected)); }
    @Test void sharedAssignmentsAreUniquePersistedAndIndependentOfResults() throws Exception {
        var privateMaterial=upload("LIBRARY",List.of(first.getId()),"",null,201);
        assertEquals(0,mongo.getCollection("student_assignments").countDocuments()); assertEquals(0,getJson("/api/dashboard/lessons").size());
        var lesson=upload("STUDENTS",List.of(first.getId(),first.getId(),second.getId()),"","selected-request-12345",201);
        var retry=upload("STUDENTS",List.of(first.getId(),first.getId(),second.getId()),"","selected-request-12345",201); assertEquals(lesson.get("id"),retry.get("id"));
        assertEquals(2,mongo.getCollection("content_assets").countDocuments()); assertEquals(2,mongo.getCollection("student_assignments").countDocuments());
        var feed=getJson("/api/dashboard/lessons"); assertEquals(1,feed.size()); assertEquals(2,feed.get(0).get("notStarted").asInt()); assertTrue(feed.get(0).get("overdue").isNull());
        change(lesson.get("id").asText(),first.getId(),"COMPLETED",200); change(lesson.get("id").asText(),second.getId(),"IN_PROGRESS",200);
        feed=getJson("/api/dashboard/lessons"); assertEquals(1,feed.get(0).get("completed").asInt()); assertEquals(1,feed.get(0).get("inProgress").asInt()); assertEquals(0,feed.get(0).get("notStarted").asInt());
        assertEquals(0,getJson("/api/dashboard/statistics").get("activityRecords").asInt()); assertEquals(0,getJson("/api/dashboard/statistics").get("learningProgress").asDouble());
        // Changing a material back to private removes it from the feed and deactivates assignments.
        mvc.perform(put("/api/content/"+lesson.get("id").asText()).with(user(email).roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Shared lesson\",\"category\":\"Worksheet\",\"assignmentMode\":\"LIBRARY\"}")).andExpect(status().isOk());
        assertEquals(0,getJson("/api/dashboard/lessons").size()); assertEquals(0,mongo.getCollection("student_assignments").countDocuments(new org.bson.Document("active",true)));
        change(privateMaterial.get("id").asText(),first.getId(),"COMPLETED",409);
    }
    @Test void gradeAndOwnerValidationAndStatisticsUseActualResults() throws Exception {
        upload("GRADE",List.of(),"Grade 1",null,201); var feed=getJson("/api/dashboard/lessons"); assertEquals(2,feed.get(0).get("assigned").asInt());
        upload("STUDENTS",List.of(foreign.getId()),"",null,400); upload("GRADE",List.of(),"Grade 9",null,400); upload("INVALID",List.of(),"",null,400);
        records.save(new LearningRecord(first.getId(),"Math",4,5,"")); var result=new AssessmentResult(); result.setStudentId(second.getId());result.setScore(6);result.setMaxScore(10);assessments.save(result);
        var invalid=new AssessmentResult();invalid.setStudentId(first.getId());invalid.setScore(4);invalid.setMaxScore(0);assessments.save(invalid);
        records.save(new LearningRecord(foreign.getId(),"Foreign",0,5,""));
        recommendations.save(new LearningRecommendation(first.getId(),"Math","Count","Practice","Need",0.8));
        var stats=getJson("/api/dashboard/statistics");assertEquals(3,stats.get("studentsEnrolled").asInt());assertEquals(3,stats.get("activityRecords").asInt());assertEquals(2,stats.get("validResults").asInt());assertEquals(0,stats.get("learningProgress").asDouble());assertEquals(1,stats.get("pendingReviews").asInt());
        assertEquals(1,getJson("/api/dashboard/reviews").size());assertEquals(0,getJson("/api/dashboard/statistics?studentId="+first.getId()).get("learningProgress").asDouble());
        mvc.perform(get("/api/dashboard/statistics?studentId="+foreign.getId()).with(user(email).roles("TEACHER"))).andExpect(status().isNotFound());
        var materialId=feed.get(0).get("id").asText();change(materialId,foreign.getId(),"COMPLETED",404);change(materialId,first.getId(),"FAKE",400);
        mvc.perform(get("/api/dashboard/lessons").with(user("other@example.test").roles("TEACHER"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(patch("/api/dashboard/lessons/"+materialId+"/students/"+first.getId()).with(user("other@example.test").roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}")).andExpect(status().isNotFound());
    }
    @Test void legacyRecipientsRemainUnrecordedAndEditsPreserveCompletedStatus() throws Exception {
        var old=new ContentAsset(owner,"Legacy lesson","Worksheet","","old.pdf","old.pdf","application/pdf",10);
        old.assign("STUDENTS","",List.of(first.getId(),second.getId())); old=materials.save(old);
        var feed=getJson("/api/dashboard/lessons"); assertEquals(2,feed.get(0).get("unrecorded").asInt()); assertTrue(feed.get(0).get("assignedAt").isNull());
        change(old.getId(),first.getId(),"COMPLETED",200);
        old=materials.save(materials.findById(old.getId()).orElseThrow());
        feed=getJson("/api/dashboard/lessons");assertEquals(1,feed.get(0).get("completed").asInt());assertEquals(1,feed.get(0).get("unrecorded").asInt());
        assertEquals(2,mongo.getCollection("student_assignments").countDocuments());
        // Newer assignments are sorted ahead of an older legacy material.
        var recent=upload("STUDENTS",List.of(first.getId()),"",null,201);
        assertEquals(recent.get("id").asText(),getJson("/api/dashboard/lessons").get(0).get("id").asText());
    }
    @Test void authenticationCsrfAndUploadKeyConflictsAreEnforced() throws Exception {
        mvc.perform(get("/api/dashboard/lessons")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard/statistics").with(user(email).roles("STUDENT"))).andExpect(status().isForbidden());
        var lesson=upload("STUDENTS",List.of(first.getId()),"","request-conflict-1234",201);
        upload("STUDENTS",List.of(second.getId()),"","request-conflict-1234",409);
        assertEquals(1,mongo.getCollection("student_assignments").countDocuments());
        mvc.perform(patch("/api/dashboard/lessons/"+lesson.get("id").asText()+"/students/"+first.getId()).with(user(email).roles("TEACHER")).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}")).andExpect(status().isForbidden());
    }
    @Test void teacherCompletesCurrentLessonOnlyAfterEveryStudentAndQueueAdvances() throws Exception {
        upload("STUDENTS",List.of(first.getId(),second.getId()),"",null,201);
        upload("STUDENTS",List.of(first.getId(),second.getId()),"",null,201);
        upload("STUDENTS",List.of(first.getId(),second.getId()),"",null,201);
        var feed=getJson("/api/dashboard/lessons");
        String current=feed.get(0).get("id").asText(), next=feed.get(1).get("id").asText();
        mvc.perform(post("/api/dashboard/lessons/"+current+"/complete").with(user(email).roles("TEACHER")).with(csrf())).andExpect(status().isConflict());
        change(current,first.getId(),"COMPLETED",200);
        mvc.perform(post("/api/dashboard/lessons/"+current+"/complete").with(user(email).roles("TEACHER")).with(csrf())).andExpect(status().isConflict());
        change(current,second.getId(),"COMPLETED",200);
        mvc.perform(post("/api/dashboard/lessons/"+next+"/complete").with(user(email).roles("TEACHER")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/dashboard/lessons/"+current+"/complete").with(user("other@example.test").roles("TEACHER")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/dashboard/lessons/"+current+"/complete").with(user(email).roles("TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/dashboard/lessons/"+current+"/complete").with(user(email).roles("STUDENT")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/dashboard/lessons/"+current+"/complete").with(user(email).roles("TEACHER")).with(csrf())).andExpect(status().isOk());
        assertNotNull(materials.findById(current).orElseThrow().getClassroomCompletedAt());
        var remaining=getJson("/api/dashboard/lessons");assertEquals(2,remaining.size());assertEquals(next,remaining.get(0).get("id").asText());
        // Completion is durable and safe to retry; it neither deletes the material nor creates results.
        mvc.perform(post("/api/dashboard/lessons/"+current+"/complete").with(user(email).roles("TEACHER")).with(csrf())).andExpect(status().isOk());
        assertEquals(3,materials.count());assertEquals(0,getJson("/api/dashboard/statistics").get("activityRecords").asInt());
    }

    Map<String,Object> feedbackRow(String studentId,String status) {
        return new LinkedHashMap<>(Map.of("studentId",studentId,"status",status,"observation",new LinkedHashMap<>(Map.of(
            "participation","High","attentionLevel","Medium","confidence","High","collaboration","Good",
            "learningDifficulty","Vocabulary development","learningStyle","Visual learner","demonstratedSkills",List.of("Named two birds"))),
            "question","Which bird is this?","studentAnswer","A parrot","improvement","Answered without prompting","feedback","Practise two more names"));
    }
    JsonNode saveFeedback(String lesson,List<Map<String,Object>> rows,int status) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/dashboard/lessons/"+lesson+"/feedback").with(user(email).roles("TEACHER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("students",rows))))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    @Test void batchFeedbackPersistsObservationHistoryAndStatusOncePerRecipientAndOpensNextWeek() throws Exception {
        upload("STUDENTS",List.of(first.getId(),second.getId()),"",null,201);
        upload("STUDENTS",List.of(first.getId(),second.getId()),"",null,201);
        var feed=getJson("/api/dashboard/lessons"); String current=feed.get(0).get("id").asText(), next=feed.get(1).get("id").asText();
        assertEquals(1,getJson("/api/dashboard/lessons/"+current).get("weekPosition").asInt());
        assertEquals(2,getJson("/api/dashboard/lessons/"+next).get("weekPosition").asInt());
        var batch=List.of(feedbackRow(first.getId(),"COMPLETED"),feedbackRow(second.getId(),"IN_PROGRESS"));
        saveFeedback(next,batch,409);
        var response=saveFeedback(current,batch,200);assertEquals("SAVED",response.get(0).get("status").asText());assertEquals("SAVED",response.get(1).get("status").asText());
        assertEquals(2,mongo.getCollection("teacher_observations").countDocuments());assertEquals(2,mongo.getCollection("historical_lessons").countDocuments());
        assertEquals(2,getJson("/api/dashboard/lessons/history").get(0).get("completed").asInt());
        assertEquals(0,getJson("/api/dashboard/statistics").get("activityRecords").asInt());
        var observed=getJson("/api/students/"+first.getId()+"/observations").get(0);
        assertEquals(current,observed.get("materialId").asText());assertEquals("A parrot",observed.get("studentAnswer").asText());assertEquals("Answered without prompting",observed.get("improvement").asText());
        var savedHistory=getJson("/api/students/"+first.getId()+"/history").get(0);assertEquals(current,savedHistory.get("lessonId").asText());assertEquals(1,savedHistory.get("sequenceNumber").asInt());
        batch.get(0).put("studentAnswer","A crow");batch.get(1).put("status","COMPLETED");
        saveFeedback(current,batch,409); // Submitted snapshots cannot be silently overwritten.
        batch.forEach(row -> row.put("draftKey","revision-two"));
        saveFeedback(current,batch,200);saveFeedback(current,batch,200);
        assertEquals(2,mongo.getCollection("teacher_observations").countDocuments());assertEquals(2,mongo.getCollection("historical_lessons").countDocuments());
        var restored=getJson("/api/dashboard/lessons/"+current);assertEquals(2,restored.get("lesson").get("completed").asInt());
        assertTrue(restored.get("saved").toString().contains("A crow"));
        mvc.perform(post("/api/dashboard/lessons/"+current+"/complete").with(user(email).roles("TEACHER")).with(csrf())).andExpect(status().isOk());
        assertTrue(getJson("/api/dashboard/lessons/"+current).get("completed").asBoolean());
        saveFeedback(current,batch,200);assertEquals(1,getJson("/api/dashboard/lessons/"+next).get("weekPosition").asInt());
        saveFeedback(next,List.of(feedbackRow(first.getId(),"COMPLETED")),200);
        assertEquals(3,mongo.getCollection("historical_lessons").countDocuments());
        assertEquals(2,getJson("/api/students/"+first.getId()+"/history").get(0).get("sequenceNumber").asInt());
    }
    @Test void lessonFeedbackValidatesWholeBatchOwnershipCategoriesAndTeacherAccess() throws Exception {
        String current=upload("STUDENTS",List.of(first.getId(),second.getId()),"",null,201).get("id").asText();
        String library=upload("LIBRARY",List.of(),"",null,201).get("id").asText();
        mvc.perform(get("/api/dashboard/lessons/"+current)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard/lessons/"+current).with(user(email).roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard/lessons/"+current).with(user("other@example.test").roles("TEACHER"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/dashboard/lessons/"+library).with(user(email).roles("TEACHER"))).andExpect(status().isNotFound());
        saveFeedback(current,List.of(feedbackRow(first.getId(),"COMPLETED"),feedbackRow(foreign.getId(),"COMPLETED")),404);
        var invalid=feedbackRow(second.getId(),"COMPLETED");
        @SuppressWarnings("unchecked") var observation=(Map<String,Object>)invalid.get("observation");observation.put("attentionLevel","Automatic guess");
        saveFeedback(current,List.of(feedbackRow(first.getId(),"COMPLETED"),invalid),400);
        var tooLong=feedbackRow(first.getId(),"COMPLETED");tooLong.put("feedback","x".repeat(2001));saveFeedback(current,List.of(tooLong),400);
        var duplicate=feedbackRow(first.getId(),"COMPLETED");saveFeedback(current,List.of(duplicate,duplicate),400);
        assertEquals(0,mongo.getCollection("teacher_observations").countDocuments());assertEquals(0,mongo.getCollection("historical_lessons").countDocuments());
        var body=mapper.writeValueAsBytes(Map.of("students",List.of(feedbackRow(first.getId(),"COMPLETED"))));
        mvc.perform(post("/api/dashboard/lessons/"+current+"/feedback").with(user(email).roles("TEACHER")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/dashboard/lessons/"+current+"/feedback").with(user("other@example.test").roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        first.setActive(false);students.save(first);
        saveFeedback(current,List.of(feedbackRow(first.getId(),"COMPLETED")),404);
    }

    @Test void feedbackDraftsAreSeparateFromImmutableStudentSubmissions() throws Exception {
        String current=upload("STUDENTS",List.of(first.getId(),second.getId()),"",null,201).get("id").asText();
        var draft=feedbackRow(first.getId(),"");draft.put("draftKey","draft-lifecycle");
        draft.put("observation",Map.of("participation","","attentionLevel","","confidence","","collaboration","","learningDifficulty","","learningStyle","","demonstratedSkills",List.of()));
        var body=mapper.writeValueAsBytes(Map.of("students",List.of(draft)));
        var saved=mapper.readTree(mvc.perform(put("/api/dashboard/lessons/"+current+"/feedback/drafts").with(user(email).roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0).get("record");
        assertEquals("DRAFT",saved.get("submissionStatus").asText());assertTrue(saved.get("submittedAt").isNull());
        assertEquals(0,getJson("/api/dashboard/lessons/"+current+"/feedback").size());
        assertEquals(0,getJson("/api/students/"+first.getId()+"/feedback").size());
        assertEquals(0,mongo.getCollection("teacher_observations").countDocuments());assertEquals(0,mongo.getCollection("historical_lessons").countDocuments());
        saveFeedback(current,List.of(draft),400);
        var submitted=feedbackRow(first.getId(),"COMPLETED");submitted.put("draftKey","draft-lifecycle");submitted.put("expectedVersion",saved.get("version").asLong());
        var result=saveFeedback(current,List.of(submitted),200).get(0).get("record");
        assertEquals("SUBMITTED",result.get("submissionStatus").asText());assertFalse(result.get("submittedAt").isNull());assertEquals(saved.get("createdAt"),result.get("createdAt"));
        saveFeedback(current,List.of(submitted),200);
        assertEquals(1,mongo.getCollection("lesson_feedback").countDocuments());
        var history=getJson("/api/students/"+first.getId()+"/feedback");assertEquals(1,history.size());assertEquals(first.getId(),history.get(0).get("studentId").asText());assertEquals(current,history.get(0).get("materialId").asText());
        assertEquals(0,getJson("/api/students/"+second.getId()+"/feedback").size());
        assertEquals(1,getJson("/api/dashboard/feedback").size());assertEquals(0,getJson("/api/dashboard/feedback?status=DRAFT").size());
        submitted.put("feedback","Changed after submission");saveFeedback(current,List.of(submitted),409);
        assertEquals("Practise two more names",getJson("/api/students/"+first.getId()+"/feedback").get(0).get("feedback").asText());
        submitted.put("draftKey","explicit-revision");submitted.remove("expectedVersion");saveFeedback(current,List.of(submitted),200);
        assertEquals(2,getJson("/api/students/"+first.getId()+"/feedback").size());assertEquals(1,mongo.getCollection("teacher_observations").countDocuments());assertEquals(1,mongo.getCollection("historical_lessons").countDocuments());
        mvc.perform(get("/api/students/"+first.getId()+"/feedback").with(user("other@example.test").roles("TEACHER"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/students/"+first.getId()+"/feedback").with(user(email).roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard/lessons/"+current+"/feedback").with(user("other@example.test").roles("TEACHER"))).andExpect(status().isNotFound());
    }

    @Test void tenStudentsReceiveIndependentSubmittedFeedbackAndOneRevisionCannotChangeTheOthers() throws Exception {
        var roster=new ArrayList<Student>(List.of(first,second));
        for(int i=3;i<=10;i++)roster.add(students.save(new Student("Learner "+i,"Grade 1",owner)));
        var ids=roster.stream().map(Student::getId).toList();
        String material=upload("STUDENTS",ids,"",null,201).get("id").asText();
        var batch=ids.stream().map(id->feedbackRow(id,"COMPLETED")).toList();
        var result=saveFeedback(material,batch,200);assertEquals(10,result.size());
        for(var row:result)assertEquals("SAVED",row.get("status").asText());
        var stored=mongo.getCollection("lesson_feedback").find().into(new ArrayList<org.bson.Document>());
        assertEquals(10,stored.size());assertEquals(10,stored.stream().map(row->row.getString("studentId")).distinct().count());
        for(var row:stored){assertEquals(owner,row.getString("ownerId"));assertEquals(material,row.getString("materialId"));assertEquals("SUBMITTED",row.getString("submissionStatus"));assertNotNull(row.get("submittedAt"));}
        saveFeedback(material,batch,200);assertEquals(10,mongo.getCollection("lesson_feedback").countDocuments());
        var update=feedbackRow(first.getId(),"COMPLETED");update.put("draftKey","first-student-update");update.put("feedback","Individual next step");
        saveFeedback(material,List.of(update),200);saveFeedback(material,List.of(update),200);
        assertEquals(11,mongo.getCollection("lesson_feedback").countDocuments());
        var firstFeedback=getJson("/api/students/"+first.getId()+"/feedback");assertEquals(2,firstFeedback.size());assertEquals("Individual next step",firstFeedback.get(0).get("feedback").asText());
        for(var student:roster.subList(1,roster.size())){
            var feedback=getJson("/api/students/"+student.getId()+"/feedback");assertEquals(1,feedback.size());assertEquals(material,feedback.get(0).get("materialId").asText());assertEquals("Practise two more names",feedback.get(0).get("feedback").asText());
        }
        mvc.perform(get("/api/students/"+first.getId()+"/feedback")).andExpect(status().isUnauthorized());
    }

    @Test void feedbackWithoutLearningDifficultySavesWithoutInventingOrReplacingModelObservations() throws Exception {
        String material=upload("STUDENTS",List.of(first.getId()),"",null,201).get("id").asText();
        var feedback=feedbackRow(first.getId(),"COMPLETED");
        @SuppressWarnings("unchecked") var observation=(Map<String,Object>)feedback.get("observation");
        observation.remove("learningDifficulty");
        var result=saveFeedback(material,List.of(feedback),200);assertEquals("SAVED",result.get(0).get("status").asText());
        assertEquals("",getJson("/api/students/"+first.getId()+"/feedback").get(0).get("observation").get("learningDifficulty").asText());
        assertEquals(0,mongo.getCollection("teacher_observations").countDocuments());
        assertEquals(1,mongo.getCollection("historical_lessons").countDocuments());
        assertEquals(1,getJson("/api/dashboard/lessons/history").get(0).get("completed").asInt());
        var existing=new TeacherObservation(first.getId(),owner,"Low","Low","Low","Fair","Vocabulary development","Visual learner",List.of(),java.time.Instant.now());
        mongo.save(existing);
        feedback.put("draftKey","without-difficulty-update");saveFeedback(material,List.of(feedback),200);
        var observed=getJson("/api/students/"+first.getId()+"/observations");assertEquals(1,observed.size());assertEquals("Vocabulary development",observed.get(0).get("learningDifficulty").asText());assertEquals("Low",observed.get(0).get("participation").asText());
        assertEquals(2,getJson("/api/students/"+first.getId()+"/feedback").size());
    }

    @Test void submittedStatuslessFeedbackCompletesAssignmentsWithoutRewritingSavedSnapshots() throws Exception {
        String material=upload("STUDENTS",List.of(first.getId(),second.getId()),"",null,201).get("id").asText();
        change(material,second.getId(),"IN_PROGRESS",200);
        var one=feedbackRow(first.getId(),"COMPLETED");one.remove("status");var two=feedbackRow(second.getId(),"COMPLETED");two.remove("status");
        var result=saveFeedback(material,List.of(one,two),200);assertEquals("SAVED",result.get(0).get("status").asText());assertEquals("SAVED",result.get(1).get("status").asText());
        assertEquals("NOT_STARTED",getJson("/api/students/"+first.getId()+"/feedback").get(0).get("activityStatus").asText());
        assertEquals("IN_PROGRESS",getJson("/api/students/"+second.getId()+"/feedback").get(0).get("activityStatus").asText());
        var lesson=getJson("/api/dashboard/lessons/history").get(0);assertEquals(0,lesson.get("notStarted").asInt());assertEquals(0,lesson.get("inProgress").asInt());assertEquals(2,lesson.get("completed").asInt());
        one.put("draftKey","statusless-draft");
        var draft=mapper.readTree(mvc.perform(put("/api/dashboard/lessons/"+material+"/feedback/drafts").with(user(email).roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("students",List.of(one)))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0).get("record");
        change(material,first.getId(),"COMPLETED",200);one.put("expectedVersion",draft.get("version").asLong());
        saveFeedback(material,List.of(one),200);
        assertEquals("COMPLETED",getJson("/api/students/"+first.getId()+"/feedback").get(0).get("activityStatus").asText());
    }
    @Test void legacyFeedbackSnapshotIsPreservedWhileAssignmentCompletionIsRepaired() throws Exception {
        var material=new ContentAsset(owner,"Legacy activity","Worksheet","","old.pdf","old.pdf","application/pdf",10);
        material.assign("STUDENTS","",List.of(first.getId()));material=materials.save(material);
        var row=feedbackRow(first.getId(),"COMPLETED");row.remove("status");
        var result=saveFeedback(material.getId(),List.of(row),200);assertEquals("SAVED",result.get(0).get("status").asText());
        assertEquals("UNRECORDED",getJson("/api/students/"+first.getId()+"/feedback").get(0).get("activityStatus").asText());
        assertEquals(0,mongo.getCollection("historical_lessons").countDocuments());
        change(material.getId(),first.getId(),"IN_PROGRESS",200);
        saveFeedback(material.getId(),List.of(row),200);
        assertEquals(1,mongo.getCollection("lesson_feedback").countDocuments());assertEquals(1,getJson("/api/dashboard/lessons/history").get(0).get("completed").asInt());
    }

    @Test void submittedFeedbackCompletesEachStudentOnceAndMovesFullyReviewedLessonToHistory() throws Exception {
        String material=upload("STUDENTS",List.of(first.getId(),second.getId()),"",null,201).get("id").asText();
        var one=feedbackRow(first.getId(),"COMPLETED");one.remove("status");
        var two=feedbackRow(second.getId(),"COMPLETED");two.remove("status");
        mvc.perform(put("/api/dashboard/lessons/"+material+"/feedback/drafts").with(user(email).roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("students",List.of(two))))).andExpect(status().isOk());
        assertEquals(0,getJson("/api/dashboard/lessons").get(0).get("completed").asInt());
        saveFeedback(material,List.of(one),200);saveFeedback(material,List.of(one),200);
        assertEquals(1,getJson("/api/dashboard/lessons").get(0).get("completed").asInt());assertEquals(0,getJson("/api/dashboard/lessons/history").size());
        // Submit using another key; the old draft must not add a second completion.
        two.put("draftKey","submitted-second");saveFeedback(material,List.of(two),200);saveFeedback(material,List.of(two),200);
        assertEquals(0,getJson("/api/dashboard/lessons").size());assertEquals(1,getJson("/api/dashboard/lessons/history").size());
        assertEquals(2,getJson("/api/dashboard/lessons/history").get(0).get("completed").asInt());
        assertEquals(2,getJson("/api/dashboard/lessons/"+material).get("submitted").size());assertTrue(getJson("/api/dashboard/lessons/"+material).get("completed").asBoolean());
        assertEquals(100,getJson("/api/dashboard/statistics").get("learningProgress").asDouble());
        // Simulate legacy persisted feedback whose assignment synchronization never happened.
        mongo.updateMulti(new org.springframework.data.mongodb.core.query.Query(),new org.springframework.data.mongodb.core.query.Update().set("status","NOT_STARTED").unset("completedAt"),StudentAssignment.class);
        assertEquals(2,getJson("/api/dashboard/lessons/history").get(0).get("completed").asInt());
        var snapshot=mongo.getCollection("student_assignments").find().into(new ArrayList<org.bson.Document>());
        getJson("/api/dashboard/lessons/history");assertEquals(snapshot,mongo.getCollection("student_assignments").find().into(new ArrayList<org.bson.Document>()));
        assertEquals(2,mongo.getCollection("student_assignments").countDocuments());
    }

    @Test void summaryUsesOwnedMaterialsUniqueAssignmentsAndRefreshesAfterReviews() throws Exception {
        var empty=getJson("/api/dashboard/statistics");assertEquals(0,empty.get("teacherMaterials").asInt());assertEquals(0,empty.get("learningProgress").asDouble());
        upload("LIBRARY",List.of(),"",null,201);
        String material=upload("STUDENTS",List.of(first.getId(),first.getId(),second.getId()),"","summary-idempotency-key",201).get("id").asText();
        upload("STUDENTS",List.of(first.getId(),first.getId(),second.getId()),"","summary-idempotency-key",201);
        upload("STUDENTS",List.of(first.getId()),"",null,201);
        records.save(new LearningRecord(first.getId(),"Math",10,10,""));
        var assessment=new AssessmentResult();assessment.setStudentId(first.getId());assessment.setScore(10);assessment.setMaxScore(10);assessments.save(assessment);assessments.save(assessment);
        assertEquals(2,getJson("/api/dashboard/statistics").get("activityRecords").asInt());
        assertEquals(3,getJson("/api/dashboard/statistics").get("teacherMaterials").asInt());
        assertEquals(3,getJson("/api/dashboard/statistics").get("learningRecords").asInt());
        assertEquals(3,getJson("/api/dashboard/statistics").get("remainingLessons").asInt());
        assertEquals(0,getJson("/api/dashboard/statistics").get("learningProgress").asDouble());
        change(material,first.getId(),"COMPLETED",200);change(material,first.getId(),"COMPLETED",200);
        assertEquals(0,getJson("/api/dashboard/statistics").get("learningProgress").asDouble());
        assertEquals(0,getJson("/api/dashboard/statistics?studentId="+first.getId()).get("learningProgress").asDouble());
        change(material,first.getId(),"IN_PROGRESS",200);assertEquals(0,getJson("/api/dashboard/statistics").get("learningProgress").asDouble());
        change(material,first.getId(),"COMPLETED",200);change(material,second.getId(),"COMPLETED",200);
        // Finished lessons remain part of the percentage after leaving the weekly queue.
        materials.findById(material).ifPresent(m->{m.completeClassroomLesson();materials.save(m);});
        assertEquals(33.33,getJson("/api/dashboard/statistics").get("learningProgress").asDouble());
        assertEquals(1,getJson("/api/dashboard/statistics").get("completedLessons").asInt());
        assertEquals(2,getJson("/api/dashboard/statistics").get("remainingLessons").asInt());
        var pending=recommendations.save(new LearningRecommendation(first.getId(),"Math","Count","Practice","Need",.8));
        var rejected=new LearningRecommendation(second.getId(),"Math","Count","Practice","Need",.8);rejected.review(LearningRecommendation.Status.REJECTED,null,null,"");recommendations.save(rejected);
        recommendations.save(new LearningRecommendation(foreign.getId(),"Math","Other","Practice","Need",.8));
        assertEquals(1,getJson("/api/dashboard/statistics").get("pendingReviews").asInt());
        mvc.perform(patch("/api/recommendations/"+pending.getId()).with(user(email).roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}")).andExpect(status().isOk());
        assertEquals(0,getJson("/api/dashboard/statistics").get("pendingReviews").asInt());assertEquals(0,getJson("/api/dashboard/reviews").size());
        var foreignStats=mapper.readTree(mvc.perform(get("/api/dashboard/statistics").with(user("other@example.test").roles("TEACHER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0,foreignStats.get("teacherMaterials").asInt());assertEquals(0,foreignStats.get("activityRecords").asInt());assertEquals(0,foreignStats.get("learningProgress").asDouble());assertEquals(1,foreignStats.get("pendingReviews").asInt());
    }

    @Test void automaticProgressionHistoryGradeIsolationAndRepeatedReadsAreStable() throws Exception {
        upload("GRADE",List.of(),"Grade 1",null,201);upload("GRADE",List.of(),"Grade 1",null,201);upload("GRADE",List.of(),"Grade 2",null,201);
        var queue=getJson("/api/dashboard/lessons?grade=Grade 1");assertEquals(2,queue.size());
        String current=queue.get(0).get("id").asText(),next=queue.get(1).get("id").asText();
        assertEquals("lesson.pdf",queue.get(0).get("originalFileName").asText());
        change(current,first.getId(),"COMPLETED",200);change(current,second.getId(),"IN_PROGRESS",200);
        assertEquals(current,getJson("/api/dashboard/lessons?grade=Grade 1").get(0).get("id").asText());assertEquals(0,getJson("/api/dashboard/lessons/history").size());
        change(current,second.getId(),"COMPLETED",200);
        for(int i=0;i<3;i++) {
            assertEquals(next,getJson("/api/dashboard/lessons?grade=Grade 1").get(0).get("id").asText());
            var history=getJson("/api/dashboard/lessons/history?grade=Grade 1");assertEquals(1,history.size());assertEquals(current,history.get(0).get("id").asText());assertFalse(history.get(0).get("completedAt").isNull());assertEquals(2,history.get(0).get("completed").asInt());
        }
        change(next,first.getId(),"COMPLETED",200);change(next,second.getId(),"COMPLETED",200);
        assertEquals(0,getJson("/api/dashboard/lessons?grade=Grade 1").size());assertEquals(next,getJson("/api/dashboard/lessons/history?grade=Grade 1").get(0).get("id").asText());
        assertEquals(1,getJson("/api/dashboard/lessons?grade=Grade 2").size());assertEquals(0,getJson("/api/dashboard/lessons/history?grade=Grade 2").size());
        assertEquals(5,mongo.getCollection("student_assignments").countDocuments());
        mvc.perform(get("/api/dashboard/lessons/history").with(user("other@example.test").roles("TEACHER"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/dashboard/lessons/history")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard/lessons/history").with(user(email).roles("STUDENT"))).andExpect(status().isForbidden());
    }
    @Test void zeroRecipientsAndMissingAssignmentsNeverAutoComplete() throws Exception {
        String material=upload("STUDENTS",List.of(first.getId()),"",null,201).get("id").asText();
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(material)),new org.springframework.data.mongodb.core.query.Update().set("assignedStudentIds",List.of()),ContentAsset.class);
        var queue=getJson("/api/dashboard/lessons");assertEquals(1,queue.size());assertEquals(0,queue.get(0).get("assigned").asInt());assertEquals(0,getJson("/api/dashboard/lessons/history").size());
        mvc.perform(post("/api/dashboard/lessons/"+material+"/complete").with(user(email).roles("TEACHER")).with(csrf())).andExpect(status().isConflict());
        assertFalse(getJson("/api/dashboard/lessons/"+material).get("completed").asBoolean());
    }
}
