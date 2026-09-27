package com.springboot.backend.recommendation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor inventory-triggered evaluations run on.
 *
 * <p>Named rather than default: the ingestion {@code ThreadPoolTaskScheduler}
 * is itself an {@code Executor}, so an unqualified {@code @Async} would
 * otherwise land on the ingestion pool.
 *
 * <p>One thread on purpose. Evaluations queue up and run one at a time, so two
 * quick edits to the same device can never race each other on the partial
 * unique index that keeps one {@code ACTIVE} row per (user, candidate).
 */
@Configuration
@EnableAsync
public class RecommendationTriggerConfiguration {

    public static final String EXECUTOR = "recommendationExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor recommendationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("recommendation-trigger-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
