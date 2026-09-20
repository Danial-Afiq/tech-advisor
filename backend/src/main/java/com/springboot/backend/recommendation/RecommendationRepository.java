package com.springboot.backend.recommendation;

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
            db.update(
                    "UPDATE recommendations SET status='SUPERSEDED' "
                            + "WHERE user_id=? AND candidate_product_id=? AND status='ACTIVE'",
                    rec.userId(), rec.candidateProductId());

            long newId = db.queryForObject(
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
}
