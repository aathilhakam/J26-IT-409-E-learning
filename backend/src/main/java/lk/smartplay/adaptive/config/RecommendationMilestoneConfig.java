package lk.smartplay.adaptive.config;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
@Configuration @EnableAsync @EnableScheduling
public class RecommendationMilestoneConfig {
    @Bean("milestoneExecutor") public java.util.concurrent.Executor milestoneExecutor() {
        var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(2);executor.setQueueCapacity(500);executor.setThreadNamePrefix("lesson-milestone-");executor.initialize();return executor;
    }
}
