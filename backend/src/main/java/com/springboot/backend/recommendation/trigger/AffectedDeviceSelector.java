package com.springboot.backend.recommendation.trigger;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which owned devices a change to one product affects.
 *
 * <p>Only devices linked to a catalogue product have a known category, so a
 * manual device ({@code product_id IS NULL}) can never be matched. A device that
 * already <em>is</em> the changed product is excluded: a product is never an
 * upgrade candidate for itself.
 */
@Component
public class AffectedDeviceSelector {

    private final JdbcTemplate db;

    public AffectedDeviceSelector(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * Every current device whose catalogue product shares {@code category},
     * other than devices that are {@code productId} itself, in id order.
     */
    public Selection forProduct(String category, long productId) {
        List<Long> evaluable = new ArrayList<>();
        List<Long> withoutPreferences = new ArrayList<>();

        db.query(
                """
                SELECT d.id, (p.user_device_id IS NOT NULL) AS has_preferences
                FROM user_devices d
                JOIN products owned ON owned.id = d.product_id
                LEFT JOIN device_preferences p ON p.user_device_id = d.id
                WHERE d.is_current
                  AND owned.category = ?
                  AND d.product_id <> ?
                ORDER BY d.id
                """,
                rs -> {
                    (rs.getBoolean("has_preferences") ? evaluable : withoutPreferences).add(rs.getLong("id"));
                },
                category,
                productId);

        return new Selection(evaluable, withoutPreferences);
    }

    /**
     * @param evaluable          devices the pipeline can evaluate
     * @param withoutPreferences devices in the category with no budget recorded,
     *                           which the pipeline cannot shortlist against
     */
    public record Selection(List<Long> evaluable, List<Long> withoutPreferences) {

        public Selection {
            evaluable = List.copyOf(evaluable);
            withoutPreferences = List.copyOf(withoutPreferences);
        }
    }
}
