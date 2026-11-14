package com.springboot.backend.email;

import com.springboot.backend.recommendation.DeviceRecommendationEvaluationCompleted;
import com.springboot.backend.recommendation.RecommendationSaved;
import com.springboot.backend.recommendation.RecommendationEmailMode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public void onRecommendationSaved(RecommendationSaved event) {
        if (event.emailMode() == RecommendationEmailMode.DEVICE_SUMMARY) {
            return;
        }

        try {
            process(event.recommendationId());
        } catch (RuntimeException failure) {
            LOG.error(
                    "Unexpected recommendation email failure for recommendation {}; recommendation remains saved",
                    event.recommendationId(),
                    failure
            );
        }
    }

    public void onDeviceEvaluationCompleted(DeviceRecommendationEvaluationCompleted event) {
        try {
            processSummary(event.userDeviceId());
        } catch (RuntimeException failure) {
            LOG.error(
                    "Unexpected recommendation summary email failure for device {}; recommendations remain saved",
                    event.userDeviceId(),
                    failure
            );
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

        if (!repository.isRecommendationSendable(recommendationId)) {
            repository.abandon(List.of(recommendationId));
            return;
        }

        RecommendationEmailContent content = contentBuilder.build(recommendation);

        for (int attempt = 1; attempt <= settings.maximumAttempts(); attempt++) {
            if (!repository.isRecommendationSendable(recommendationId)) {
                repository.abandon(List.of(recommendationId));
                return;
            }
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

    private void processSummary(long userDeviceId) {
        List<EmailRecommendation> recommendations = repository.findUncheckedActiveDeviceRecommendations(userDeviceId);
        if (recommendations.isEmpty()) {
            return;
        }

        List<EmailRecommendation> selected = recommendations.stream()
                .filter(this::eligible)
                .sorted(Comparator
                        .comparing(
                                EmailRecommendation::upgradeScore,
                                Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
                        .thenComparingLong(EmailRecommendation::candidateProductId)
                        .thenComparingLong(EmailRecommendation::recommendationId))
                .limit(3)
                .toList();

        List<Long> selectedIds = selected.stream().map(EmailRecommendation::recommendationId).toList();
        repository.markDeviceRecommendationsNotSelected(userDeviceId, selectedIds);

        if (selected.isEmpty()) {
            return;
        }

        List<EmailRecommendation> claimed = new ArrayList<>();
        for (EmailRecommendation recommendation : selected) {
            if (repository.markPending(recommendation.recommendationId())
                    && repository.claim(recommendation.recommendationId())) {
                claimed.add(recommendation);
            }
        }
        if (claimed.isEmpty()) {
            return;
        }

        List<Long> claimedIds = claimed.stream().map(EmailRecommendation::recommendationId).toList();
        EmailRecommendation first = claimed.getFirst();
        if (!repository.areDeviceSummaryRecommendationsSendable(
                userDeviceId, first.userId(), claimedIds)) {
            repository.abandon(claimedIds);
            return;
        }

        RecommendationEmailContent content = contentBuilder.buildSummary(claimed);
        for (int attempt = 1; attempt <= settings.maximumAttempts(); attempt++) {
            if (!repository.areDeviceSummaryRecommendationsSendable(
                    userDeviceId, first.userId(), claimedIds)) {
                repository.abandon(claimedIds);
                return;
            }
            try {
                sender.send(first.recipient(), content.subject(), content.body());
                repository.recordSummarySuccess(claimedIds, first.userId());
                return;
            } catch (RuntimeException failure) {
                boolean finalFailure = attempt == settings.maximumAttempts();
                repository.recordSummaryFailure(
                        claimedIds,
                        first.userId(),
                        describe(failure),
                        finalFailure
                );

                if (finalFailure) {
                    LOG.warn(
                            "Recommendation summary email for device {} failed after {} attempts",
                            userDeviceId,
                            attempt
                    );
                }
            }
        }
    }

    private boolean eligible(EmailRecommendation recommendation) {
        return recommendation.accountEnabled()
                && recommendation.recommendationEmailsEnabled()
                && settings.isEligible(recommendation.verdict(), recommendation.grade());
    }

    private String describe(RuntimeException failure) {
        String message = failure.getMessage();
        String description = failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return description.length() <= 1000 ? description : description.substring(0, 1000);
    }
}
