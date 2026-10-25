package com.springboot.backend;

import com.springboot.backend.model.DevicePreference;
import com.springboot.backend.model.Product;
import com.springboot.backend.model.User;
import com.springboot.backend.model.UserDevice;
import com.springboot.backend.recommendation.AssessVocabulary;
import com.springboot.backend.recommendation.CandidateEvaluation;
import com.springboot.backend.recommendation.classification.Factors;
import com.springboot.backend.recommendation.classification.SpecComparison;
import com.springboot.backend.recommendation.classification.UpgradeClassification;
import com.springboot.backend.recommendation.classification.UpgradeScore;
import com.springboot.backend.recommendation.trigger.AssessRequestFactory;
import com.springboot.backend.repository.CandidateProduct;
import com.springboot.backend.repository.DevicePreferenceRepository;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP requests commit normally, so subsequent repository reads verify real persistence. */
@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class DevicePreferencesIntegrationTest {

    @Autowired WebApplicationContext web;
    @Autowired UserRepository users;
    @Autowired UserDeviceRepository devices;
    @Autowired DevicePreferenceRepository preferences;
    @Autowired ProductRepository products;
    @Autowired AssessRequestFactory assessmentFactory;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate db;

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Long> fixtureProductIds = new ArrayList<>();
    private MockMvc mvc;
    private User owner;
    private User other;
    private String bearer;
    private Long deviceId;

    @BeforeEach
    void setUp() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Persistence tests must use a dedicated _test database");
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        owner = users.save(new User("preferences-" + UUID.randomUUID() + "@example.com", "hashed", "USER"));
        other = users.save(new User("preferences-" + UUID.randomUUID() + "@example.com", "hashed", "USER"));
        bearer = "Bearer " + jwt.generateToken(owner);
        deviceId = devices.save(new UserDevice(owner.getId(), null, "My phone")).getId();
    }

    @AfterEach
    void cleanUp() {
        // FK cascades remove only this test's owned devices and preferences.
        if (owner != null) users.deleteById(owner.getId());
        if (other != null) users.deleteById(other.getId());
        fixtureProductIds.forEach(products::deleteById);
    }

    @Test
    void createPersistsAllFieldsForTheNewOwnedDevice() throws Exception {
        String body = mvc.perform(post("/api/devices").header("Authorization", bearer)
                        .contentType("application/json").content(json.writeValueAsString(fullRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.upgradeUrgency").value("URGENT"))
                .andExpect(jsonPath("$.brandFlexibility").value("SOMEWHAT_FLEXIBLE"))
                .andReturn().getResponse().getContentAsString();
        Long createdId = json.readTree(body).get("id").longValue();
        assertEquals(owner.getId(), devices.findById(createdId).orElseThrow().getUserId());
        assertSavedFields(createdId);
        assertTrue(preferences.findById(deviceId).isEmpty());
        assertResponseFields(createdId);
    }

    @Test
    void updateAndListRoundTripEveryFieldWithoutDuplicates() throws Exception {
        save(deviceId, fullRequest()).andExpect(status().isOk());
        DevicePreference first = preferences.findById(deviceId).orElseThrow();
        var createdAt = first.getCreatedAt();
        assertSavedFields(deviceId);
        assertResponseFields(deviceId);

        String listed = mvc.perform(get("/api/devices").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(deviceId))
                .andExpect(jsonPath("$[0].budget").value(1234.56))
                .andExpect(jsonPath("$[0].upgradeUrgency").value("URGENT"))
                .andExpect(jsonPath("$[0].brandFlexibility").value("SOMEWHAT_FLEXIBLE"))
                .andExpect(jsonPath("$[0].notes").value("Keep this phone for four years.\nAvoid launch pricing."))
                .andReturn().getResponse().getContentAsString();
        assertPreferenceResponse(json.readTree(listed).get(0), fullRequest());

        Map<String, Object> changed = fullRequest();
        changed.put("budget", 2500);
        changed.put("currency", "SGD");
        changed.put("upgradeUrgency", "NOT_URGENT");
        changed.put("brandFlexibility", "FLEXIBLE");
        changed.put("priorities", "{\"display\":3}");
        changed.put("painPoints", "\"Screen is small\"");
        changed.put("notes", "");
        save(deviceId, changed).andExpect(status().isOk());
        save(deviceId, changed).andExpect(status().isOk());

        DevicePreference updated = preferences.findById(deviceId).orElseThrow();
        assertEquals(0, new BigDecimal("2500.00").compareTo(updated.getBudget()));
        assertEquals("SGD", updated.getCurrency());
        assertEquals("NOT_URGENT", updated.getUpgradeUrgency());
        assertEquals("FLEXIBLE", updated.getBrandFlexibility());
        assertEquals(json.readTree("{\"display\":3}"), json.readTree(updated.getPriorities()));
        assertEquals("Screen is small", json.readTree(updated.getPainPoints()).stringValue());
        assertEquals("", updated.getNotes());
        assertEquals(createdAt, updated.getCreatedAt());
        assertFalse(updated.getUpdatedAt().isBefore(first.getUpdatedAt()));
        assertEquals(1, db.queryForObject(
                "SELECT count(*) FROM device_preferences WHERE user_device_id = ?", Integer.class, deviceId));
        assertResponseFields(deviceId, changed);
    }

    @Test
    void omittedFieldsPreserveValuesAndExplicitEmptyFieldsClearThem() throws Exception {
        save(deviceId, fullRequest()).andExpect(status().isOk());
        save(deviceId, Map.of("customName", "My phone", "budget", 900)).andExpect(status().isOk());
        DevicePreference saved = preferences.findById(deviceId).orElseThrow();
        assertEquals("USD", saved.getCurrency());
        assertEquals("URGENT", saved.getUpgradeUrgency());
        assertEquals("SOMEWHAT_FLEXIBLE", saved.getBrandFlexibility());
        assertEquals(json.readTree(fullRequest().get("priorities").toString()), json.readTree(saved.getPriorities()));

        save(deviceId, Map.of("customName", "My phone", "priorities", "{}", "painPoints", "[]", "notes", ""))
                .andExpect(status().isOk());
        saved = preferences.findById(deviceId).orElseThrow();
        assertEquals(0, new BigDecimal("900").compareTo(saved.getBudget()));
        assertEquals(json.readTree("{}"), json.readTree(saved.getPriorities()));
        assertEquals(json.readTree("[]"), json.readTree(saved.getPainPoints()));
        assertEquals("", saved.getNotes());
    }

    @Test
    void preferencesStayIsolatedBetweenDevicesAndUsers() throws Exception {
        Long secondId = devices.save(new UserDevice(owner.getId(), null, "My second phone")).getId();
        Long otherId = devices.save(new UserDevice(other.getId(), null, "Another user's phone")).getId();
        save(deviceId, fullRequest()).andExpect(status().isOk());
        save(secondId, Map.of("customName", "My second phone", "budget", 500, "notes", "Second device only"))
                .andExpect(status().isOk());

        assertSavedFields(deviceId);
        assertEquals("SGD", preferences.findById(secondId).orElseThrow().getCurrency());
        assertEquals("Second device only", preferences.findById(secondId).orElseThrow().getNotes());
        assertTrue(preferences.findById(otherId).isEmpty());
        mvc.perform(get("/api/devices").header("Authorization", "Bearer " + jwt.generateToken(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(otherId))
                .andExpect(jsonPath("$[0].budget").isEmpty())
                .andExpect(jsonPath("$[0].notes").isEmpty());
    }

    @Test
    void nonexistentOtherUsersAndRemovedDevicesCannotBeReadOrChanged() throws Exception {
        Long otherId = devices.save(new UserDevice(other.getId(), null, "Private phone")).getId();
        DevicePreference privatePreferences = new DevicePreference(otherId, new BigDecimal("777"), "USD");
        privatePreferences.setNotes("Private notes");
        preferences.save(privatePreferences);
        UserDevice removed = devices.save(new UserDevice(owner.getId(), null, "Removed phone"));
        removed.setCurrent(false);
        devices.save(removed);

        for (Long forbidden : new Long[] {Long.MAX_VALUE, otherId, removed.getId()}) {
            mvc.perform(get("/api/devices/{id}", forbidden).header("Authorization", bearer))
                    .andExpect(status().isNotFound());
            save(forbidden, fullRequest()).andExpect(status().isNotFound());
        }
        assertEquals("Private notes", preferences.findById(otherId).orElseThrow().getNotes());
        assertTrue(preferences.findById(removed.getId()).isEmpty());
        assertTrue(preferences.findById(deviceId).isEmpty());
    }

    @Test
    void authenticationIsRequiredForReadsAndWrites() throws Exception {
        mvc.perform(get("/api/devices/{id}", deviceId)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/devices/{id}", deviceId).contentType("application/json")
                .content(json.writeValueAsString(fullRequest()))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/devices").header("Authorization", "Bearer invalid")
                .contentType("application/json").content(json.writeValueAsString(fullRequest())))
                .andExpect(status().isUnauthorized());
        assertTrue(preferences.findById(deviceId).isEmpty());
    }

    @Test
    void firstPreferencesRequireBudgetAndInvalidCreateRollsBackDevice() throws Exception {
        save(deviceId, Map.of("customName", "My phone", "upgradeUrgency", "URGENT"))
                .andExpect(status().isBadRequest());
        assertTrue(preferences.findById(deviceId).isEmpty());
        mvc.perform(post("/api/devices").header("Authorization", bearer).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("customName", "Must roll back", "notes", "No budget"))))
                .andExpect(status().isBadRequest());
        assertEquals(1, devices.findAllByUserIdAndIsCurrentTrueOrderByCreatedAtDesc(owner.getId()).size());
    }

    @Test
    void aDeviceWithoutPreferencesReturnsNullForEveryPreferenceField() throws Exception {
        String body = mvc.perform(get("/api/devices/{id}", deviceId).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode response = json.readTree(body);
        for (String field : new String[] {
                "budget", "currency", "upgradeUrgency", "brandFlexibility", "priorities", "painPoints", "notes"}) {
            assertTrue(response.has(field), () -> "Missing response field: " + field);
            assertTrue(response.get(field).isNull(), () -> "Invented preference value: " + field);
        }
        assertTrue(preferences.findById(deviceId).isEmpty());
    }

    @Test
    void notesOnlyAndExplicitNullUpdatesPreserveAllOtherPreferences() throws Exception {
        save(deviceId, fullRequest()).andExpect(status().isOk());
        Map<String, Object> changed = fullRequest();
        changed.put("notes", "Only these notes changed.");
        save(deviceId, Map.of("customName", "My phone", "notes", changed.get("notes")))
                .andExpect(status().isOk());
        assertSavedFields(deviceId, changed);

        Map<String, Object> omitted = new LinkedHashMap<>();
        omitted.put("customName", "My phone");
        for (String field : changed.keySet()) {
            if (!field.equals("customName")) omitted.put(field, null);
        }
        save(deviceId, omitted).andExpect(status().isOk());
        assertSavedFields(deviceId, changed);
        assertResponseFields(deviceId, changed);
    }

    @Test
    void everySupportedFactorUrgencyAndBrandFlexibilityCanBePersisted() throws Exception {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (int index = 0; index < Factors.ALL.size(); index++) weights.put(Factors.ALL.get(index), index + 1);
        assertEquals(AssessVocabulary.UPGRADE_URGENCIES.size(), AssessVocabulary.BRAND_FLEXIBILITIES.size());
        for (int index = 0; index < AssessVocabulary.UPGRADE_URGENCIES.size(); index++) {
            Map<String, Object> request = fullRequest();
            request.put("priorities", json.writeValueAsString(weights));
            request.put("upgradeUrgency", AssessVocabulary.UPGRADE_URGENCIES.get(index));
            request.put("brandFlexibility", AssessVocabulary.BRAND_FLEXIBILITIES.get(index));
            save(deviceId, request).andExpect(status().isOk());
            assertSavedFields(deviceId, request);
            assertResponseFields(deviceId, request);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "9999999999.99"})
    void budgetBoundariesRoundTripWithoutLosingDecimalPrecision(String budget) throws Exception {
        Map<String, Object> request = fullRequest();
        request.put("budget", new BigDecimal(budget));
        save(deviceId, request).andExpect(status().isOk());
        assertSavedFields(deviceId, request);
        assertResponseFields(deviceId, request);
    }

    @Test
    void anOlderBudgetOnlyProfileCanBeCompletedAndNotesRemainOptional() throws Exception {
        save(deviceId, Map.of("customName", "My phone", "budget", 800, "currency", "USD"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notes").isEmpty());
        assertEquals(json.readTree("{}"), json.readTree(preferences.findById(deviceId).orElseThrow().getPainPoints()));

        // The website converts the old JSONB default {} into JSON-encoded empty text.
        save(deviceId, Map.of("customName", "My phone", "upgradeUrgency", "NOT_URGENT",
                "brandFlexibility", "FLEXIBLE", "priorities", "{}", "painPoints", "\"\""))
                .andExpect(status().isOk());
        DevicePreference saved = preferences.findById(deviceId).orElseThrow();
        assertEquals(0, new BigDecimal("800").compareTo(saved.getBudget()));
        assertEquals("USD", saved.getCurrency());
        assertEquals("NOT_URGENT", saved.getUpgradeUrgency());
        assertEquals("FLEXIBLE", saved.getBrandFlexibility());
        assertEquals("", json.readTree(saved.getPainPoints()).stringValue());
        assertNull(saved.getNotes());
    }

    @Test
    void apiSavedPreferencesAreAvailableToRecommendationAnalysis() throws Exception {
        Product owned = saveFixtureProduct("Owned " + UUID.randomUUID());
        Product candidate = saveFixtureProduct("Candidate " + UUID.randomUUID());
        Map<String, Object> request = fullRequest();
        request.put("productId", owned.getId());
        request.put("condition", "FAIR");
        request.put("satisfactionScore", 45);
        request.put("purchaseDate", "2024-04-05");
        save(deviceId, request).andExpect(status().isOk());

        CandidateProduct shortlisted = new CandidateProduct() {
            public Long getProductId() { return candidate.getId(); }
            public String getBrand() { return candidate.getBrand(); }
            public String getModelName() { return candidate.getModelName(); }
            public BigDecimal getLatestPrice() { return new BigDecimal("1000"); }
            public String getCurrency() { return "USD"; }
        };
        UpgradeClassification classification = new UpgradeClassification("WORTH_CONSIDERING", 0.7,
                new UpgradeScore(0.7, true, Map.of(), List.of("battery"), 1.0),
                new SpecComparison(List.of(), Map.of(), null, null, List.of(), List.of()), "test");
        var built = assessmentFactory.build(deviceId, new CandidateEvaluation.Classified(shortlisted, classification), null);
        assertTrue(built.complete(), () -> "Missing analysis context: " + built.missing());
        var context = built.request().userContext().preferences();
        assertEquals(1234.56, context.budget());
        assertEquals("USD", context.currency());
        assertEquals("URGENT", context.upgradeUrgency());
        assertEquals("SOMEWHAT_FLEXIBLE", context.brandFlexibility());
        assertEquals(Map.of("battery", 5, "camera", 4, "longevity", 2), context.priorities());
        assertEquals("Battery drains quickly; Camera struggles at night", context.painPoints());
        assertEquals(request.get("notes"), context.notes());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "priorities|not-json", "priorities|[]", "priorities|null", "priorities|{\"unknown\":1}",
            "priorities|{\"battery\":1.5}", "priorities|{\"battery\":null}", "priorities|{\"battery\":2147483648}",
            "priorities|{\"battery\":\"5\"}", "priorities|{\"battery\":1} []",
            "painPoints|not-json", "painPoints|{}", "painPoints|[1]", "painPoints|null",
            "upgradeUrgency|whenever", "brandFlexibility|any brand", "currency|US", "budget|-1", "budget|1.234"
    })
    void invalidPreferencesAreRejectedWithoutChangingStoredValues(String field, String value) throws Exception {
        save(deviceId, fullRequest()).andExpect(status().isOk());
        Map<String, Object> request = fullRequest();
        request.put(field, field.equals("budget") ? new BigDecimal(value) : value);
        save(deviceId, request).andExpect(status().isBadRequest());
        assertSavedFields(deviceId);
    }

    private Map<String, Object> fullRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("customName", "My phone");
        request.put("budget", new BigDecimal("1234.56"));
        request.put("currency", "USD");
        request.put("upgradeUrgency", "URGENT");
        request.put("brandFlexibility", "SOMEWHAT_FLEXIBLE");
        request.put("priorities", "{\"battery\":5,\"camera\":4,\"longevity\":2}");
        request.put("painPoints", "[\"Battery drains quickly\",\"Camera struggles at night\"]");
        request.put("notes", "Keep this phone for four years.\nAvoid launch pricing.");
        return request;
    }

    private ResultActions save(Long id, Map<String, Object> request) throws Exception {
        return mvc.perform(put("/api/devices/{id}", id).header("Authorization", bearer)
                .contentType("application/json").content(json.writeValueAsString(request)));
    }

    private void assertSavedFields(Long id) {
        assertSavedFields(id, fullRequest());
    }

    private void assertSavedFields(Long id, Map<String, Object> expected) {
        DevicePreference saved = preferences.findById(id).orElseThrow();
        assertEquals(0, new BigDecimal(expected.get("budget").toString()).compareTo(saved.getBudget()));
        assertEquals(expected.get("currency"), saved.getCurrency());
        assertEquals(expected.get("upgradeUrgency"), saved.getUpgradeUrgency());
        assertEquals(expected.get("brandFlexibility"), saved.getBrandFlexibility());
        assertEquals(json.readTree(expected.get("priorities").toString()), json.readTree(saved.getPriorities()));
        assertEquals(json.readTree(expected.get("painPoints").toString()), json.readTree(saved.getPainPoints()));
        assertEquals(expected.get("notes"), saved.getNotes());
    }

    private void assertResponseFields(Long id) throws Exception {
        assertResponseFields(id, fullRequest());
    }

    private void assertResponseFields(Long id, Map<String, Object> expected) throws Exception {
        String body = mvc.perform(get("/api/devices/{id}", id).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertPreferenceResponse(json.readTree(body), expected);
    }

    private void assertPreferenceResponse(JsonNode response, Map<String, Object> expected) {
        assertEquals(0, new BigDecimal(expected.get("budget").toString()).compareTo(response.get("budget").decimalValue()));
        for (String field : new String[] {"currency", "upgradeUrgency", "brandFlexibility", "notes"}) {
            assertEquals(expected.get(field), response.get(field).stringValue());
        }
        for (String field : new String[] {"priorities", "painPoints"}) {
            assertEquals(json.readTree(expected.get(field).toString()), json.readTree(response.get(field).stringValue()));
        }
    }

    private Product saveFixtureProduct(String model) {
        Product product = new Product("SCRUM-38", model, Product.CATEGORY_SMARTPHONE, Product.STATUS_VERIFIED);
        product.setReleaseDate(LocalDate.of(2025, 1, 1));
        product = products.save(product);
        fixtureProductIds.add(product.getId());
        return product;
    }
}
