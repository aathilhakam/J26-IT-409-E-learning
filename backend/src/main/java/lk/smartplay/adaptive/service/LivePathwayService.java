package lk.smartplay.adaptive.service;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.dto.LivePathwayRequest;
import lk.smartplay.adaptive.dto.LivePathwayResponse;
import lk.smartplay.adaptive.model.LearningRecommendation;
import lk.smartplay.adaptive.repository.*;

@Service
public class LivePathwayService {
    private final StudentService students;
    private final AssessmentResultRepository assessments;
    private final LearningRecordRepository records;
    private final ClassroomActivityRepository classroomActivities;
    private final LearningRecommendationRepository recommendations;
    private final ContentAssetRepository materials;
    private final AIRecommendationClient ai;
    private final LiveFeatureService features;

    public LivePathwayService(StudentService students, AssessmentResultRepository assessments, LearningRecordRepository records,
            LearningRecommendationRepository recommendations, ContentAssetRepository materials, AIRecommendationClient ai, LiveFeatureService features) {
        this(students, assessments, records, null, recommendations, materials, ai, features);
    }
    @Autowired
    public LivePathwayService(StudentService students, AssessmentResultRepository assessments, LearningRecordRepository records,
            ClassroomActivityRepository classroomActivities, LearningRecommendationRepository recommendations,
            ContentAssetRepository materials, AIRecommendationClient ai, LiveFeatureService features) {
        this.students = students; this.assessments = assessments; this.records = records;
        this.classroomActivities = classroomActivities;
        this.recommendations = recommendations; this.materials = materials; this.ai = ai; this.features = features;
    }

    public record Result(String status, String predictedOutcome, List<LearningRecommendation> recommendations, Map<String,Double> probabilities,String modelVersion) {
        public Result(String status, String predictedOutcome, List<LearningRecommendation> recommendations) { this(status, predictedOutcome, recommendations, Map.of(),null); }
        public Result(String status,String predictedOutcome,List<LearningRecommendation> recommendations,Map<String,Double> probabilities) { this(status,predictedOutcome,recommendations,probabilities,null); }
    }

    public LiveFeatureService.Snapshot snapshot(String studentId, String ownerId) { return features.generate(studentId, ownerId); }

    public Result generateStored(String studentId, String ownerId) {
        var snapshot = features.generate(studentId, ownerId);
        return generate(studentId, ownerId, new LivePathwayRequest(snapshot.features(), snapshot.demonstratedSkills(), snapshot.completedLessonIds(), Map.of()));
    }

