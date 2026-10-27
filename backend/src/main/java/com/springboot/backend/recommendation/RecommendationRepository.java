package com.springboot.backend.recommendation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.springboot.backend.dto.DashboardRecommendationResponse;
import com.springboot.backend.recommendation.RecommendationsPersisted.NotableRecommendation;
import com.springboot.backend.recommendation.classification.SpecFactorCatalog;
import com.springboot.backend.recommendation.classification.TierMapper;
import tools.jackson.databind.JsonNode;

/**
 * Typed-column persistence for {@code recommendations}, plus the degraded-path
 * {@code system_log} write. {@code recommendations} has real relational
 * columns rather than one JSONB blob, but it's write-once-per-assessment with
 * no update-in-place lifecycle and no joins beyond FKs that already exist -
 * not enough to justify introducing the first JPA entity in this codebase
 * (spring-boot-starter-data-jpa is on the classpath but unused everywhere
 * else too). JdbcTemplate + TransactionTemplate follows the one existing
 * persistence precedent, {@link com.springboot.backend.ingestion.run.RunStore}.
 */
@Component
public class RecommendationRepository {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private static final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public RecommendationRepository(
            JdbcTemplate db, PlatformTransactionManager manager, ApplicationEventPublisher events) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.events = events;
    }

    /**
     * Persists the recommendation and, when {@code degradedLog} is non-null,
     * the accompanying {@code system_log} failure row - in the same
     * transaction, so the failure and the recommendation it belongs to are
     * never written separately (AGENTS.md §10). The previous ACTIVE row for
     * the same (user, device, candidate) tuple, if any, is superseded first:
     * each owned device keeps its own current answer.
     */
    public long save(RecommendationRecord rec, Map<String, Object> degradedLog) {
        Long id = tx.execute(status -> {
            Notables notables = new Notables();
            long newId = supersedeAndInsert(rec, notables);
            notables.publish();

            if (degradedLog != null) {
                db.update(
                        "INSERT INTO system_log(component,status,message,metadata) VALUES (?,?,?,?::jsonb)",
                        degradedLog.get("component"),
                        degradedLog.get("status"),
                        degradedLog.get("message"),
                        json.writeValueAsString(degradedLog.get("metadata")));
            }
            return newId;
        });
        return id;
    }

    /**
     * Replaces one owned device's deterministic results in a single
     * transaction: a run either lands completely or not at all.
     *
     * <p>Rows for candidates that are no longer on the device's shortlist are
     * <strong>deleted</strong>, history included, not superseded. A product that
     * dropped out (over budget, delisted) is not a recommendation, and the user
     * has no use for a record that it once was (AGENTS.md §18.11). Each record
     * then goes through the usual supersede-and-insert, so a candidate that is
     * still shortlisted keeps its history.
     *
     * @param currentDeviceId       the owned device whose rows are replaced
     * @param shortlistedProductIds every candidate still on the shortlist,
     *                              including ones that could not be classified -
     *                              those keep their previous rows untouched
     * @param records               the new rows, one per classified candidate
     * @return how many rows were deleted
     */
    public int replaceForDevice(
            long currentDeviceId, Collection<Long> shortlistedProductIds, List<RecommendationRecord> records) {

        Integer deleted = tx.execute(status -> {
            int removed = deleteDropped(currentDeviceId, shortlistedProductIds);
            Notables notables = new Notables();
            records.forEach(rec -> supersedeAndInsert(rec, notables));
            notables.publish();
            return removed;
        });
        return deleted;
    }

    public List<DashboardRecommendationResponse>
        findActiveDashboardRecommendationsByUserId(
                long userId, RecommendationSort sort) {

    // sort.orderBy() is a fixed whitelisted fragment (never request text), so
    // concatenating it is safe; ORDER BY cannot take bound parameters.
    String sql = """
            SELECT
                r.id AS recommendation_id,
                r.current_device_id,
                COALESCE(
                    NULLIF(BTRIM(ud.custom_name), ''),
                    NULLIF(
                        CONCAT_WS(
                            ' ',
                            owned_product.brand,
                            owned_product.model_name
                        ),
                        ''
                    ),
                    'Unnamed device'
                ) AS current_device_name,
                r.candidate_product_id,
                candidate.brand AS candidate_brand,
                candidate.model_name AS candidate_model_name,
                latest_price.price AS latest_price,
                BTRIM(latest_price.currency)
                    AS price_currency,
                r.verdict,
                r.confidence,
                r.reasoning,
                r.starred,
                r.created_at,
                r.input_snapshot -> 'computed' -> 'spec_deltas'
                    AS spec_deltas
            FROM recommendations r
            JOIN user_devices ud
                ON ud.id = r.current_device_id
                AND ud.user_id = r.user_id
                AND ud.is_current = TRUE
            LEFT JOIN products owned_product
                ON owned_product.id = ud.product_id
            JOIN products candidate
                ON candidate.id = r.candidate_product_id
            LEFT JOIN LATERAL (
                SELECT
                    ph.price,
                    ph.currency
                FROM price_history ph
                WHERE ph.product_id =
                    r.candidate_product_id
                ORDER BY
                    ph.observed_at DESC,
                    ph.id DESC
                LIMIT 1
            ) latest_price ON TRUE
            WHERE r.user_id = ?
                AND r.status = 'ACTIVE'
            ORDER BY\s""" + sort.orderBy();

    return db.query(
            sql,
            (resultSet, rowNumber) ->
                    new DashboardRecommendationResponse(
                            resultSet.getLong(
                                    "recommendation_id"
                            ),
                            resultSet.getLong(
                                    "current_device_id"
                            ),
                            resultSet.getString(
                                    "current_device_name"
                            ),
                            resultSet.getLong(
                                    "candidate_product_id"
                            ),
                            resultSet.getString(
                                    "candidate_brand"
                            ),
                            resultSet.getString(
                                    "candidate_model_name"
                            ),
                            resultSet.getBigDecimal(
                                    "latest_price"
                            ),
                            resultSet.getString(
                                    "price_currency"
                            ),
                            resultSet.getString(
                                    "verdict"
                            ),
                            resultSet.getString(
                                    "confidence"
                            ),
                            resultSet.getString(
                                    "reasoning"
                            ),
                            resultSet.getBoolean(
                                    "starred"
                            ),
                            resultSet.getObject(
                                    "created_at",
                                    java.time.OffsetDateTime.class
                            ),
                            "ORIGINAL",
                            null,
                            specChanges(resultSet.getString(
                                    "spec_deltas"
                            ))
                    ),
            userId
    );
}

    /**
     * Numeric spec changes from {@code input_snapshot.computed.spec_deltas}
     * ({@code {spec: {current, candidate, delta_pct}}}), biggest change first.
     * The snapshot stores the raw observed change, so each one is
     * direction-corrected with the same {@link SpecFactorCatalog} rule the verdict
     * scores with: positive always means the candidate is better. Text specs and
     * {@code price} (budget headroom, not a spec) are left out. Parsed leniently:
     * rows written under an older shape yield an empty list rather than failing
     * the whole dashboard.
     */
    static List<DashboardRecommendationResponse.SpecChange> specChanges(String specDeltasJson) {
        if (specDeltasJson == null) {
            return List.of();
        }
        List<DashboardRecommendationResponse.SpecChange> changes = new java.util.ArrayList<>();
        try {
            json.readTree(specDeltasJson).properties().forEach(entry -> {
                Boolean higherIsBetter = HIGHER_IS_BETTER.get(entry.getKey());
                JsonNode deltaPct = entry.getValue().path("delta_pct");
                if (higherIsBetter != null && deltaPct.isNumber()) {
                    double raw = deltaPct.asDouble();
                    changes.add(new DashboardRecommendationResponse.SpecChange(
                            entry.getKey(), higherIsBetter ? raw : -raw));
                }
            });
        } catch (RuntimeException malformed) {
            return List.of();
        }
        changes.sort(java.util.Comparator.comparingDouble(
                (DashboardRecommendationResponse.SpecChange change) -> -Math.abs(change.deltaPct())));
        return List.copyOf(changes);
    }

    /** Scored numeric specs and their direction, straight from the verdict's catalogue. */
    private static final Map<String, Boolean> HIGHER_IS_BETTER = higherIsBetter();

    private static Map<String, Boolean> higherIsBetter() {
        Map<String, Boolean> rules = new java.util.HashMap<>();
        SpecFactorCatalog.NUMERIC_SPECS.forEach(rule -> rules.put(rule.spec(), rule.higherIsBetter()));
        rules.put(SpecFactorCatalog.BENCHMARK_SPEC, true);
        return Map.copyOf(rules);
    }

    /**
     * {@link #replaceForDevice} for one candidate of one device, in a single
     * transaction. When the candidate is no longer shortlisted, this device's rows
     * for it are deleted, history included; otherwise each record goes through
     * supersede-and-insert. Rows for every other candidate are left alone.
     *
     * @return how many rows were deleted
     */
    public int replaceForCandidate(
            long currentDeviceId, long candidateProductId, boolean shortlisted, List<RecommendationRecord> records) {

        Integer deleted = tx.execute(status -> {
            int removed = shortlisted
                    ? 0
                    : db.update(
                            "DELETE FROM recommendations WHERE current_device_id=? AND candidate_product_id=?",
                            currentDeviceId, candidateProductId);
            Notables notables = new Notables();
            records.forEach(rec -> supersedeAndInsert(rec, notables));
            notables.publish();
            return removed;
        });
        return deleted;
    }

    /**
     * Stars or unstars one of the user's ACTIVE recommendations for a current
     * owned device - exactly the rows the dashboard shows. Ownership is part of
     * the WHERE clause, so another user's id behaves like an unknown one.
     *
     * @return false when no such recommendation exists for this user
     */
    public boolean setStarred(long userId, long recommendationId, boolean starred) {
        return db.update(
                "UPDATE recommendations r SET starred=? "
                        + "FROM user_devices ud "
                        + "WHERE r.id=? AND r.user_id=? AND r.status='ACTIVE' "
                        + "AND ud.id=r.current_device_id AND ud.user_id=r.user_id AND ud.is_current=TRUE",
                starred, recommendationId, userId) > 0;
    }

    /**
     * Supersedes the previous ACTIVE row for the same (user, device, candidate)
     * tuple, if any, then inserts the new one. The user's star is carried onto
     * the new row, so re-assessment never silently unsaves it.
     *
     * <p>The new row is compared with this device's own most recent row for the
     * candidate, superseded or not, and recorded in {@code notables} only when
     * it is not {@code NO_MEANINGFUL_CHANGE} and there is no such row or its
     * verdict differs. Every notification linking to an older row for this
     * device and candidate is re-pointed at the new row. Callers own the
     * transaction.
     */
    private long supersedeAndInsert(RecommendationRecord rec, Notables notables) {
        List<String> deviceHistory = db.queryForList(
                "SELECT verdict FROM recommendations "
                        + "WHERE user_id=? AND candidate_product_id=? AND current_device_id IS NOT DISTINCT FROM ?::bigint "
                        + "ORDER BY created_at DESC, id DESC LIMIT 1",
                String.class,
                rec.userId(), rec.candidateProductId(), rec.currentDeviceId());

        boolean starred = db.queryForList(
                "UPDATE recommendations SET status='SUPERSEDED' "
                        + "WHERE user_id=? AND candidate_product_id=? "
                        + "AND current_device_id IS NOT DISTINCT FROM ?::bigint AND status='ACTIVE' "
                        + "RETURNING starred",
                Boolean.class,
                rec.userId(), rec.candidateProductId(), rec.currentDeviceId())
                .contains(Boolean.TRUE);

        long newId = db.queryForObject(
                "INSERT INTO recommendations "
                        + "(user_id, current_device_id, candidate_product_id, trigger_event_id, verdict, "
                        + "confidence, input_snapshot, factor_analysis, reasoning, ai_model, prompt_version, "
                        + "starred) "
                        + "VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?) RETURNING id",
                Long.class,
                rec.userId(),
                rec.currentDeviceId(),
                rec.candidateProductId(),
                rec.triggerEventId(),
                rec.verdict(),
                rec.confidence(),
                json.writeValueAsString(rec.inputSnapshot()),
                json.writeValueAsString(rec.factorAnalysis()),
                rec.reasoning(),
                rec.aiModel(),
                rec.promptVersion(),
                starred);

        events.publishEvent(new RecommendationSaved(newId));

        if (!deviceHistory.isEmpty()) {
            db.update(
                    "UPDATE notifications SET recommendation_id=? WHERE recommendation_id IN ("
                            + "SELECT id FROM recommendations WHERE user_id=? AND candidate_product_id=? "
                            + "AND current_device_id IS NOT DISTINCT FROM ?::bigint AND id<>?)",
                    newId, rec.userId(), rec.candidateProductId(), rec.currentDeviceId(), newId);
        }

        boolean verdictChanged = deviceHistory.isEmpty() || !rec.verdict().equals(deviceHistory.getFirst());
        if (verdictChanged && !TierMapper.NO_MEANINGFUL_CHANGE.equals(rec.verdict())) {
            notables.add(rec, new NotableRecommendation(newId, rec.candidateProductId(), rec.verdict()));
        }
        return newId;
    }

    /**
     * Notable recommendations from one save, grouped per (user, device), each
     * group published as one {@link RecommendationsPersisted} - so one
     * evaluation of one device produces at most one notification.
     */
    private final class Notables {
        private record Key(long userId, Long currentDeviceId) {}

        private final Map<Key, List<NotableRecommendation>> byDevice = new LinkedHashMap<>();

        void add(RecommendationRecord rec, NotableRecommendation notable) {
            byDevice.computeIfAbsent(new Key(rec.userId(), rec.currentDeviceId()), key -> new ArrayList<>())
                    .add(notable);
        }

        void publish() {
            byDevice.forEach((key, notable) ->
                    events.publishEvent(new RecommendationsPersisted(key.userId(), key.currentDeviceId(), notable)));
        }
    }

    private int deleteDropped(long currentDeviceId, Collection<Long> shortlistedProductIds) {
        if (shortlistedProductIds.isEmpty()) {
            return db.update("DELETE FROM recommendations WHERE current_device_id=?", currentDeviceId);
        }
        String placeholders = String.join(",", Collections.nCopies(shortlistedProductIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(currentDeviceId);
        args.addAll(shortlistedProductIds);
        return db.update(
                "DELETE FROM recommendations WHERE current_device_id=? "
                        + "AND candidate_product_id NOT IN (" + placeholders + ")",
                args.toArray());
    }
}
