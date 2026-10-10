package com.springboot.backend.recommendation.trigger;

import com.springboot.backend.recommendation.AssessRequest;
import com.springboot.backend.recommendation.CandidateEvaluation;
import com.springboot.backend.recommendation.RecommendationInput;
import com.springboot.backend.recommendation.RecommendationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Channel B for one pair that passed the preference gate: build the request,
 * call {@code POST /assess} once, persist (AGENTS.md §8.1).
 *
 * <p>Only ever handed a candidate whose verdict is not
 * {@code NO_MEANINGFUL_CHANGE}; the gate itself stays with the caller and with
 * {@link CandidateEvaluation#worthAssessing()}.
 *
 * <p>Never throws. Whatever happens to this pair, the deterministic row for it
 * is already committed, and one pair's AI failure must not stop the rest of the
 * run. {@link RecommendationService#assessAndPersist} makes the call outside any
 * database transaction and persists in its own, superseding the deterministic
 * row only when the call succeeded.
 */
@Component
public class AiAssessmentStep {

    private final AssessRequestFactory requests;
    private final RecommendationService recommendations;
    private final boolean enabled;

    public AiAssessmentStep(
            AssessRequestFactory requests,
            RecommendationService recommendations,
            @Value("${recommendation.triggers.ai-assessment-enabled:true}") boolean enabled) {

        this.requests = requests;
        this.recommendations = recommendations;
        this.enabled = enabled;
    }

    /**
     * @param triggerEventId stored on the AI row as {@code trigger_event_id}; nullable
     * @param triggerEvent   sent to the model as {@code computed.trigger_event}; nullable
     */
    public Outcome assess(
            CandidateEvaluation evaluation,
            CandidateEvaluation.Classified classified,
            Long triggerEventId,
            AssessRequest.TriggerEvent triggerEvent) {

        if (!enabled) {
            return Outcome.skipped("AI assessment disabled (recommendation.triggers.ai-assessment-enabled=false)");
        }

        try {
            AssessRequestFactory.Built built = requests.build(evaluation.userDeviceId(), classified, triggerEvent);
            if (!built.complete()) {
                return Outcome.skipped("missing " + String.join(", ", built.missing()));
            }

            long id = recommendations.assessAndPersist(new RecommendationInput(
                    evaluation.userId(),
                    evaluation.userDeviceId(),
                    triggerEventId,
                    built.request(),
                    classified.classification().toDeterministicFactors()));
            return Outcome.assessed(id);
        } catch (RuntimeException e) {
            return Outcome.failed(e);
        }
    }

    /**
     * @param recommendationId the AI row written, when {@link Kind#ASSESSED}
     * @param reason           why the call was not made, when {@link Kind#SKIPPED}
     * @param error            what went wrong, when {@link Kind#FAILED}
     */
    public record Outcome(Kind kind, Long recommendationId, String reason, RuntimeException error) {

        public enum Kind { ASSESSED, SKIPPED, FAILED }

        static Outcome assessed(long recommendationId) {
            return new Outcome(Kind.ASSESSED, recommendationId, null, null);
        }

        static Outcome skipped(String reason) {
            return new Outcome(Kind.SKIPPED, null, reason, null);
        }

        static Outcome failed(RuntimeException error) {
            return new Outcome(Kind.FAILED, null, null, error);
        }
    }
}
