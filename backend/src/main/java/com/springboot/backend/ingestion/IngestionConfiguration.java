package com.springboot.backend.ingestion;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(IngestionSettings.class)
public class IngestionConfiguration {
    @Bean public Clock ingestionClock() { return Clock.systemUTC(); }
    @Bean public ThreadPoolTaskScheduler ingestionScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3); scheduler.setThreadNamePrefix("ingestion-schedule-");
        scheduler.setRemoveOnCancelPolicy(true); return scheduler;
    }
}
