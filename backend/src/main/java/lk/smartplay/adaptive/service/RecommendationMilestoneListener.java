package lk.smartplay.adaptive.service;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lk.smartplay.adaptive.repository.StudentRepository;
@Component
@ConditionalOnProperty(name="smartplay.milestones.automatic",havingValue="true",matchIfMissing=true)
public class RecommendationMilestoneListener {
    private final RecommendationMilestoneService milestones;
    private final StudentRepository students;
    public RecommendationMilestoneListener(RecommendationMilestoneService milestones,StudentRepository students){this.milestones=milestones;this.students=students;}
    @Async("milestoneExecutor") @EventListener public void completed(LessonCompletionEvent event){try{milestones.evaluate(event.ownerId(),event.studentId());}catch(RuntimeException ignored){/* Catch-up and explicit retry recover missed evaluations. */}}
    @Scheduled(initialDelayString="${smartplay.milestones.interval-ms:60000}",fixedDelayString="${smartplay.milestones.interval-ms:60000}")
    public void catchUp(){for(var student:students.findAll())if(student.isActive())try{milestones.evaluate(student.getOwnerId(),student.getId());}catch(RuntimeException ignored){/* A bad student's data must not stop other students. */}}
}
