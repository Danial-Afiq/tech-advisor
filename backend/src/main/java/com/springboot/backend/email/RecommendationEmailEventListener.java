package com.springboot.backend.email;

import com.springboot.backend.recommendation.DeviceRecommendationEvaluationCompleted;
import com.springboot.backend.recommendation.RecommendationSaved;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Async event boundary; the processor remains synchronous and easy to test. */
@Component
public class RecommendationEmailEventListener {

    private final RecommendationEmailService emailService;

    public RecommendationEmailEventListener(RecommendationEmailService emailService) {
        this.emailService = emailService;
    }

    @Async(RecommendationEmailConfiguration.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecommendationSaved(RecommendationSaved event) {
        emailService.onRecommendationSaved(event);
    }

    @Async(RecommendationEmailConfiguration.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeviceEvaluationCompleted(DeviceRecommendationEvaluationCompleted event) {
        emailService.onDeviceEvaluationCompleted(event);
    }
}
