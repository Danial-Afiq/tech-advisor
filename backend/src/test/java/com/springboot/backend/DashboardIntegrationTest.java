package com.springboot.backend;

import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "logging.level.root=WARN"
})
class DashboardIntegrationTest {

    @Autowired
    WebApplicationContext web;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtService jwtService;

    @Autowired
    JdbcTemplate db;

    @Test
    void dashboardReturnsOnlyAuthenticatedUsersActiveRecommendations()
            throws Exception {

        var mvc = MockMvcBuilders
                .webAppContextSetup(web)
                .apply(
                        SecurityMockMvcConfigurers
                                .springSecurity()
                )
                .build();

        String unique = UUID.randomUUID().toString();

        User owner = userRepository.save(
                new User(
                        "dashboard-owner-"
                                + unique
                                + "@example.com",
                        "unused-hash",
                        "USER"
                )
        );

        User otherUser = userRepository.save(
                new User(
                        "dashboard-other-"
                                + unique
                                + "@example.com",
                        "unused-hash",
                        "USER"
                )
        );

        Long ownedProductId = null;
        Long candidateProductId = null;
        Long removedDeviceCandidateProductId = null;

        try {
            ownedProductId = db.queryForObject(
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

            candidateProductId = db.queryForObject(
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

            removedDeviceCandidateProductId = db.queryForObject(
                    """
                    INSERT INTO products (
                        brand,
                        model_name
                    )
                    VALUES (?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    "RemovedCandidateBrand-" + unique,
                    "Removed Candidate Model"
            );

            long currentDeviceId = db.queryForObject(
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

            long removedDeviceId = db.queryForObject(
                    """
                    INSERT INTO user_devices (
                        user_id,
                        product_id,
                        custom_name,
                        is_current
                    )
                    VALUES (?, ?, ?, FALSE)
                    RETURNING id
                    """,
                    Long.class,
                    owner.getId(),
                    ownedProductId,
                    "Removed phone"
            );

            long otherDeviceId = db.queryForObject(
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
                    otherUser.getId(),
                    ownedProductId,
                    "Other user's phone"
            );

            db.update(
                    """
                    INSERT INTO price_history (
                        product_id,
                        price,
                        currency,
                        observed_at
                    )
                    VALUES (
                        ?,
                        1099.00,
                        'SGD',
                        CURRENT_TIMESTAMP - INTERVAL '1 day'
                    )
                    """,
                    candidateProductId
            );

            db.update(
                    """
                    INSERT INTO price_history (
                        product_id,
                        price,
                        currency,
                        observed_at
                    )
                    VALUES (
                        ?,
                        1299.00,
                        'SGD',
                        CURRENT_TIMESTAMP
                    )
                    """,
                    candidateProductId
            );

            db.update(
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
                    VALUES (?, ?, ?, ?, ?, ?, 'SUPERSEDED')
                    """,
                    owner.getId(),
                    currentDeviceId,
                    candidateProductId,
                    "NO_MEANINGFUL_CHANGE",
                    "D",
                    "Old recommendation"
            );

            db.update(
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
                    """,
                    owner.getId(),
                    currentDeviceId,
                    candidateProductId,
                    "WORTH_CONSIDERING",
                    "A",
                    "Meaningful upgrade"
            );

            db.update(
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
                    """,
                    owner.getId(),
                    removedDeviceId,
                    removedDeviceCandidateProductId,
                    "STRONG_UPGRADE_CANDIDATE",
                    "A",
                    "Removed device recommendation"
            );

            db.update(
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
                    """,
                    otherUser.getId(),
                    otherDeviceId,
                    candidateProductId,
                    "STRONG_UPGRADE_CANDIDATE",
                    "A",
                    "Another user's recommendation"
            );

            mvc.perform(
                            MockMvcRequestBuilders.get(
                                    "/api/dashboard/recommendations"
                            )
                    )
                    .andExpect(status().isUnauthorized());

            String token = jwtService.generateToken(owner);

            mvc.perform(
                            MockMvcRequestBuilders.get(
                                            "/api/dashboard/recommendations"
                                    )
                                    .header(
                                            "Authorization",
                                            "Bearer " + token
                                    )
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(
                            jsonPath("$[0].currentDeviceId")
                                    .value(currentDeviceId)
                    )
                    .andExpect(
                            jsonPath("$[0].currentDeviceName")
                                    .value("Daily phone")
                    )
                    .andExpect(
                            jsonPath("$[0].candidateProductId")
                                    .value(candidateProductId)
                    )
                    .andExpect(
                            jsonPath("$[0].candidateModelName")
                                    .value("Candidate Model")
                    )
                    .andExpect(
                            jsonPath("$[0].latestPrice")
                                    .value(1299.00)
                    )
                    .andExpect(
                            jsonPath("$[0].currency")
                                    .value("SGD")
                    )
                    .andExpect(
                            jsonPath("$[0].verdict")
                                    .value("WORTH_CONSIDERING")
                    )
                    .andExpect(
                            jsonPath("$[0].confidence")
                                    .value("A")
                    );
        } finally {
            userRepository.deleteById(owner.getId());
            userRepository.deleteById(otherUser.getId());

            if (removedDeviceCandidateProductId != null) {
                db.update(
                        "DELETE FROM products WHERE id = ?",
                        removedDeviceCandidateProductId
                );
            }

            if (candidateProductId != null) {
                db.update(
                        "DELETE FROM products WHERE id = ?",
                        candidateProductId
                );
            }

            if (ownedProductId != null) {
                db.update(
                        "DELETE FROM products WHERE id = ?",
                        ownedProductId
                );
            }
        }
    }
}