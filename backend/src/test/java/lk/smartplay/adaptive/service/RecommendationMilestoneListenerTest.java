package lk.smartplay.adaptive.service;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import lk.smartplay.adaptive.config.RecommendationMilestoneConfig;
import lk.smartplay.adaptive.repository.StudentRepository;
class RecommendationMilestoneListenerTest {
    @Test void completionEventStartsOnlyTheCorrectStudentsEvaluationAsynchronously() {
        var service=mock(RecommendationMilestoneService.class);var students=mock(StudentRepository.class);
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("isolated-listener",java.util.Map.of("smartplay.milestones.automatic","true","smartplay.milestones.interval-ms","3600000")));
            context.register(RecommendationMilestoneConfig.class);
            context.registerBean(RecommendationMilestoneService.class,()->service);
            context.registerBean(StudentRepository.class,()->students);
            context.registerBean(RecommendationMilestoneListener.class,()->new RecommendationMilestoneListener(service,students));
            context.refresh();context.publishEvent(new LessonCompletionEvent("teacher-a","student-a"));
            verify(service,timeout(3000)).evaluate("teacher-a","student-a");verifyNoInteractions(students);
        }
    }
}
