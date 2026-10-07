package com.springboot.backend.marketevent;

import com.springboot.backend.exception.ResourceNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place {@code market_events} rows are written.
 *
 * <p>Every writer goes through {@link #record}: admin entry and catalogue ingestion
 * today, any future scraper sink tomorrow. That is what lets the market-event
 * recommendation trigger hang off a single {@link MarketEventRecorded} publication
 * instead of being wired into each source separately.
 *
 * <p>{@code JdbcTemplate} rather than JPA, following the other write-mostly tables
 * ({@code recommendations}, {@code system_log}).
 */
@Service
public class MarketEventService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final JdbcTemplate db;
    private final ApplicationEventPublisher events;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public MarketEventService(JdbcTemplate db, ApplicationEventPublisher events) {
        this.db = db;
        this.events = events;
    }

    /**
     * Persists one event and announces it. Joins the caller's transaction when
     * there is one, so the event commits (and the trigger fires) together with
     * whatever change it describes.
     *
     * @throws ResourceNotFoundException when {@code productId} names no product
     */
    @Transactional
    public MarketEvent record(NewMarketEvent event) {
        if (event.productId() != null) {
            Boolean exists = db.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM products WHERE id = ?)", Boolean.class, event.productId());
            if (!Boolean.TRUE.equals(exists)) {
                throw new ResourceNotFoundException("Product " + event.productId() + " not found");
            }
        }

        MarketEvent saved = db.queryForObject(
                """
                INSERT INTO market_events (product_id, event_type, title, description, old_value, new_value, source)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                RETURNING id, product_id, event_type, title, description,
                          old_value::text AS old_value, new_value::text AS new_value, source, detected_at
                """,
                this::map,
                event.productId(),
                event.eventType().name(),
                event.title(),
                event.description(),
                toJson(event.oldValue()),
                toJson(event.newValue()),
                event.source());

        events.publishEvent(new MarketEventRecorded(saved.id()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<MarketEvent> find(long id) {
        return db.query(
                        """
                        SELECT id, product_id, event_type, title, description,
                               old_value::text AS old_value, new_value::text AS new_value, source, detected_at
                        FROM market_events WHERE id = ?
                        """,
                        this::map,
                        id)
                .stream()
                .findFirst();
    }

    private MarketEvent map(ResultSet rs, int row) throws SQLException {
        Long productId = rs.getObject("product_id", Long.class);
        return new MarketEvent(
                rs.getLong("id"),
                productId,
                MarketEventType.valueOf(rs.getString("event_type")),
                rs.getString("title"),
                rs.getString("description"),
                fromJson(rs.getString("old_value")),
                fromJson(rs.getString("new_value")),
                rs.getString("source"),
                rs.getObject("detected_at", OffsetDateTime.class));
    }

    private String toJson(Map<String, Object> value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    private Map<String, Object> fromJson(String raw) {
        return raw == null ? null : json.readValue(raw, JSON_OBJECT);
    }

    /** What a writer supplies; the id and detection time are assigned on insert. */
    public record NewMarketEvent(
            Long productId,
            MarketEventType eventType,
            String title,
            String description,
            Map<String, Object> oldValue,
            Map<String, Object> newValue,
            String source) {}
}
