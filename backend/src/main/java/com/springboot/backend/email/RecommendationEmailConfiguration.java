package com.springboot.backend.email;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Isolates potentially slow SMTP delivery from recommendation generation. */
@Configuration
public class RecommendationEmailConfiguration {

    public static final String EXECUTOR = "recommendationEmailExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor recommendationEmailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("recommendation-email-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
