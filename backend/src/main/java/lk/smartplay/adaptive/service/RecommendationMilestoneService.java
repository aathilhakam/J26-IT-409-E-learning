package lk.smartplay.adaptive.service;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.model.*;
import lk.smartplay.adaptive.repository.*;

@Service
public class RecommendationMilestoneService {
    private final StudentService students;
    private final StudentAssignmentRepository assignments;
    private final ContentAssetRepository materials;
    private final AssessmentResultRepository assessmentResults;
    private final LearningRecommendationRepository recommendations;
    private final LessonFeedbackRepository feedback;
    private final LivePathwayService pathways;
    private final MongoTemplate mongo;
    public RecommendationMilestoneService(StudentService students,StudentAssignmentRepository assignments,ContentAssetRepository materials,AssessmentResultRepository assessmentResults,
            LearningRecommendationRepository recommendations,LessonFeedbackRepository feedback,LivePathwayService pathways,MongoTemplate mongo) {
        this.students=students;this.assignments=assignments;this.materials=materials;this.assessmentResults=assessmentResults;this.recommendations=recommendations;this.feedback=feedback;this.pathways=pathways;this.mongo=mongo;
    }
    public record Progress(String studentId,int completedLessons,int lessonsTowardNext,int nextMilestone,List<RecommendationMilestone> milestones,List<LearningRecommendation> recommendations) {}
    private record EligibleLesson(StudentAssignment assignment,ContentAsset material,AssessmentResult score) {}
    private List<EligibleLesson> eligibleLessons(String owner,String student) {
        var owned=new HashMap<String,ContentAsset>();materials.findByOwnerIdOrderByUploadedAtDesc(owner).forEach(m->owned.put(m.getId(),m));
        var scores=new HashMap<String,AssessmentResult>();
        for(var result:assessmentResults.findByStudentIdOrderByAssessedAtDesc(student)) {
            if(!result.isWorksheet()||!owner.equals(result.getTeacherOwnerId())||!student.equals(result.getStudentId())
                    ||!"COMPLETED".equals(result.getCompletionStatus())||result.getMaterialId()==null||result.getMaterialId().isBlank()
                    ||!Double.isFinite(result.getScore())||!Double.isFinite(result.getMaxScore())||result.getMaxScore()<=0
                    ||result.getScore()<0||result.getScore()>result.getMaxScore())continue;
            scores.merge(result.getMaterialId(),result,(a,b)->{
                var aAt=a.getRecordedAt()==null?a.getAssessedAt():a.getRecordedAt();var bAt=b.getRecordedAt()==null?b.getAssessedAt():b.getRecordedAt();
                return aAt==null?b:bAt==null?a:(aAt.isAfter(bAt)?a:b);
            });
        }
        var unique=new HashMap<String,EligibleLesson>();
        for(var row:assignments.findByOwnerIdAndStudentIdAndActiveTrue(owner,student)) {
            var material=owned.get(row.getMaterialId());
            var score=scores.get(row.getMaterialId());
            if("COMPLETED".equals(row.getStatus())&&row.getCompletedAt()!=null&&material!=null&&score!=null
                    &&!"LIBRARY".equals(material.getAssignmentMode())&&material.getAssignedStudentIds().contains(student))
                unique.putIfAbsent(row.getMaterialId(),new EligibleLesson(row,material,score));
        }
        return unique.values().stream().sorted(Comparator.comparing((EligibleLesson lesson)->lesson.assignment().getCompletedAt()).thenComparing(lesson->lesson.assignment().getMaterialId())).toList();
    }
    public List<StudentAssignment> completed(String owner,String student) {return eligibleLessons(owner,student).stream().map(EligibleLesson::assignment).toList();}
    public Progress progress(String owner,String student) {
        students.findById(student,owner);
        int count=completed(owner,student).size();
        return new Progress(student,count,count%3,(count/3+1)*3,
            mongo.find(Query.query(Criteria.where("ownerId").is(owner).and("studentId").is(student)).with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,"milestone")),RecommendationMilestone.class),
            recommendations.findByStudentIdOrderByCreatedAtDesc(student).stream().filter(r->r.getCompletionMilestone()!=null).toList());
    }
    public void retryNoMatch(String owner,String student) {
        students.findById(student,owner);
        mongo.updateMulti(Query.query(Criteria.where("ownerId").is(owner).and("studentId").is(student).and("status").is("NO_MATCH")),new Update().set("status","READY").set("inferenceReady",false).set("plannedRecommendations",List.of()),RecommendationMilestone.class);
    }
    public Progress evaluate(String owner,String student) {
        var learner=students.findById(student,owner);
        if(!learner.isActive())throw new ResponseStatusException(HttpStatus.CONFLICT,"Cannot recommend for an archived student");
        var completed=eligibleLessons(owner,student);
        for(int threshold=3;threshold<=completed.size();threshold+=3)evaluateOne(owner,student,threshold,completed);
        return progress(owner,student);
    }
    private void evaluateOne(String owner,String student,int threshold,List<EligibleLesson> completed) {
        String id=owner+":"+student+":"+threshold,key="milestone:"+id;
        Instant now=Instant.now();
        var seed=Query.query(Criteria.where("_id").is(id));
        try {mongo.upsert(seed,new Update().setOnInsert("ownerId",owner).setOnInsert("studentId",student).setOnInsert("milestone",threshold)
            .setOnInsert("createdAt",now).setOnInsert("status","READY"),RecommendationMilestone.class);}catch(org.springframework.dao.DuplicateKeyException concurrent){}
        // A bounded lease permits crash recovery without parallel inference for this milestone.
        var claim=Query.query(new Criteria().andOperator(Criteria.where("_id").is(id),new Criteria().orOperator(
            Criteria.where("status").in("READY","INSUFFICIENT_DATA","ERROR"),new Criteria().andOperator(Criteria.where("status").is("GENERATING"),Criteria.where("leaseUntil").lt(now)))));
        var claimed=mongo.findAndModify(claim,new Update().set("status","GENERATING").set("updatedAt",now).set("leaseUntil",now.plusSeconds(300)),FindAndModifyOptions.options().returnNew(true),RecommendationMilestone.class);
        if(claimed==null)return;
        try {
            if(claimed.inferenceReady) {
                var saved=new ArrayList<LearningRecommendation>();
                for(var planned:claimed.plannedRecommendations) {
                    var prior=recommendations.findByStudentIdAndRequestKeyAndLessonId(student,key,planned.getLessonId());
                    if(prior.isPresent()){saved.add(prior.get());continue;}
                    try{saved.add(recommendations.insert(planned));}catch(org.springframework.dao.DuplicateKeyException race){saved.add(recommendations.findByStudentIdAndRequestKeyAndLessonId(student,key,planned.getLessonId()).orElseThrow(()->race));}
                }
                var result=new LivePathwayService.Result(saved.isEmpty()?"No suitable validated activity":"Recommendation available",claimed.predictedOutcome,saved,claimed.probabilities,claimed.modelVersion);
                finish(id,saved.isEmpty()?"NO_MATCH":"GENERATED",saved.isEmpty()?"No validated curriculum activity matches the documented evidence":"Recovered saved pathway; no duplicate inference",saved,result);return;
            }
            var existing=recommendations.findByStudentIdAndRequestKey(student,key);
            if(!existing.isEmpty()) {finish(id,"GENERATED","Recovered saved recommendation; no duplicate inference",existing,null);return;}
            var milestoneLessons=completed.subList(threshold-3,threshold);
            var performance=performance(milestoneLessons);
            mongo.updateFirst(seed,new Update().set("completedMaterialIds",completed.subList(0,threshold).stream().map(lesson->lesson.assignment().getMaterialId()).toList()).set("evidenceIds",completed.stream().map(lesson->"assignment:"+lesson.assignment().getId()).toList()).set("threeLessonPerformance",performance),RecommendationMilestone.class);
            var snapshot=pathways.snapshot(student,owner); // Exact unchanged 28-feature contract; errors stop before inference.
            var savedFeedback=feedback.findByOwnerIdAndStudentIdAndSubmissionStatusOrderBySubmittedAtDescUpdatedAtDesc(owner,student,"SUBMITTED");
            var sources=new LinkedHashSet<>(snapshot.sourceRecordIds());savedFeedback.forEach(f->sources.add("feedback:"+f.getId()));completed.forEach(lesson->{sources.add("assignment:"+lesson.assignment().getId());sources.add("assessment:"+lesson.score().getId());});
            var completedIds=new LinkedHashSet<String>();completed.forEach(lesson->completedIds.add(lesson.assignment().getMaterialId()));
            for(var prior:recommendations.findByStudentIdOrderByCreatedAtDesc(student))if(completedIds.contains(prior.getMaterialId()) && prior.getLessonId()!=null)completedIds.add(prior.getLessonId());
            var context=new ArrayList<Map<String,Object>>();
            savedFeedback.stream().limit(180).forEach(f->{Map<String,Object> row=new LinkedHashMap<>();row.put("evidence_id","feedback:"+f.getId());row.put("improvement",f.getImprovement()==null?"":f.getImprovement());row.put("next_step",f.getFeedback()==null?"":f.getFeedback());context.add(row);});
            for(var lesson:milestoneLessons){var score=lesson.score();Map<String,Object> row=new LinkedHashMap<>();row.put("evidence_id","assessment:"+score.getId());row.put("improvement",lesson.material().getTitle()+" score: "+score.getScore()+"/"+score.getMaxScore()+" ("+score.getPercentage()+"%)");row.put("next_step",score.getNotes()==null?"":score.getNotes());context.add(row);}
            mongo.updateFirst(seed,new Update().set("featureSnapshot",snapshot).set("completedMaterialIds",completed.subList(0,threshold).stream().map(lesson->lesson.assignment().getMaterialId()).toList()).set("evidenceIds",List.copyOf(sources)).set("threeLessonPerformance",performance),RecommendationMilestone.class);
            var result=pathways.generateForMilestone(student,owner,threshold,snapshot,List.copyOf(completedIds),List.copyOf(sources),Map.of("teacher_context",context),plan -> {
                plan.recommendations().forEach(recommendation->recommendation.milestonePerformance(performance));
                mongo.updateFirst(seed,new Update().set("inferenceReady",true).set("plannedRecommendations",plan.recommendations())
                    .set("predictedOutcome",plan.predictedOutcome()).set("probabilities",plan.probabilities()).set("modelVersion",plan.modelVersion()),RecommendationMilestone.class);
            });
            finish(id,result.recommendations().isEmpty()?"NO_MATCH":"GENERATED",result.recommendations().isEmpty()?"No validated curriculum activity matches the documented need, grade and prerequisites": "Awaiting teacher review",result.recommendations(),result);
        }catch(ResponseStatusException error){fail(id,error.getStatusCode().value()==422?"INSUFFICIENT_DATA":"ERROR",error.getReason()==null?"Required learning evidence is unavailable":error.getReason());}
        catch(RuntimeException error){fail(id,"ERROR","Could not finish evaluation. Retry this milestone safely.");}
    }
    private void finish(String id,String status,String message,List<LearningRecommendation> saved,LivePathwayService.Result result) {
        var update=new Update().set("status",status).set("message",message).set("updatedAt",Instant.now()).unset("leaseUntil").set("recommendationIds",saved.stream().map(LearningRecommendation::getId).toList());
        if(result!=null)update.set("predictedOutcome",result.predictedOutcome()).set("probabilities",result.probabilities()).set("modelVersion",result.modelVersion());
        else if(!saved.isEmpty())update.set("predictedOutcome",saved.get(0).getPredictedOutcome()).set("probabilities",saved.get(0).getPredictionProbabilities()).set("modelVersion",saved.get(0).getModelVersion());
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),update,RecommendationMilestone.class);
    }
    private RecommendationMilestone.ThreeLessonPerformance performance(List<EligibleLesson> lessons) {
        var rows=lessons.stream().map(lesson->{var result=lesson.score();return new RecommendationMilestone.LessonScore(
                lesson.assignment().getMaterialId(),result.getWorksheetTitle()==null?lesson.material().getTitle():result.getWorksheetTitle(),
                result.getSubject(),lesson.assignment().getCompletedAt(),result.getAssessmentDate(),result.getScore(),result.getMaxScore(),
                result.getPercentage(),result.getNotes(),result.getId());}).toList();
        double average=rows.stream().mapToDouble(RecommendationMilestone.LessonScore::percentage).average().orElse(0);
        double change=rows.get(rows.size()-1).percentage()-rows.get(0).percentage();
        String trend=change>0?"Improving":change<0?"Declining":"Stable";
        return new RecommendationMilestone.ThreeLessonPerformance(rows,Math.round(average*100.0)/100.0,Math.round(change*100.0)/100.0,trend);
    }
    private void fail(String id,String status,String message){mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),new Update().set("status",status).set("message",message).set("updatedAt",Instant.now()).unset("leaseUntil"),RecommendationMilestone.class);}
}
