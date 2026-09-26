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

/**
 * Typed-column persistence for {@code recommendations}, plus the degraded-path
 * {@code system_log} write. {@code recommendations} has real relational
 * columns rather than one JSONB blob, but it's write-once-per-assessment with
 * no update-in-place lifecycle and no joins beyond FKs that already exist -
 * not enough to justify introducing the first JPA entity in this codebase
 * (spring-boot-starter-data-jpa is on the classpath but unused everywhere
 * else too). JdbcTemplate + TransactionTemplate follows the one existing
 * persistence precedent, {@link com.springboot.backend.ingestion.RunStore}.
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
