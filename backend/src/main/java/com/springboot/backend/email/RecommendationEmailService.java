package com.springboot.backend.email;

import com.springboot.backend.recommendation.RecommendationsPersisted;
import com.springboot.backend.recommendation.RecommendationsPersisted.NotableRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class RecommendationEmailService {

    private static final Logger LOG = LoggerFactory.getLogger(RecommendationEmailService.class);

    private final RecommendationEmailRepository repository;
    private final RecommendationEmailSettings settings;
    private final RecommendationEmailContentBuilder contentBuilder;
    private final RecommendationEmailSender sender;

    public RecommendationEmailService(
            RecommendationEmailRepository repository,
            RecommendationEmailSettings settings,
            RecommendationEmailContentBuilder contentBuilder,
            RecommendationEmailSender sender) {

        this.repository = repository;
        this.settings = settings;
        this.contentBuilder = contentBuilder;
        this.sender = sender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecommendationsPersisted(RecommendationsPersisted event) {
        for (NotableRecommendation notable : event.recommendations()) {
            try {
                process(notable.recommendationId());
            } catch (RuntimeException failure) {
                LOG.error(
                        "Unexpected recommendation email failure for recommendation {}; recommendation remains saved",
                        notable.recommendationId(),
                        failure
                );
            }
        }
    }

    private void process(long recommendationId) {
        EmailRecommendation recommendation = repository.findEligibleAccountRecommendation(recommendationId).orElse(null);

        if (recommendation == null) {
            return;
        }

        boolean eligible = recommendation.accountEnabled()
                && recommendation.recommendationEmailsEnabled()
                && settings.isEligible(recommendation.verdict(), recommendation.grade());

        if (!eligible) {
            repository.markNotEligible(recommendationId);
            return;
        }

        if (!repository.markPending(recommendationId) || !repository.claim(recommendationId)) {
            return;
        }

        RecommendationEmailContent content = contentBuilder.build(recommendation);

        for (int attempt = 1; attempt <= settings.maximumAttempts(); attempt++) {
            try {
                sender.send(recommendation.recipient(), content.subject(), content.body());
                repository.recordSuccess(recommendationId, recommendation.userId());
                return;
            } catch (RuntimeException failure) {
                boolean finalFailure = attempt == settings.maximumAttempts();
                repository.recordFailure(
                        recommendationId,
                        recommendation.userId(),
                        describe(failure),
                        finalFailure
                );

                if (finalFailure) {
                    LOG.warn(
                            "Recommendation email {} failed after {} attempts",
                            recommendationId,
                            attempt
                    );
                }
            }
        }
    }

    private String describe(RuntimeException failure) {
        String message = failure.getMessage();
        String description = failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return description.length() <= 1000 ? description : description.substring(0, 1000);
    }
}