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

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="MONGODB_TEST_URI",matches=".+")
@EnabledIfEnvironmentVariable(named="AI_TEST_URL",matches=".+")
class PersistedLiveWorkflowTest {
    static final String DATABASE = "smartplay_stored_workflow_test_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.data.mongodb.uri", () -> System.getenv("MONGODB_TEST_URI"));
        properties.add("spring.data.mongodb.database", () -> DATABASE);
        properties.add("smartplay.ai.base-url", () -> System.getenv("AI_TEST_URL"));
        properties.add("smartplay.content.storage-dir", () -> System.getProperty("java.io.tmpdir") + "/" + DATABASE);
    }
    @Autowired lk.smartplay.adaptive.service.StudentAssignmentService assignmentService;
    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired TeacherAccountRepository accounts;
    @Autowired StudentRepository students;
    @Autowired ContentAssetRepository materials;
    @Autowired LearningRecommendationRepository recommendations;
    @Autowired lk.smartplay.adaptive.service.AssessmentResultService worksheetResults;
    @Autowired ObjectMapper mapper;
    final String email = "live-workflow@example.test";
    String postJson(String path, Object body, int expected) throws Exception {
        return mvc.perform(post(path).with(user(email).roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    @AfterEach void cleanup() throws Exception {
        mongo.getDb().drop();
        var directory = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), DATABASE);
        if (java.nio.file.Files.exists(directory)) {
            try (var paths = java.nio.file.Files.walk(directory)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path); }
        }
    }
    @Test void fullAuthenticatedWorkflowUsesOnlyPersistedRecordsAndAll28Features() throws Exception {
        assertEquals(DATABASE, mongo.getDb().getName());
        var teacher = accounts.save(new TeacherAccount("Development teacher",email,"unused-in-mocked-login"));
        var student = students.save(new Student("Development learner","Grade 1",teacher.getId()));
        String root = "/api/students/" + student.getId();
        JsonNode fixture;
        try (var source = getClass().getResourceAsStream("/fixtures/st001-source-records.json")) { fixture = mapper.readTree(source); }
        // No feature vectors are accepted by the public live API.
        postJson("/api/recommendations/student/"+student.getId()+"/pathway",Map.of("features",fixture.get("expectedFeatures")),400);
        postJson("/api/recommendations/student/"+student.getId()+"/pathway",Map.of(),422);
        for (var result : fixture.get("assessments")) postJson(root+"/assessments",result,201);
        assertEquals(2,mongo.getCollection("assessment_results").countDocuments());
        assertEquals(0,mongo.getCollection("learning_records").countDocuments());
        for (var record : fixture.get("activities")) postJson(root+"/records",record,200);
        postJson(root+"/observations",fixture.get("observation"),201);
        for (var lesson : fixture.get("history")) postJson(root+"/history",lesson,201);
        assertEquals(2,mongo.getCollection("assessment_results").countDocuments());
        assertEquals(fixture.get("activities").size(),mongo.getCollection("learning_records").countDocuments());
        assertEquals(1,mongo.getCollection("teacher_observations").countDocuments());
        assertEquals(fixture.get("history").size(),mongo.getCollection("historical_lessons").countDocuments());
        // Owner information is derived server-side and is not exposed in JSON.
        mvc.perform(get(root+"/observations").with(user(email).roles("TEACHER"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].ownerId").doesNotExist());
        mvc.perform(get(root+"/history").with(user(email).roles("TEACHER"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].lessonId").exists());
        var snapshot = mapper.readTree(mvc.perform(get("/api/recommendations/student/"+student.getId()+"/features").with(user(email).roles("TEACHER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("features");
        assertEquals(28,snapshot.size());
        fixture.get("expectedFeatures").properties().forEach(entry -> {
            if (entry.getValue().isNumber()) assertEquals(entry.getValue().doubleValue(),snapshot.get(entry.getKey()).doubleValue(),1e-8,entry.getKey());
            else assertEquals(entry.getValue().asText(),snapshot.get(entry.getKey()).asText(),entry.getKey());
        });
        String endpoint = "/api/recommendations/student/"+student.getId()+"/pathway";
        var live = mapper.readTree(postJson(endpoint,Map.of(),200));
        assertEquals("Successful",live.get("predictedOutcome").asText());
        assertEquals(3,live.get("probabilities").size()); assertEquals(1,live.get("recommendations").size());
        var recommendation = live.get("recommendations").get(0);
        assertEquals("LES021",recommendation.get("lessonId").asText()); assertEquals("PENDING",recommendation.get("status").asText());
        String recommendationId = recommendation.get("id").asText();
        postJson(endpoint,Map.of(),200); assertEquals(1,recommendations.count());
        var pdf = new MockMultipartFile("file","numbers.pdf","application/pdf","%PDF-1.7 development fixture".getBytes());
        var upload = mapper.readTree(mvc.perform(multipart("/api/content").file(pdf).param("title","Number chart").param("category","Worksheet")
                .with(user(email).roles("TEACHER")).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String materialId = upload.get("id").asText();
        postJson("/api/recommendations/"+recommendationId+"/assign",Map.of("materialId",materialId),409);
        mvc.perform(patch("/api/recommendations/"+recommendationId).with(user(email).roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\",\"activityTitle\":\"Teacher reviewed number chart\",\"teacherFeedback\":\"Use guided practice\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activityTitle").value("Teacher reviewed number chart"));
        postJson("/api/recommendations/"+recommendationId+"/assign",Map.of("materialId",materialId),200);
        assertEquals(List.of(student.getId()),materials.findById(materialId).orElseThrow().getAssignedStudentIds());
        // Subsequent measured result updates activity aggregates without overwriting assessments.
        postJson(root+"/records",Map.of("domain","Number Recognition","score",9,"maxScore",10,"attempts",1,"timeTakenMinutes",6,"completionStatus","COMPLETED"),200);
        var updated = mapper.readTree(mvc.perform(get("/api/recommendations/student/"+student.getId()+"/features").with(user(email).roles("TEACHER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(fixture.get("activities").size()+1,updated.get("features").get("Total_Activities").asInt());
        assertEquals(2,mongo.getCollection("assessment_results").countDocuments());
        // An actual 3-assignment milestone passes the unchanged RF through the protected Python API.
        assignmentService.update(materialId,student.getId(),teacher.getId(),"COMPLETED");
        worksheetResults.createWorksheet(student.getId(),teacher.getId(),"milestone-score:"+materialId,materialId,"Number chart","Number Recognition",java.time.LocalDate.now(),8,"Completed number chart worksheet");
        for(int i=0;i<2;i++) {
            var extra=mvc.perform(multipart("/api/content").file(new MockMultipartFile("file","lesson.pdf","application/pdf","%PDF-1.7 milestone fixture".getBytes())).param("title","Development milestone lesson").param("category","Worksheet").param("grade","Grade 1").param("assignmentMode","STUDENTS").param("studentIds",student.getId()).with(user(email).roles("TEACHER")).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse();
            String extraId=mapper.readTree(extra.getContentAsString()).get("id").asText();assignmentService.update(extraId,student.getId(),teacher.getId(),"COMPLETED");
            worksheetResults.createWorksheet(student.getId(),teacher.getId(),"milestone-score:"+extraId,extraId,"Development milestone lesson","Number Recognition",java.time.LocalDate.now(),7+i,"Completed worksheet assessment");
        }
        String milestones="/api/recommendations/student/"+student.getId()+"/milestones";
        var evaluated=mapper.readTree(postJson(milestones,Map.of(),200));assertEquals(3,evaluated.get("completedLessons").asInt());assertEquals(6,evaluated.get("nextMilestone").asInt());
        var milestone=evaluated.get("milestones").get(0);assertEquals("NO_MATCH",milestone.get("status").asText());assertEquals(28,milestone.get("featureSnapshot").get("features").size());assertTrue(milestone.get("modelVersion").asText().length()>20);
        postJson(milestones,Map.of(),200);assertEquals(1,recommendations.count());
        postJson(root+"/history",Map.of("lessonId","LES021","sequenceNumber",5,"completionStatus","Completed"),201);
        var afterCompletion = mapper.readTree(postJson(endpoint,Map.of(),200));
        assertEquals("No suitable validated activity",afterCompletion.get("status").asText());
        assertTrue(afterCompletion.get("recommendations").isEmpty());
        // Another teacher and a student-role principal cannot obtain this profile.
        accounts.save(new TeacherAccount("Other","other-live@example.test","unused"));
        mvc.perform(get("/api/recommendations/student/"+student.getId()+"/features").with(user("other-live@example.test").roles("TEACHER"))).andExpect(status().isNotFound());
        mvc.perform(get(root+"/observations").with(user(email).roles("STUDENT"))).andExpect(status().isForbidden());
    }
}
