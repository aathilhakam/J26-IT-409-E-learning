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
    static final double SUCCESS_THRESHOLD_PERCENT=80.0;
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
    record VerifiedAssessment(double percentage,int count,String fingerprint,List<RecommendationMilestone.AssessmentEvidence> evidence) {}
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
        var milestones=mongo.find(Query.query(Criteria.where("ownerId").is(owner).and("studentId").is(student)).with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,"milestone")),RecommendationMilestone.class);
        var currentEvidence=verifiedAssessment(owner,student,assessmentResults.findByStudentIdOrderByAssessedAtDesc(student));
        // Older milestones predate the detailed evidence field. Add the exact current
        // rows to the response only when their fingerprint matches the saved decision.
        // This is a response enrichment and does not mutate the persisted milestone.
        milestones.stream().filter(m->Objects.equals(m.assessmentEvidenceFingerprint,currentEvidence.fingerprint()))
            .forEach(m->m.assessmentEvidence=currentEvidence.evidence());
        return new Progress(student,count,count%3,(count/3+1)*3,
            milestones,
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
        var verified=verifiedAssessment(owner,student,assessmentResults.findByStudentIdOrderByAssessedAtDesc(student));
        var seed=Query.query(Criteria.where("_id").is(id));
        try {mongo.upsert(seed,new Update().setOnInsert("ownerId",owner).setOnInsert("studentId",student).setOnInsert("milestone",threshold)
            .setOnInsert("createdAt",now).setOnInsert("status","READY"),RecommendationMilestone.class);}catch(org.springframework.dao.DuplicateKeyException concurrent){}
        var previous=mongo.findById(id,RecommendationMilestone.class);
        boolean sameEvidence=previous!=null&&Objects.equals(previous.assessmentEvidenceFingerprint,verified.fingerprint());
        if(previous!=null&&Set.of("NO_MATCH","SUCCESSFUL","GENERATED").contains(previous.status)&&sameEvidence)return;
        if(previous!=null&&!sameEvidence&&!"GENERATING".equals(previous.status)) {
            var reset=Query.query(Criteria.where("_id").is(id).and("status").is(previous.status)
                .and("assessmentEvidenceFingerprint").is(previous.assessmentEvidenceFingerprint));
            mongo.updateFirst(reset,new Update().set("status","READY").set("inferenceReady",false)
                .set("plannedRecommendations",List.of()).set("assessmentEvidenceFingerprint",verified.fingerprint())
                .set("verifiedPerformancePercentage",verified.count()==0?null:verified.percentage()).set("verifiedAssessmentCount",verified.count())
                .set("assessmentEvidence",verified.evidence())
                .set("applicationOutcome","INSUFFICIENT_DATA").set("pathwayDecision","NOT_EVALUATED")
                .set("updatedAt",now).unset("outcomeDiscrepancy").unset("leaseUntil"),RecommendationMilestone.class);
        }
        // A bounded lease permits crash recovery without parallel inference for this milestone.
        var claim=Query.query(new Criteria().andOperator(Criteria.where("_id").is(id),new Criteria().orOperator(
            Criteria.where("status").in("READY","INSUFFICIENT_DATA","ERROR"),new Criteria().andOperator(Criteria.where("status").is("GENERATING"),Criteria.where("leaseUntil").lt(now)))));
        var claimed=mongo.findAndModify(claim,new Update().set("status","GENERATING").set("updatedAt",now)
            .set("assessmentEvidenceFingerprint",verified.fingerprint()).set("verifiedAssessmentCount",verified.count())
            .set("verifiedPerformancePercentage",verified.count()==0?null:verified.percentage()).set("assessmentEvidence",verified.evidence()).set("leaseUntil",now.plusSeconds(300)),FindAndModifyOptions.options().returnNew(true),RecommendationMilestone.class);
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
                boolean successful="Successful".equals(claimed.applicationOutcome);
                finish(id,successful?"SUCCESSFUL":saved.isEmpty()?"NO_MATCH":"GENERATED",
                    successful?"Verified overall performance met the 80% threshold; no remedial pathway was generated."
                        :saved.isEmpty()?"No validated curriculum activity matches the documented evidence":"Recovered saved pathway; no duplicate inference",saved,result);return;
            }
            var milestoneLessons=completed.subList(threshold-3,threshold);
            var performance=performance(milestoneLessons);
            mongo.updateFirst(seed,new Update().set("completedMaterialIds",completed.subList(0,threshold).stream().map(lesson->lesson.assignment().getMaterialId()).toList()).set("evidenceIds",completed.stream().map(lesson->"assignment:"+lesson.assignment().getId()).toList()).set("threeLessonPerformance",performance),RecommendationMilestone.class);
            // Keep score evidence visible, but do not produce a final outcome until the
            // existing 28-feature readiness check also succeeds.
            mongo.updateFirst(seed,new Update().set("verifiedPerformancePercentage",verified.count()==0?null:verified.percentage())
                .set("verifiedAssessmentCount",verified.count()).set("applicationOutcome","INSUFFICIENT_DATA")
                .set("pathwayDecision","NOT_EVALUATED"),RecommendationMilestone.class);
            if(verified.count()==0) {
                mongo.updateFirst(seed,new Update().set("applicationOutcome","INSUFFICIENT_DATA")
                    .set("pathwayDecision","NOT_EVALUATED").set("verifiedPerformancePercentage",null),RecommendationMilestone.class);
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Insufficient data: no completed assessment results with valid score and maximum score are available");
            }
            var snapshot=pathways.snapshot(student,owner); // Exact unchanged 28-feature contract; errors stop before inference.
            double verifiedPerformance=verified.percentage();
            String applicationOutcome=applicationOutcome(verifiedPerformance);
            String pathwayDecision=verifiedPerformance>=SUCCESS_THRESHOLD_PERCENT?"NO_REMEDIAL_PATHWAY_SUCCESSFUL":"EVALUATE_TARGETED_PRACTICE";
            mongo.updateFirst(seed,new Update().set("verifiedPerformancePercentage",verifiedPerformance)
                .set("verifiedAssessmentCount",verified.count()).set("applicationOutcome",applicationOutcome).set("pathwayDecision",pathwayDecision),RecommendationMilestone.class);
            var savedFeedback=feedback.findByOwnerIdAndStudentIdAndSubmissionStatusOrderBySubmittedAtDescUpdatedAtDesc(owner,student,"SUBMITTED");
            var sources=new LinkedHashSet<>(snapshot.sourceRecordIds());savedFeedback.forEach(f->sources.add("feedback:"+f.getId()));completed.forEach(lesson->{sources.add("assignment:"+lesson.assignment().getId());sources.add("assessment:"+lesson.score().getId());});
            var completedIds=new LinkedHashSet<String>();completed.forEach(lesson->completedIds.add(lesson.assignment().getMaterialId()));
            for(var prior:recommendations.findByStudentIdOrderByCreatedAtDesc(student))if(completedIds.contains(prior.getMaterialId()) && prior.getLessonId()!=null)completedIds.add(prior.getLessonId());
            var context=new ArrayList<Map<String,Object>>();
            savedFeedback.stream().limit(180).forEach(f->{Map<String,Object> row=new LinkedHashMap<>();row.put("evidence_id","feedback:"+f.getId());row.put("improvement",f.getImprovement()==null?"":f.getImprovement());row.put("next_step",f.getFeedback()==null?"":f.getFeedback());context.add(row);});
            for(var lesson:milestoneLessons){var score=lesson.score();Map<String,Object> row=new LinkedHashMap<>();row.put("evidence_id","assessment:"+score.getId());row.put("improvement",lesson.material().getTitle()+" score: "+score.getScore()+"/"+score.getMaxScore()+" ("+score.getPercentage()+"%)");row.put("next_step",score.getNotes()==null?"":score.getNotes());context.add(row);}
            mongo.updateFirst(seed,new Update().set("featureSnapshot",snapshot).set("completedMaterialIds",completed.subList(0,threshold).stream().map(lesson->lesson.assignment().getMaterialId()).toList()).set("evidenceIds",List.copyOf(sources)).set("threeLessonPerformance",performance),RecommendationMilestone.class);
            var pathwayContext=new LinkedHashMap<String,Object>();pathwayContext.put("teacher_context",context);
            pathwayContext.put("verified_performance_percentage",verifiedPerformance);pathwayContext.put("application_outcome",applicationOutcome);
            var result=pathways.generateForMilestone(student,owner,threshold,snapshot,List.copyOf(completedIds),List.copyOf(sources),pathwayContext,plan -> {
                plan.recommendations().forEach(recommendation->recommendation.milestonePerformance(performance));
                mongo.updateFirst(seed,new Update().set("inferenceReady",true).set("plannedRecommendations",plan.recommendations())
                    .set("predictedOutcome",plan.predictedOutcome()).set("probabilities",plan.probabilities()).set("modelVersion",plan.modelVersion())
                    .set("outcomeDiscrepancy",!applicationOutcome.equals(plan.predictedOutcome())?"Random Forest predicted "+plan.predictedOutcome()+"; verified score policy classified "+applicationOutcome+".":null),RecommendationMilestone.class);
            });
            String finalStatus=applicationOutcome.equals("Successful")?"SUCCESSFUL":result.recommendations().isEmpty()?"NO_MATCH":"GENERATED";
            String finalMessage=applicationOutcome.equals("Successful")?"Verified overall performance is "+verifiedPerformance+"% (80% or above); no new remedial pathway was generated."
                :result.recommendations().isEmpty()?result.status():"Verified overall performance is "+verifiedPerformance+"%; targeted recommendations are awaiting teacher review.";
            finish(id,finalStatus,finalMessage,result.recommendations(),result);
        }catch(ResponseStatusException error){
            boolean insufficient=error.getStatusCode().value()==422;
            if(insufficient)mongo.updateFirst(seed,new Update().set("applicationOutcome","INSUFFICIENT_DATA").set("pathwayDecision","NOT_EVALUATED"),RecommendationMilestone.class);
            fail(id,insufficient?"INSUFFICIENT_DATA":"ERROR",error.getReason()==null?"Required learning evidence is unavailable":error.getReason());
        }
        catch(RuntimeException error){fail(id,"ERROR","Could not finish evaluation. Retry this milestone safely.");}
    }
    private void finish(String id,String status,String message,List<LearningRecommendation> saved,LivePathwayService.Result result) {
        var update=new Update().set("status",status).set("message",message).set("updatedAt",Instant.now()).unset("leaseUntil").set("recommendationIds",saved.stream().map(LearningRecommendation::getId).toList());
        if(result!=null)update.set("predictedOutcome",result.predictedOutcome()).set("modelOutcomeCompatibility",legacyApplicationCategory(result.predictedOutcome())).set("probabilities",result.probabilities()).set("modelVersion",result.modelVersion());
        else if(!saved.isEmpty())update.set("predictedOutcome",saved.get(0).getPredictedOutcome()).set("modelOutcomeCompatibility",legacyApplicationCategory(saved.get(0).getPredictedOutcome())).set("probabilities",saved.get(0).getPredictionProbabilities()).set("modelVersion",saved.get(0).getModelVersion());
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
    /** Mean of valid completed score percentages, matching the existing worksheet/activity percentage aggregation. */
    static Double verifiedOverallPerformance(String owner,String student,List<AssessmentResult> results) {
        var verified=verifiedAssessment(owner,student,results);
        return verified.count()==0?null:verified.percentage();
    }
    /** Deduplicate the same assessment identity before computing a verified score summary. */
    static VerifiedAssessment verifiedAssessment(String owner,String student,List<AssessmentResult> results) {
        var unique=new LinkedHashMap<String,AssessmentResult>();
        results.stream()
            // Legacy measured assessment results do not persist teacherOwnerId;
            // the caller has already verified ownership of this student. Worksheet
            // records do carry an owner and must match it explicitly.
            .filter(result->student.equals(result.getStudentId())&&(!result.isWorksheet()||owner.equals(result.getTeacherOwnerId())))
            .filter(result->"COMPLETED".equals(result.getCompletionStatus()))
            .filter(result->Double.isFinite(result.getScore())&&Double.isFinite(result.getMaxScore())
                &&result.getMaxScore()>0&&result.getScore()>=0&&result.getScore()<=result.getMaxScore())
            .forEach(result->unique.merge(assessmentIdentity(result),result,(first,second)->recordedAt(first).compareTo(recordedAt(second))>=0?first:second));
        var scores=unique.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        var evidence=scores.stream().map(entry->{var result=entry.getValue();return new RecommendationMilestone.AssessmentEvidence(
                result.getId(),result.getAssessmentId(),result.getWorksheetTitle()==null?result.getSubject():result.getWorksheetTitle(),
                result.getAssessmentType(),result.getSourceComponent(),result.getScore(),result.getMaxScore(),
                result.getScore()*100.0/result.getMaxScore(),result.getCompletionStatus(),result.getAssessedAt());}).toList();
        if(scores.isEmpty())return new VerifiedAssessment(0,0,fingerprint(scores),evidence);
        double average=scores.stream().mapToDouble(entry->entry.getValue().getScore()*100.0/entry.getValue().getMaxScore()).average().orElseThrow();
        return new VerifiedAssessment(Math.round(average*100.0)/100.0,scores.size(),fingerprint(scores),evidence);
    }
    private static String assessmentIdentity(AssessmentResult result) {
        if(result.isWorksheet()) {
            String material=result.getMaterialId();
            if(material!=null&&!material.isBlank())return "worksheet:"+material+":"+(result.getAssessmentDate()==null?"unknown-date":result.getAssessmentDate());
        }
        if(result.getAssessmentId()!=null&&!result.getAssessmentId().isBlank())return (result.isWorksheet()?"worksheet:":"assessment:")+result.getAssessmentId().trim();
        return "record:"+Objects.toString(result.getId(),Integer.toHexString(System.identityHashCode(result)));
    }
    private static Instant recordedAt(AssessmentResult result) {
        if(result.getRecordedAt()!=null)return result.getRecordedAt();
        if(result.getAssessedAt()!=null)return result.getAssessedAt();
        return Instant.MIN;
    }
    private static String fingerprint(List<Map.Entry<String,AssessmentResult>> scores) {
        try {
            var value=new StringBuilder();
            for(var entry:scores){var result=entry.getValue();value.append(entry.getKey()).append('|').append(result.getScore())
                .append('|').append(result.getMaxScore()).append('|').append(Objects.toString(result.getSubject(),""))
                .append('|').append(Objects.toString(result.getWorksheetTitle(),""))
                .append('|').append(Objects.toString(result.getAssessmentType(),""))
                .append('|').append(Objects.toString(result.getActivityId(),""))
                .append('|').append(Objects.toString(result.getAttempts(),""))
                .append('|').append(Objects.toString(result.getTimeTakenMinutes(),""))
                .append('|').append(new TreeMap<>(result.getMeasures())).append('\n');}
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 is unavailable",impossible);}
    }
    static String applicationOutcome(double verifiedPerformance) {
        if(!Double.isFinite(verifiedPerformance)||verifiedPerformance<0||verifiedPerformance>100)throw new IllegalArgumentException("Verified performance must be between 0 and 100");
        return verifiedPerformance>=SUCCESS_THRESHOLD_PERCENT?"Successful":"Needs Practice";
    }
    static String legacyApplicationCategory(String rawOutcome) {
        return switch(rawOutcome) {
            case "Successful" -> "Successful";
            case "Needs Practice" -> "Needs Practice";
            case "Waiting" -> "INSUFFICIENT_DATA";
            default -> throw new IllegalArgumentException("Unsupported Random Forest class: "+rawOutcome);
        };
    }
    private void fail(String id,String status,String message){mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),new Update().set("status",status).set("message",message).set("updatedAt",Instant.now()).unset("leaseUntil"),RecommendationMilestone.class);}
}
