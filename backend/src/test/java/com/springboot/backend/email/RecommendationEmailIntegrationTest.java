package com.springboot.backend.email;

import com.springboot.backend.model.User;
import com.springboot.backend.recommendation.DeviceRecommendationEvaluationCompleted;
import com.springboot.backend.recommendation.RecommendationSaved;
import com.springboot.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "logging.level.root=WARN",
        "recommendation.email.maximum-attempts=3"
})
@DirtiesContext
class RecommendationEmailIntegrationTest {

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RecommendationEmailService emailService;

    @MockitoBean
    private RecommendationEmailSender sender;

    private User owner;
    private long productId;
    private final List<Long> extraProductIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        reset(sender);
        extraProductIds.clear();

        String unique = UUID.randomUUID().toString();
        owner = userRepository.save(new User(
                "email-test-" + unique + "@example.com",
                "unused-hash",
                "USER"
        ));

        productId = db.queryForObject(
                """
                INSERT INTO products (brand, model_name, image_url)
                VALUES (?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "TestBrand-" + unique,
                "Upgrade Phone",
                "https://images.example/upgrade-phone.png"
        );

        db.update(
                """
                INSERT INTO price_history (
                    product_id,
                    price,
                    currency
                )
                VALUES (?, 1299.00, 'SGD')
                """,
                productId
        );
    }

    @AfterEach
    void cleanUp() {
        db.update(
                """
                DELETE FROM system_log
                WHERE component = ?
                  AND metadata->>'userId' = ?
                """,
                RecommendationEmailRepository.LOG_COMPONENT,
                String.valueOf(owner.getId())
        );

        userRepository.deleteById(owner.getId());
        extraProductIds.forEach(id -> db.update("DELETE FROM products WHERE id = ?", id));
        db.update("DELETE FROM products WHERE id = ?", productId);
    }

    @Test
    void eligibleRecommendationSendsOneEmailAndRecordsSuccess() {
        long recommendationId = recommendation(
                "STRONG_UPGRADE_CANDIDATE",
                "B"
        );

        process(recommendationId);

        verify(sender).send(
            eq(owner.getEmail()),
            eq(
                    "Tech Advisor: strong upgrade candidate — TestBrand-"
                            + brandSuffix()
                            + " Upgrade Phone"
            ),
            emailBodyContainingRequiredContent(),
            emailHtmlContainingRequiredContent()
        );

        Map<String, Object> row = emailState(recommendationId);

        assertThat(row.get("email_status")).isEqualTo("SENT");
        assertThat(row.get("email_attempts")).isEqualTo(1);
        assertThat(row.get("email_sent_at")).isNotNull();
        assertThat(row.get("email_last_error")).isNull();

        assertThat(logCount(recommendationId, "SUCCESS")).isEqualTo(1);
    }

    @Test
    void optedOutUserDoesNotReceiveEmail() {
        db.update(
                """
                UPDATE users
                SET recommendation_emails_enabled = FALSE
                WHERE id = ?
                """,
                owner.getId()
        );

        long recommendationId = recommendation(
                "STRONG_UPGRADE_CANDIDATE",
                "A"
        );

        process(recommendationId);

        verify(sender, never()).send(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );

        assertThat(emailState(recommendationId).get("email_status"))
                .isEqualTo("NOT_ELIGIBLE");
    }

    @Test
    void disabledAccountDoesNotReceiveEmail() {
        db.update(
                """
                UPDATE users
                SET enabled = FALSE
                WHERE id = ?
                """,
                owner.getId()
        );

        long recommendationId = recommendation(
                "STRONG_UPGRADE_CANDIDATE",
                "A"
        );

        process(recommendationId);

        verify(sender, never()).send(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );

        assertThat(emailState(recommendationId).get("email_status"))
                .isEqualTo("NOT_ELIGIBLE");
    }

    @Test
    void missingEvidenceGradeDoesNotReceiveEmail() {
        long recommendationId = recommendation(
                "STRONG_UPGRADE_CANDIDATE",
                "-"
        );

        process(recommendationId);

        verify(sender, never()).send(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );

        assertThat(emailState(recommendationId).get("email_status"))
                .isEqualTo("NOT_ELIGIBLE");
    }

    @Test
    void lowerVerdictDoesNotReceiveEmail() {
        long recommendationId = recommendation(
                "WORTH_CONSIDERING",
                "A"
        );

        process(recommendationId);

        verify(sender, never()).send(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );

        assertThat(emailState(recommendationId).get("email_status"))
                .isEqualTo("NOT_ELIGIBLE");
    }

    @Test
    void processingTheSameRecommendationTwiceDoesNotSendDuplicateEmail() {
        long recommendationId = recommendation(
                "STRONG_UPGRADE_CANDIDATE",
                "A"
        );

        process(recommendationId);
        process(recommendationId);

        verify(sender, times(1)).send(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );

        assertThat(emailState(recommendationId).get("email_attempts"))
                .isEqualTo(1);
    }

    @Test
    void deliveryFailureRetriesWithoutDeletingRecommendation() {
        doThrow(new IllegalStateException("simulated SMTP outage"))
                .when(sender)
                .send(anyString(), anyString(), anyString(), anyString());

        long recommendationId = recommendation(
                "STRONG_UPGRADE_CANDIDATE",
                "A"
        );

        process(recommendationId);

        verify(sender, times(3)).send(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );

        Map<String, Object> row = emailState(recommendationId);

        assertThat(row.get("email_status")).isEqualTo("FAILED");
        assertThat(row.get("email_attempts")).isEqualTo(3);
        assertThat(row.get("email_sent_at")).isNull();
        assertThat((String) row.get("email_last_error"))
                .contains("simulated SMTP outage");

        assertThat(db.queryForObject(
                """
                SELECT COUNT(*)
                FROM recommendations
                WHERE id = ?
                  AND status = 'ACTIVE'
                """,
                Long.class,
                recommendationId
        )).isEqualTo(1);

        assertThat(logCount(recommendationId, "RETRY")).isEqualTo(2);
        assertThat(logCount(recommendationId, "FAILURE")).isEqualTo(1);
    }

    @Test
    void deviceAdditionSendsOneSummaryContainingOnlyTheTopThreeOfTwenty() {
        long deviceId = currentDevice();
        for (int index = 1; index <= 20; index++) {
            long candidateId = candidate("Candidate " + index);
            if (index == 20) {
                db.update(
                        "UPDATE products SET image_url = ? WHERE id = ?",
                        "https://images.example/candidate-20.png",
                        candidateId);
            }
            recommendation(
                    deviceId,
                    candidateId,
                    "STRONG_UPGRADE_CANDIDATE",
                    "B",
                    new BigDecimal(index).movePointLeft(2));
        }

        emailService.onDeviceEvaluationCompleted(new DeviceRecommendationEvaluationCompleted(deviceId));

        verify(sender, times(1)).send(
                eq(owner.getEmail()),
                eq("Tech Advisor: your top upgrade recommendations"),
                org.mockito.ArgumentMatchers.argThat(body ->
                        body.contains("Candidate 20")
                                && body.contains("Candidate 19")
                                && body.contains("Candidate 18")
                                && !body.contains("Candidate 17")),
                org.mockito.ArgumentMatchers.argThat(html ->
                        html.contains("<!doctype html>")
                                && html.contains("https://images.example/candidate-20.png")
                                && html.contains("Candidate 20")
                                && html.contains("Candidate 19")
                                && html.contains("Candidate 18")
                                && !html.contains("Candidate 17")));
        assertThat(statusCount(deviceId, "SENT")).isEqualTo(3);
        assertThat(statusCount(deviceId, "NOT_ELIGIBLE")).isEqualTo(17);

        emailService.onDeviceEvaluationCompleted(new DeviceRecommendationEvaluationCompleted(deviceId));
        verify(sender, times(1)).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void deviceAdditionWithTwoEligibleCandidatesSendsBothInOneSummary() {
        long deviceId = currentDevice();
        recommendation(deviceId, candidate("First candidate"), "STRONG_UPGRADE_CANDIDATE", "A", new BigDecimal("0.91"));
        recommendation(deviceId, candidate("Second candidate"), "STRONG_UPGRADE_CANDIDATE", "B", new BigDecimal("0.88"));

        emailService.onDeviceEvaluationCompleted(new DeviceRecommendationEvaluationCompleted(deviceId));

        verify(sender).send(
                eq(owner.getEmail()),
                anyString(),
                org.mockito.ArgumentMatchers.argThat(body ->
                        body.contains("First candidate") && body.contains("Second candidate")),
                org.mockito.ArgumentMatchers.argThat(html ->
                        html.contains("First candidate") && html.contains("Second candidate")));
        assertThat(statusCount(deviceId, "SENT")).isEqualTo(2);
    }

    @Test
    void deviceAdditionWithNoEligibleCandidatesSendsNoEmail() {
        long deviceId = currentDevice();
        recommendation(deviceId, candidate("Mixed evidence"), "STRONG_UPGRADE_CANDIDATE", "C", new BigDecimal("0.95"));
        recommendation(deviceId, candidate("Lower verdict"), "WORTH_CONSIDERING", "A", new BigDecimal("0.94"));

        emailService.onDeviceEvaluationCompleted(new DeviceRecommendationEvaluationCompleted(deviceId));

        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString());
        assertThat(statusCount(deviceId, "NOT_ELIGIBLE")).isEqualTo(2);
    }

    @Test
    void removedDeviceDoesNotReceiveAStaleSummary() {
        long deviceId = currentDevice();
        recommendation(deviceId, candidate("Removed device candidate"), "STRONG_UPGRADE_CANDIDATE", "A", new BigDecimal("0.99"));
        db.update("UPDATE user_devices SET is_current = FALSE WHERE id = ?", deviceId);

        emailService.onDeviceEvaluationCompleted(new DeviceRecommendationEvaluationCompleted(deviceId));

        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    private long recommendation(String verdict, String grade) {
        return db.queryForObject(
                """
                INSERT INTO recommendations (
                    user_id,
                    candidate_product_id,
                    verdict,
                    confidence,
                    factor_analysis,
                    reasoning,
                    status
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    CAST(? AS jsonb),
                    ?,
                    'ACTIVE'
                )
                RETURNING id
                """,
                Long.class,
                owner.getId(),
                productId,
                verdict,
                grade,
                """
                {
                  "deterministic": {
                    "weighting": "PRIORITY_NORMALIZED",
                    "factors": {
                      "battery": {
                        "priority": 5,
                        "contribution": 0.6,
                        "impact": "HIGH_POSITIVE"
                      },
                      "camera": {
                        "priority": 4,
                        "contribution": 0.2,
                        "impact": "POSITIVE"
                      }
                    }
                  }
                }
                """,
                "A meaningful improvement for the user."
        );
    }

    private long currentDevice() {
        return db.queryForObject(
                "INSERT INTO user_devices (user_id, product_id, custom_name) VALUES (?, ?, 'Email test device') RETURNING id",
                Long.class,
                owner.getId(),
                productId);
    }

    private long candidate(String name) {
        long id = db.queryForObject(
                "INSERT INTO products (brand, model_name) VALUES ('SummaryTest', ?) RETURNING id",
                Long.class,
                name);
        extraProductIds.add(id);
        return id;
    }

    private long recommendation(
            long deviceId,
            long candidateId,
            String verdict,
            String grade,
            BigDecimal score) {
        return db.queryForObject(
                """
                INSERT INTO recommendations (
                    user_id, current_device_id, candidate_product_id, verdict, confidence,
                    factor_analysis, reasoning, status
                )
                VALUES (
                    ?, ?, ?, ?, ?,
                    jsonb_build_object(
                        'deterministic',
                        jsonb_build_object('upgrade_score', ?, 'factors', jsonb_build_object())
                    ),
                    'Summary recommendation reasoning.',
                    'ACTIVE'
                )
                RETURNING id
                """,
                Long.class,
                owner.getId(),
                deviceId,
                candidateId,
                verdict,
                grade,
                score);
    }

    private long statusCount(long deviceId, String status) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM recommendations WHERE current_device_id = ? AND email_status = ?",
                Long.class,
                deviceId,
                status);
    }

    private void process(long recommendationId) {
        emailService.onRecommendationSaved(new RecommendationSaved(recommendationId));
    }

    private Map<String, Object> emailState(long recommendationId) {
        return db.queryForMap(
                """
                SELECT
                    email_status,
                    email_attempts,
                    email_sent_at,
                    email_last_error
                FROM recommendations
                WHERE id = ?
                """,
                recommendationId
        );
    }

    private long logCount(long recommendationId, String status) {
        return db.queryForObject(
                """
                SELECT COUNT(*)
                FROM system_log
                WHERE component = ?
                  AND status = ?
                  AND metadata->>'recommendationId' = ?
                """,
                Long.class,
                RecommendationEmailRepository.LOG_COMPONENT,
                status,
                String.valueOf(recommendationId)
        );
    }

    private String brandSuffix() {
        return db.queryForObject(
                "SELECT REPLACE(brand, 'TestBrand-', '') FROM products WHERE id = ?",
                String.class,
                productId
        );
    }

    private String emailBodyContainingRequiredContent() {
        return org.mockito.ArgumentMatchers.argThat(body ->
                body.contains("TestBrand-")
                        && body.contains("Upgrade Phone")
                        && body.contains("Strong upgrade candidate")
                        && body.contains("Owner-evidence grade: B")
                        && body.contains("SGD 1299")
                        && body.contains("Battery: High positive")
                        && body.contains("Camera: Positive")
                        && body.contains("A meaningful improvement for the user.")
                        && body.contains("not an instruction to buy")
                        && body.contains("http://localhost:5173/dashboard?recommendation=")
        );
    }

    private String emailHtmlContainingRequiredContent() {
        return org.mockito.ArgumentMatchers.argThat(html ->
                html.contains("<!doctype html>")
                        && html.contains("https://images.example/upgrade-phone.png")
                        && html.contains("Upgrade Phone product image")
                        && html.contains("Strong upgrade candidate")
                        && html.contains("Owner evidence B")
                        && html.contains("INDICATIVE PRICE")
                        && html.contains("Battery: High positive")
                        && html.contains("A meaningful improvement for the user.")
                        && html.contains("Review recommendation")
                        && html.contains("http://localhost:5173/dashboard?recommendation="));
    }
}
