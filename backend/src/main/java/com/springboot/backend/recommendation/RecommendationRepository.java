package com.springboot.backend.recommendation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.springboot.backend.dto.DashboardRecommendationResponse;

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
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public RecommendationRepository(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
    }

    /**
     * Persists the recommendation and, when {@code degradedLog} is non-null,
     * the accompanying {@code system_log} failure row - in the same
     * transaction, so the failure and the recommendation it belongs to are
     * never written separately (AGENTS.md §10). The previous ACTIVE row for
     * the same (user, candidate) pair, if any, is superseded first: this is
     * "per-user/per-product" persistence, one current answer per pair.
     */
    public long save(RecommendationRecord rec, Map<String, Object> degradedLog) {
        Long id = tx.execute(status -> {
            long newId = supersedeAndInsert(rec);

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
            records.forEach(this::supersedeAndInsert);
            return removed;
        });
        return deleted;
    }

    public List<DashboardRecommendationResponse>
        findActiveDashboardRecommendationsByUserId(
                long userId) {

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
                r.created_at
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
            ORDER BY
                r.created_at DESC,
                r.id DESC
            """;

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
                            resultSet.getObject(
                                    "created_at",
                                    java.time.OffsetDateTime.class
                            )
                    ),
            userId
    );
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
            records.forEach(this::supersedeAndInsert);
            return removed;
        });
        return deleted;
    }

    /**
     * Supersedes the previous ACTIVE row for the same (user, candidate) pair,
     * if any, then inserts the new one: this is "per-user/per-product"
     * persistence, one current answer per pair. Callers own the transaction.
     */
    private long supersedeAndInsert(RecommendationRecord rec) {
        db.update(
                "UPDATE recommendations SET status='SUPERSEDED' "
                        + "WHERE user_id=? AND candidate_product_id=? AND status='ACTIVE'",
                rec.userId(), rec.candidateProductId());

        return db.queryForObject(
                "INSERT INTO recommendations "
                        + "(user_id, current_device_id, candidate_product_id, trigger_event_id, verdict, "
                        + "confidence, input_snapshot, factor_analysis, reasoning, ai_model, prompt_version) "
                        + "VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?) RETURNING id",
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
                rec.promptVersion());
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
