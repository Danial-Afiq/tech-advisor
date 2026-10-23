package com.springboot.backend;

import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "logging.level.root=WARN",
        "recommendation.dismissal.cooldown-days=30"
})
class DashboardDismissalIntegrationTest {

    @Autowired
    WebApplicationContext web;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtService jwtService;

    @Autowired
    JdbcTemplate db;

    @Test
    void ownerCanDismissRecommendationAndFeedbackIsStored()
            throws Exception {

        Fixture fixture = createFixture();

        try {
            String token =
                    jwtService.generateToken(fixture.owner());

            mvc().perform(
                            MockMvcRequestBuilders.post(
                                            "/api/dashboard/recommendations/{id}/dismiss",
                                            fixture.recommendationId()
                                    )
                                    .header(
                                            "Authorization",
                                            "Bearer " + token
                                    )
                                    .contentType(
                                            MediaType.APPLICATION_JSON
                                    )
                                    .content("""
                                            {
                                              "reason": "TOO_EXPENSIVE",
                                              "comment": "  Above my budget  "
                                            }
                                            """)
                    )
                    .andExpect(status().isNoContent());

            String status = db.queryForObject(
                    """
                    SELECT status
                    FROM recommendations
                    WHERE id = ?
                    """,
                    String.class,
                    fixture.recommendationId()
            );

            assertEquals("DISMISSED", status);

            String reason = db.queryForObject(
                    """
                    SELECT reason
                    FROM recommendation_dismissals
                    WHERE recommendation_id = ?
                    """,
                    String.class,
                    fixture.recommendationId()
            );

            assertEquals("TOO_EXPENSIVE", reason);

            String comment = db.queryForObject(
                    """
                    SELECT comment
                    FROM recommendation_dismissals
                    WHERE recommendation_id = ?
                    """,
                    String.class,
                    fixture.recommendationId()
            );

            assertEquals("Above my budget", comment);

            Boolean cooldownIsActive = db.queryForObject(
                    """
                    SELECT cooldown_until > CURRENT_TIMESTAMP
                    FROM recommendation_dismissals
                    WHERE recommendation_id = ?
                    """,
                    Boolean.class,
                    fixture.recommendationId()
            );

            assertTrue(Boolean.TRUE.equals(cooldownIsActive));
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void anotherUserCannotDismissRecommendation()
            throws Exception {

        Fixture fixture = createFixture();

        User otherUser = userRepository.save(
                new User(
                        "dismissal-other-"
                                + UUID.randomUUID()
                                + "@example.com",
                        "unused-hash",
                        "USER"
                )
        );

        try {
            String token = jwtService.generateToken(otherUser);

            mvc().perform(
                            MockMvcRequestBuilders.post(
                                            "/api/dashboard/recommendations/{id}/dismiss",
                                            fixture.recommendationId()
                                    )
                                    .header(
                                            "Authorization",
                                            "Bearer " + token
                                    )
                                    .contentType(
                                            MediaType.APPLICATION_JSON
                                    )
                                    .content("""
                                            {
                                              "reason": "NOT_INTERESTED"
                                            }
                                            """)
                    )
                    .andExpect(status().isNotFound());

            String recommendationStatus = db.queryForObject(
                    """
                    SELECT status
                    FROM recommendations
                    WHERE id = ?
                    """,
                    String.class,
                    fixture.recommendationId()
            );

            assertEquals("ACTIVE", recommendationStatus);

            Integer dismissalCount = db.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM recommendation_dismissals
                    WHERE recommendation_id = ?
                    """,
                    Integer.class,
                    fixture.recommendationId()
            );

            assertEquals(0, dismissalCount);
        } finally {
            userRepository.deleteById(otherUser.getId());
            deleteFixture(fixture);
        }
    }

    @Test
    void missingDismissalReasonReturnsBadRequest()
            throws Exception {

        Fixture fixture = createFixture();

        try {
            String token =
                    jwtService.generateToken(fixture.owner());

            mvc().perform(
                            MockMvcRequestBuilders.post(
                                            "/api/dashboard/recommendations/{id}/dismiss",
                                            fixture.recommendationId()
                                    )
                                    .header(
                                            "Authorization",
                                            "Bearer " + token
                                    )
                                    .contentType(
                                            MediaType.APPLICATION_JSON
                                    )
                                    .content("""
                                            {
                                              "comment": "No reason selected"
                                            }
                                            """)
                    )
                    .andExpect(status().isBadRequest());

            String recommendationStatus = db.queryForObject(
                    """
                    SELECT status
                    FROM recommendations
                    WHERE id = ?
                    """,
                    String.class,
                    fixture.recommendationId()
            );

            assertEquals("ACTIVE", recommendationStatus);
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void unauthenticatedUserCannotDismissRecommendation()
            throws Exception {

        Fixture fixture = createFixture();

        try {
            mvc().perform(
                            MockMvcRequestBuilders.post(
                                            "/api/dashboard/recommendations/{id}/dismiss",
                                            fixture.recommendationId()
                                    )
                                    .contentType(
                                            MediaType.APPLICATION_JSON
                                    )
                                    .content("""
                                            {
                                              "reason": "NOT_INTERESTED"
                                            }
                                            """)
                    )
                    .andExpect(status().isUnauthorized());
        } finally {
            deleteFixture(fixture);
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders
                .webAppContextSetup(web)
                .apply(
                        SecurityMockMvcConfigurers
                                .springSecurity()
                )
                .build();
    }

    private Fixture createFixture() {

        String unique = UUID.randomUUID().toString();

        User owner = userRepository.save(
                new User(
                        "dismissal-owner-"
                                + unique
                                + "@example.com",
                        "unused-hash",
                        "USER"
                )
        );

        Long ownedProductId = db.queryForObject(
                """
                INSERT INTO products (
                    brand,
                    model_name
                )
                VALUES (?, ?)
                RETURNING id
                """,
                Long.class,
                "OwnedBrand-" + unique,
                "Owned Model"
        );

        Long candidateProductId = db.queryForObject(
                """
                INSERT INTO products (
                    brand,
                    model_name
                )
                VALUES (?, ?)
                RETURNING id
                """,
                Long.class,
                "CandidateBrand-" + unique,
                "Candidate Model"
        );

        Long currentDeviceId = db.queryForObject(
                """
                INSERT INTO user_devices (
                    user_id,
                    product_id,
                    custom_name,
                    is_current
                )
                VALUES (?, ?, ?, TRUE)
                RETURNING id
                """,
                Long.class,
                owner.getId(),
                ownedProductId,
                "Daily phone"
        );

        Long recommendationId = db.queryForObject(
                """
                INSERT INTO recommendations (
                    user_id,
                    current_device_id,
                    candidate_product_id,
                    verdict,
                    confidence,
                    reasoning,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                RETURNING id
                """,
                Long.class,
                owner.getId(),
                currentDeviceId,
                candidateProductId,
                "WORTH_CONSIDERING",
                "A",
                "Potential upgrade"
        );

        return new Fixture(
                owner,
                ownedProductId,
                candidateProductId,
                recommendationId
        );
    }

    private void deleteFixture(Fixture fixture) {

        userRepository.deleteById(fixture.owner().getId());

        db.update(
                "DELETE FROM products WHERE id = ?",
                fixture.candidateProductId()
        );

        db.update(
                "DELETE FROM products WHERE id = ?",
                fixture.ownedProductId()
        );
    }

    private record Fixture(
            User owner,
            Long ownedProductId,
            Long candidateProductId,
            Long recommendationId
    ) {
    }
}