package com.springboot.backend.email;

import com.springboot.backend.model.User;
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

    @BeforeEach
    void seed() {
        reset(sender);

        String unique = UUID.randomUUID().toString();
        owner = userRepository.save(new User(
                "email-test-" + unique + "@example.com",
                "unused-hash",
                "USER"
        ));

        productId = db.queryForObject(
                """
                INSERT INTO products (brand, model_name)
                VALUES (?, ?)
                RETURNING id
                """,
                Long.class,
                "TestBrand-" + unique,
                "Upgrade Phone"
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
            emailBodyContainingRequiredContent()
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
                anyString()
        );

        assertThat(emailState(recommendationId).get("email_attempts"))
                .isEqualTo(1);
    }

    @Test
    void deliveryFailureRetriesWithoutDeletingRecommendation() {
        doThrow(new IllegalStateException("simulated SMTP outage"))
                .when(sender)
                .send(anyString(), anyString(), anyString());

        long recommendationId = recommendation(
                "STRONG_UPGRADE_CANDIDATE",
                "A"
        );

        process(recommendationId);

        verify(sender, times(3)).send(
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
                    "battery": {
                      "priority": 5,
                      "impact": "HIGH_POSITIVE"
                    },
                    "camera": {
                      "priority": 4,
                      "impact": "POSITIVE"
                    }
                  }
                }
                """,
                "A meaningful improvement for the user."
        );
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
}