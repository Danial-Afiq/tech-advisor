package com.springboot.backend;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Ticket 4.3.2. Runs against the real PostgreSQL test database because the
 * query relies on LATERAL, NULLS LAST and JSONB casts.
 *
 * <pre>
 * Rec | Verdict                  | Score | Latest price | Created
 *  A  | WORTH_WATCHING           | 0.80  | 899 (500 earlier) | Oct 10
 *  B  | STRONG_UPGRADE_CANDIDATE | 0.88  | 1299         | Oct 5
 *  C  | STRONG_UPGRADE_CANDIDATE | 0.95  | none         | Oct 1
 *  D  | WORTH_CONSIDERING        | 0.75  | 649          | Oct 8
 *  E  | WORTH_CONSIDERING        | 0.75  | 749          | Oct 9
 *  F  | other user, STRONG, 0.99, price 1 - must never appear
 * </pre>
 */
@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "logging.level.root=WARN"
})
class DashboardSortIntegrationTest {

    @Autowired
    WebApplicationContext web;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtService jwtService;

    @Autowired
    JdbcTemplate db;

    private MockMvc mvc;
    private User owner;
    private User otherUser;
    private String token;
    private final List<Long> productIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        String unique = UUID.randomUUID().toString();
        owner = userRepository.save(new User("sort-owner-" + unique + "@example.com", "unused-hash", "USER"));
        otherUser = userRepository.save(new User("sort-other-" + unique + "@example.com", "unused-hash", "USER"));
        token = jwtService.generateToken(owner);

        long ownedProduct = product(unique, "Owned");
        long ownerDevice = device(owner, ownedProduct);
        long otherDevice = device(otherUser, ownedProduct);

        long a = product(unique, "A");
        price(a, "500.00", "2026-09-20T00:00:00Z");
        price(a, "899.00", "2026-10-03T00:00:00Z");
        recommendation(owner, ownerDevice, a, "WORTH_WATCHING", "0.80", "2026-10-10T00:00:00Z");

        long b = product(unique, "B");
        price(b, "1299.00", "2026-10-01T00:00:00Z");
        recommendation(owner, ownerDevice, b, "STRONG_UPGRADE_CANDIDATE", "0.88", "2026-10-05T00:00:00Z");

        long c = product(unique, "C");
        recommendation(owner, ownerDevice, c, "STRONG_UPGRADE_CANDIDATE", "0.95", "2026-10-01T00:00:00Z");

        long d = product(unique, "D");
        price(d, "649.00", "2026-10-01T00:00:00Z");
        recommendation(owner, ownerDevice, d, "WORTH_CONSIDERING", "0.75", "2026-10-08T00:00:00Z");

        long e = product(unique, "E");
        price(e, "749.00", "2026-10-01T00:00:00Z");
        recommendation(owner, ownerDevice, e, "WORTH_CONSIDERING", "0.75", "2026-10-09T00:00:00Z");

        long f = product(unique, "F");
        price(f, "1.00", "2026-10-01T00:00:00Z");
        recommendation(otherUser, otherDevice, f, "STRONG_UPGRADE_CANDIDATE", "0.99", "2026-10-11T00:00:00Z");
    }

    @AfterEach
    void cleanUp() {
        if (owner != null) {
            userRepository.deleteById(owner.getId());
        }
        if (otherUser != null) {
            userRepository.deleteById(otherUser.getId());
        }
        productIds.forEach(id -> db.update("DELETE FROM products WHERE id = ?", id));
    }

    @Test
    void omittedSortDefaultsToRelevance() throws Exception {
        get(null).andExpect(status().isOk()).andExpect(models("C", "B", "E", "D", "A"));
    }

    @Test
    void relevanceRanksTierAboveScoreAndBreaksTiesByNewest() throws Exception {
        // C outranks B on score alone (C is the older row, so recency can't explain it).
        // A has a higher score (0.80) than D/E (0.75) but a lower tier, so it is last.
        // D and E tie on tier and score, so the newer E comes first.
        get("relevance").andExpect(status().isOk()).andExpect(models("C", "B", "E", "D", "A"));
    }

    @Test
    void priceAscendingUsesLatestPriceAndPutsMissingLast() throws Exception {
        get("price_asc").andExpect(status().isOk()).andExpect(models("D", "E", "A", "B", "C"));
    }

    @Test
    void priceDescendingStillPutsMissingLast() throws Exception {
        get("price_desc").andExpect(status().isOk()).andExpect(models("B", "A", "E", "D", "C"));
    }

    @Test
    void sortingByPriceUsesTheLatestObservationNotTheEarlierOne() throws Exception {
        get("price_asc")
                .andExpect(jsonPath("$[2].candidateModelName").value("A"))
                .andExpect(jsonPath("$[2].latestPrice").value(899.00));
    }

    @Test
    void recencyListsNewestFirst() throws Exception {
        get("recency").andExpect(status().isOk()).andExpect(models("A", "E", "D", "B", "C"));
    }

    @Test
    void sortValueIsCaseInsensitive() throws Exception {
        get("PRICE_ASC").andExpect(status().isOk()).andExpect(models("D", "E", "A", "B", "C"));
    }

    @Test
    void unknownSortIsRejectedWithBadRequest() throws Exception {
        get("banana")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("relevance, price_asc, price_desc, recency")));
        get("relevance;DROP TABLE users").andExpect(status().isBadRequest());
    }

    @Test
    void otherUsersRecommendationsNeverAppearUnderAnySort() throws Exception {
        for (String sort : new String[] {null, "relevance", "price_asc", "price_desc", "recency"}) {
            get(sort).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(5))
                    .andExpect(jsonPath("$[?(@.candidateModelName == 'F')]").isEmpty());
        }
    }

    private ResultActions get(String sort) throws Exception {
        var request = MockMvcRequestBuilders.get("/api/dashboard/recommendations")
                .header("Authorization", "Bearer " + token);
        if (sort != null) {
            request = request.param("sort", sort);
        }
        return mvc.perform(request);
    }

    private static org.springframework.test.web.servlet.ResultMatcher models(String... expected) {
        return jsonPath("$[*].candidateModelName", contains((Object[]) expected));
    }

    private long product(String unique, String model) {
        Long id = db.queryForObject(
                "INSERT INTO products (brand, model_name) VALUES (?, ?) RETURNING id",
                Long.class, "SortBrand-" + unique, model);
        productIds.add(id);
        return id;
    }

    private long device(User user, long productId) {
        return db.queryForObject(
                "INSERT INTO user_devices (user_id, product_id, custom_name, is_current) "
                        + "VALUES (?, ?, 'Daily phone', TRUE) RETURNING id",
                Long.class, user.getId(), productId);
    }

    private void price(long productId, String price, String observedAt) {
        db.update(
                "INSERT INTO price_history (product_id, price, currency, observed_at) "
                        + "VALUES (?, ?::numeric, 'SGD', ?::timestamptz)",
                productId, price, observedAt);
    }

    private void recommendation(
            User user, long deviceId, long productId, String verdict, String score, String createdAt) {
        db.update(
                "INSERT INTO recommendations (user_id, current_device_id, candidate_product_id, verdict, "
                        + "confidence, factor_analysis, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'A', jsonb_build_object('deterministic', "
                        + "jsonb_build_object('upgrade_score', ?::numeric)), 'ACTIVE', ?::timestamptz)",
                user.getId(), deviceId, productId, verdict, score, createdAt);
    }
}
