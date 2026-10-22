package lk.smartplay.adaptive.controller;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.mongodb.core.MongoTemplate;
import com.fasterxml.jackson.databind.*;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;
import lk.smartplay.adaptive.service.*;
import lk.smartplay.adaptive.dto.LivePathwayResponse;

@org.springframework.test.context.event.RecordApplicationEvents
@SpringBootTest(properties="smartplay.milestones.automatic=false") @AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="MONGODB_TEST_URI",matches=".+")
class RecommendationMilestonePersistenceTest {
    static final String DATABASE="smartplay_milestones_test_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void props(DynamicPropertyRegistry p){p.add("spring.data.mongodb.uri",()->System.getenv("MONGODB_TEST_URI"));p.add("spring.data.mongodb.database",()->DATABASE);p.add("smartplay.content.storage-dir",()->System.getProperty("java.io.tmpdir")+"/"+DATABASE);}
    @Autowired org.springframework.test.context.event.ApplicationEvents events;
    @Autowired MockMvc mvc;@Autowired MongoTemplate mongo;@Autowired ObjectMapper mapper;
    @Autowired TeacherAccountRepository accounts;@Autowired StudentRepository students;@Autowired LearningRecommendationRepository recommendations;
    @Autowired RecommendationMilestoneService service;@Autowired StudentAssignmentService assignments;@Autowired AssessmentResultService worksheetResults;
    @Autowired AssessmentResultRepository worksheetRepository;
    @MockitoBean AIRecommendationClient ai;
    String email="milestones@example.test",owner;Student first,second;
    @BeforeEach void setup(){assertEquals(DATABASE,mongo.getDb().getName());owner=accounts.save(new TeacherAccount("Teacher",email,"unused")).getId();first=students.save(new Student("First","Grade 1",owner));second=students.save(new Student("Second","Grade 1",owner));}
    @AfterEach void clean() throws Exception {mongo.getDb().drop();var dir=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),DATABASE);if(java.nio.file.Files.exists(dir))try(var paths=java.nio.file.Files.walk(dir)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(p);}}
    JsonNode post(String path,Object body,int status) throws Exception{return mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(user(email).roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());}
    String endpoint(Student s){return "/api/recommendations/student/"+s.getId()+"/milestones";}
    void complete(int count,Student s) throws Exception {int offset=(int)worksheetRepository.findByStudentIdOrderByAssessedAtDesc(s.getId()).stream().filter(AssessmentResult::isWorksheet).count();for(int i=0;i<count;i++){
        var response=mvc.perform(multipart("/api/content").file(new MockMultipartFile("file","lesson.pdf","application/pdf","%PDF-1.7 isolated milestone".getBytes())).param("title","Development lesson").param("grade","Grade 1").param("category","Worksheet").param("assignmentMode","STUDENTS").param("studentIds",s.getId()).with(user(email).roles("TEACHER")).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse();
        String material=mapper.readTree(response.getContentAsString()).get("id").asText();assignments.update(material,s.getId(),owner,"COMPLETED");
        worksheetResults.createWorksheet(s.getId(),owner,"score:"+material,material,"Development lesson","Mathematics",java.time.LocalDate.now().minusDays(offset+i),Math.min(10,6.0+offset+i),"Observed score for lesson "+(offset+i+1));
    }}
    JsonNode evidence(Student s) throws Exception {var fixture=mapper.readTree(getClass().getResourceAsStream("/fixtures/st001-source-records.json"));String base="/api/students/"+s.getId();for(var row:fixture.get("assessments"))post(base+"/assessments",row,201);for(var row:fixture.get("activities"))post(base+"/records",row,200);post(base+"/observations",fixture.get("observation"),201);for(var row:fixture.get("history"))post(base+"/history",row,201);return fixture;}
    void prediction(boolean match){when(ai.selectPathway(any())).thenAnswer(call->{Map<String,Object> profile=call.getArgument(0);List<?> needs=(List<?>)profile.get("needs");Map<?,?> need=(Map<?,?>)needs.get(0);return new LivePathwayResponse("Successful",Map.of("Successful",.8,"Waiting",.1,"Needs Practice",.1),"unchanged-model-hash",match?"Recommendation available":"No suitable validated activity",match?List.of(new LivePathwayResponse.Activity("CURRICULUM-1","Math","Number Recognition","Recognize numbers","Number cards","Beginner","Number sequence knowledge",(String)need.get("assessment_id"),"Persisted measured number evidence")):List.of());});}
    @Test void independentMilestonesAtThreeSixNineAreDurableAndUseAll28PersistedFeatures() throws Exception {
        var fixture=evidence(first);prediction(true);complete(2,first);post(endpoint(first),Map.of(),200);verifyNoInteractions(ai);assertEquals(0,service.progress(owner,first.getId()).milestones().size());
        complete(1,first);var result=post(endpoint(first),Map.of(),200);assertEquals(1,result.get("milestones").size());assertEquals("GENERATED",result.get("milestones").get(0).get("status").asText());
        var performance=service.progress(owner,first.getId()).milestones().get(0).threeLessonPerformance;assertEquals(3,performance.lessons().size());assertEquals(70.0,performance.averagePercentage());assertEquals(20.0,performance.changePercentagePoints());assertEquals("Improving",performance.trend());
        assertEquals(0,service.progress(owner,second.getId()).completedLessons());
        complete(3,first);post(endpoint(first),Map.of(),200);complete(3,first);post(endpoint(first),Map.of(),200);post(endpoint(first),Map.of(),200);
        assertTrue(events.stream(LessonCompletionEvent.class).allMatch(e->e.ownerId().equals(owner)&&e.studentId().equals(first.getId())));assertEquals(9,events.stream(LessonCompletionEvent.class).count());
        var progress=service.progress(owner,first.getId());assertEquals(9,progress.completedLessons());assertEquals(12,progress.nextMilestone());assertEquals(3,progress.milestones().size());assertEquals(3,recommendations.count());
        org.mockito.ArgumentCaptor<Map<String,Object>> capture=org.mockito.ArgumentCaptor.captor();verify(ai,times(3)).selectPathway(capture.capture());
        var features=(Map<?,?>)capture.getAllValues().get(0).get("features");assertEquals(LiveFeatureService.FEATURE_ORDER,new ArrayList<>(features.keySet()));
        fixture.get("expectedFeatures").properties().forEach(e->{if(e.getValue().isNumber())assertEquals(e.getValue().asDouble(),((Number)features.get(e.getKey())).doubleValue(),1e-8);else assertEquals(e.getValue().asText(),features.get(e.getKey()));});
        for(var r:recommendations.findAll()){assertEquals(owner,r.getTeacherOwnerId());assertTrue(Set.of(3,6,9).contains(r.getCompletionMilestone()));assertEquals(LearningRecommendation.Status.PENDING,r.getStatus());assertEquals("unchanged-model-hash",r.getModelVersion());assertNotNull(r.getFeaturesGeneratedAt());assertFalse(r.getSourceEvidenceIds().isEmpty());}
    }
    @Test void insufficientDataIsPersistedAndCanBeRetriedAfterRealEvidenceArrives() throws Exception {
        complete(3,first);var state=post(endpoint(first),Map.of(),200);assertEquals("INSUFFICIENT_DATA",state.get("milestones").get(0).get("status").asText());assertTrue(state.toString().contains("classroom activities"));verifyNoInteractions(ai);
        evidence(first);prediction(true);post(endpoint(first),Map.of(),200);assertEquals("GENERATED",service.progress(owner,first.getId()).milestones().get(0).status);assertEquals(1,recommendations.count());
    }
    @Test void scoresForUncompletedAssignmentsAndDuplicateScoreRowsDoNotInflateMilestoneCount() throws Exception {
        complete(1,first);
        var firstResult=worksheetRepository.findByStudentIdOrderByAssessedAtDesc(first.getId()).get(0);
        worksheetResults.createWorksheet(first.getId(),owner,"duplicate-correction",firstResult.getMaterialId(),"Development lesson","Mathematics",java.time.LocalDate.now(),9,"Corrected result");
        assertEquals(1,service.progress(owner,first.getId()).completedLessons());
        for(int i=0;i<2;i++) {
            var response=mvc.perform(multipart("/api/content").file(new MockMultipartFile("file","lesson.pdf","application/pdf","%PDF-1.7 score without completion".getBytes())).param("title","Scored but unfinished "+i).param("grade","Grade 1").param("category","Worksheet").param("assignmentMode","STUDENTS").param("studentIds",first.getId()).with(user(email).roles("TEACHER")).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse();
            String material=mapper.readTree(response.getContentAsString()).get("id").asText();
            worksheetResults.createWorksheet(first.getId(),owner,"uncompleted-score:"+material,material,"Scored but unfinished "+i,"Mathematics",java.time.LocalDate.now(),8,"Score alone is not completion");
        }
        assertEquals(1,service.progress(owner,first.getId()).completedLessons());
        verifyNoInteractions(ai);
    }
    @Test void noMatchAndInferenceFailureNeverFabricateAPathway() throws Exception {
        evidence(first);complete(3,first);when(ai.selectPathway(any())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"Inference unavailable"));post(endpoint(first),Map.of(),200);assertEquals("ERROR",service.progress(owner,first.getId()).milestones().get(0).status);
        reset(ai);prediction(false);post(endpoint(first),Map.of(),200);post(endpoint(first),Map.of(),200);assertEquals("NO_MATCH",service.progress(owner,first.getId()).milestones().get(0).status);assertEquals(0,recommendations.count());
    }
    @Test void ownershipTeacherRoleCsrfAndIncompleteAssignmentsAreEnforced() throws Exception {
        complete(2,first);var material=new ContentAsset(owner,"Unfinished","Worksheet","","lesson.pdf","stored.pdf","application/pdf",10);material.assign("STUDENTS","",List.of(first.getId()));material.trackIndividualAssignments();mongo.save(material);assignments.synchronize(material);assertEquals(2,service.progress(owner,first.getId()).completedLessons());
        accounts.save(new TeacherAccount("Other","foreign@example.test","unused"));mvc.perform(get(endpoint(first)).with(user("foreign@example.test").roles("TEACHER"))).andExpect(status().isNotFound());mvc.perform(get(endpoint(first)).with(user(email).roles("STUDENT"))).andExpect(status().isForbidden());mvc.perform(get(endpoint(first))).andExpect(status().isUnauthorized());mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint(first)).with(user(email).roles("TEACHER"))).andExpect(status().isForbidden());
        post(endpoint(first),Map.of("features",Map.of("Average_Score",100)),400);
        verifyNoInteractions(ai);
    }

    @Test void concurrentEvaluationAndCrashRecoveryNeverDuplicateSuccessfulRecommendations() throws Exception {
        evidence(first);complete(3,first);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(ai.selectPathway(any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));Map<String,Object> p=call.getArgument(0);var need=(Map<?,?>)((List<?>)p.get("needs")).get(0);return new LivePathwayResponse("Successful",Map.of("Successful",.8,"Waiting",.1,"Needs Practice",.1),"same-model","Recommendation available",List.of(new LivePathwayResponse.Activity("CURRICULUM-1","Math","Number Recognition","Recognize numbers","Number cards","Beginner","Number sequence knowledge",(String)need.get("assessment_id"),"Persisted evidence"),new LivePathwayResponse.Activity("CURRICULUM-2","Math","Number practice","Recognize numbers","Number chart","Beginner","Number sequence knowledge",(String)need.get("assessment_id"),"Persisted evidence")));});
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var running=pool.submit(()->service.evaluate(owner,first.getId()));assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("GENERATING",service.evaluate(owner,first.getId()).milestones().get(0).status);
            release.countDown();assertEquals("GENERATED",running.get(10,java.util.concurrent.TimeUnit.SECONDS).milestones().get(0).status);
            assertEquals(2,recommendations.count());
            recommendations.deleteById(recommendations.findAll().get(1).getId()); // Simulate a process stopping after one planned activity was saved.
            assertEquals(1,recommendations.count());
            mongo.updateMulti(new org.springframework.data.mongodb.core.query.Query(),new org.springframework.data.mongodb.core.query.Update().set("status","ERROR"),RecommendationMilestone.class);
            assertEquals("GENERATED",service.evaluate(owner,first.getId()).milestones().get(0).status);
            verify(ai,times(1)).selectPathway(any());assertEquals(2,recommendations.count());
        } finally {release.countDown();pool.shutdownNow();}
    }
}