    public Result generateForMilestone(String studentId,String ownerId,int milestone,LiveFeatureService.Snapshot snapshot,List<String> completedIds,List<String> evidenceIds,Map<String,Object> context,java.util.function.Consumer<Result> persistPlan) {
        ai.requireAuthenticatedInference();
        var completed=new LinkedHashSet<>(snapshot.completedLessonIds());completed.addAll(completedIds);
        return generate(studentId,ownerId,new LivePathwayRequest(snapshot.features(),snapshot.demonstratedSkills(),List.copyOf(completed),Map.of()),
            "milestone:"+ownerId+":"+studentId+":"+milestone,milestone,snapshot,evidenceIds,withMilestoneMode(context),persistPlan);
    }
    private Map<String,Object> withMilestoneMode(Map<String,Object> context) {
        var values=new LinkedHashMap<String,Object>();if(context!=null)values.putAll(context);values.put("pathway_mode","THREE_LESSON_MILESTONE");return values;
    }
    public Result generate(String studentId, String ownerId, LivePathwayRequest request) {
        return generate(studentId,ownerId,request,null,null,null,List.of(),Map.of(),null);
    }
    private Result generate(String studentId,String ownerId,LivePathwayRequest request,String milestoneKey,Integer milestone,LiveFeatureService.Snapshot snapshot,List<String> evidenceIdsForSnapshot,Map<String,Object> context,java.util.function.Consumer<Result> persistPlan) {
        var student = students.findById(studentId, ownerId);
        if (!student.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot recommend for an archived student");
        if (student.getGrade() == null || student.getGrade().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Student grade is required for curriculum matching");
        }
        // Evidence comes from the owned student's persisted collections, not caller-invented needs.
        record Evidence(String id, String domain, double percentage, java.time.Instant at) {}
        List<Evidence> evidence = new ArrayList<>();
        for (var result : assessments.findByStudentIdOrderByAssessedAtDesc(studentId)) {
            String domain=result.isWorksheet()?firstText(result.getSubject(),result.getWorksheetTitle()):result.getAssessmentType();
            if (domain != null && !domain.isBlank()
                    && Double.isFinite(result.getScore()) && Double.isFinite(result.getMaxScore()) && result.getMaxScore() > 0) {
                evidence.add(new Evidence("assessment:" + result.getId(), domain,
                        result.getScore() * 100 / result.getMaxScore(), result.getAssessedAt()));
            }
        }
        for (var record : records.findByStudentIdOrderByAssessedAtDesc(studentId)) {
            if (record.getDomain() != null && !record.getDomain().isBlank() && Double.isFinite(record.getScore()) && Double.isFinite(record.getMaxScore()) && record.getMaxScore() > 0) {
                evidence.add(new Evidence("record:" + record.getId(), record.getDomain(),
                        record.getScore() * 100 / record.getMaxScore(), record.getAssessedAt()));
            }
        }
        if (classroomActivities != null) for (var activity : classroomActivities.findByStudentIdOrderByAssessedAtDesc(studentId)) {
            if (activity.getDomain() != null && !activity.getDomain().isBlank() && Double.isFinite(activity.getScore()) && Double.isFinite(activity.getMaxScore()) && activity.getMaxScore() > 0) {
                evidence.add(new Evidence("classroom_activity:" + activity.getId(), activity.getDomain(),
                        activity.getScore() * 100 / activity.getMaxScore(), activity.getAssessedAt()));
            }
        }
        evidence.sort(Comparator.comparing(Evidence::at, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Evidence::id));
        Map<String, Evidence> latest = new LinkedHashMap<>();
        Map<String,Evidence> previous = new LinkedHashMap<>();
        evidence.forEach(item -> {String domain=item.domain().trim().toLowerCase(Locale.ROOT);if(latest.putIfAbsent(domain,item)!=null)previous.putIfAbsent(domain,item);});
        List<Map<String, Object>> needs = new ArrayList<>();
        for (var item : latest.values()) {
            if (needs.size() == 200) break;
            Map<String,Object> need=new LinkedHashMap<>(Map.of("assessment_id",item.id(),"domain",item.domain(),"score_percentage",item.percentage()));
            var prior=previous.get(item.domain().trim().toLowerCase(Locale.ROOT));
            if(prior!=null && prior.at()!=null && item.at()!=null && item.at().isAfter(prior.at()))need.put("score_change",item.percentage()-prior.percentage());
            needs.add(need);
        }
        if (needs.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No documented assessment or activity evidence for curriculum matching");
        Map<String, Object> profile = new LinkedHashMap<>(Map.of("features", request.features(), "grade", student.getGrade(), "needs", needs,
                "demonstrated_skills", request.demonstratedSkills(), "completed_lesson_ids", request.completedLessonIds(), "limit", 3));
        profile.putAll(context);
        if(request.features().containsKey("Learning_Difficulty")) profile.put("recorded_difficulty",request.features().get("Learning_Difficulty"));
        if(request.features().containsKey("Test_Improvement")) profile.put("measured_improvement",request.features().get("Test_Improvement"));
        // Material links are explicit teacher-provided references; never infer a file from a title.
        for (var materialId : request.materialReferences().values()) {
            materials.findByIdAndOwnerId(materialId, ownerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Referenced material not found"));
        }
        LivePathwayResponse prediction = ai.selectPathway(profile);
        // Enforce the score-based business policy at the persistence boundary too.
        // The model's raw class and probabilities remain intact, but a faulty or stale
        // AI response cannot create a remedial pathway for a score-based Successful outcome.
        if("Successful".equals(context.get("application_outcome"))&&!prediction.activities().isEmpty())
            prediction=new LivePathwayResponse(prediction.outcome(),prediction.probabilities(),prediction.modelVersion(),
                "Verified overall performance is at least 80%; no remedial pathway was generated.",List.of(),"NONE",List.of());
        Set<String> evidenceIds = new HashSet<>();
        needs.forEach(need -> evidenceIds.add((String) need.get("assessment_id")));
        Set<String> lessonIds = new HashSet<>();
        for (var activity : prediction.activities()) {
            if (request.completedLessonIds().contains(activity.lessonId()) || activity.lessonId() == null || activity.lessonId().isBlank() || activity.topic() == null
                    || activity.subject() == null || activity.activity() == null || activity.objective() == null
                    || activity.reason() == null || !evidenceIds.contains(activity.evidenceId()) || !lessonIds.add(activity.lessonId())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI service returned invalid curriculum references");
            }
        }
        if (!lessonIds.containsAll(request.materialReferences().keySet())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Material references must name selected curriculum lessons");
        }
        String key = milestoneKey==null?fingerprint(profile, request.materialReferences(), prediction.modelVersion()):milestoneKey;
        List<LearningRecommendation> planned = new ArrayList<>();
        for (var activity : prediction.activities()) {
            var existing = recommendations.findByStudentIdAndRequestKeyAndLessonId(studentId, key, activity.lessonId());
            if (existing.isPresent()) { planned.add(existing.get()); continue; }
            var recommendation = new LearningRecommendation(studentId, activity.subject(), activity.topic(),
                    activity.activity() + " Objective: " + activity.objective(), activity.reason(),
                    prediction.probabilities().get(prediction.outcome()));
            recommendation.setStudentGrade(student.getGrade());
            recommendation.liveDetails(activity.lessonId(), prediction.modelVersion(), prediction.outcome(), prediction.pathwayType(),
                    prediction.probabilities(), activity.evidenceId(), key, request.materialReferences().get(activity.lessonId()));
            recommendation.curriculumDetails(activity.objective(),activity.difficulty(),activity.prerequisites());
            if(milestone!=null) recommendation.milestoneDetails(ownerId,milestone,snapshot.generatedAt(),evidenceIdsForSnapshot);
            planned.add(recommendation);
        }
        String resultStatus=planned.isEmpty()?prediction.status():"Recommendation available";
        if(persistPlan!=null)persistPlan.accept(new Result(resultStatus,prediction.outcome(),planned,prediction.probabilities(),prediction.modelVersion()));
        List<LearningRecommendation> saved=new ArrayList<>();
        for(var recommendation:planned) {
            var existing=recommendations.findByStudentIdAndRequestKeyAndLessonId(studentId,key,recommendation.getLessonId());
            if(existing.isPresent()){saved.add(existing.get());continue;}
            try { saved.add(milestone==null?recommendations.save(recommendation):recommendations.insert(recommendation)); }
            catch(org.springframework.dao.DuplicateKeyException concurrent) {saved.add(recommendations.findByStudentIdAndRequestKeyAndLessonId(studentId,key,recommendation.getLessonId()).orElseThrow(()->concurrent));}
        }
        return new Result(saved.isEmpty() ? prediction.status() : "Recommendation available", prediction.outcome(), saved, prediction.probabilities(),prediction.modelVersion());
    }

    private static String firstText(String preferred,String fallback) {
        return preferred==null||preferred.isBlank()?fallback:preferred;
    }

    private String fingerprint(Map<String, Object> profile, Map<String, String> links, String version) {
        try {
            var mapper = new ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            byte[] bytes = mapper.writeValueAsBytes(Map.of("profile", profile, "materials", links, "model", version));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) { throw new IllegalStateException("Cannot identify recommendation request", error); }
    }

    public void assign(String recommendationId, String ownerId) { assign(recommendationId, ownerId, null); }

    public void assign(String recommendationId, String ownerId, String selectedMaterialId) {
        var recommendation = recommendations.findById(recommendationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recommendation not found"));
        var student = students.findById(recommendation.getStudentId(), ownerId);
        if (!student.isActive() || recommendation.getStatus() != LearningRecommendation.Status.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only approved recommendations for active students can be assigned");
        }
        String materialId = selectedMaterialId == null ? recommendation.getMaterialId() : selectedMaterialId;
        if (materialId == null || materialId.isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Recommendation has no linked material");
        var material = materials.findByIdAndOwnerId(materialId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Referenced material not found"));
        var ids = new LinkedHashSet<>(material.getAssignedStudentIds()); ids.add(student.getId());
        material.assign("STUDENTS", "", new ArrayList<>(ids));
        materials.save(material);
        recommendation.linkMaterial(materialId);
        recommendation.markAssigned();
        recommendations.save(recommendation);
    }
}
