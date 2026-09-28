package com.springboot.backend.recommendation.classification;

import com.springboot.backend.model.Phone;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/**
 * The single place that says which {@code phone} column speaks to which of the
 * twelve {@link Factors}, in which direction, and how large a change counts as
 * a full-strength improvement.
 *
 * <p>Keeping this in one table rather than scattered through the scorer is what
 * makes the scoring rules reviewable by a product owner: every judgement about
 * "how much better is this, really" is visible on one screen.
 *
 * <p><strong>Four factors are deliberately unscored.</strong> {@code camera},
 * {@code build_quality}, {@code thermals}, {@code connectivity} and
 * {@code audio} have no numerically comparable column in V6 -
 * {@code camera_specs} and {@code ip_rating} are free text, and the other three
 * have no column at all. They are listed in {@link #UNSCORED_FACTORS} rather
 * than silently contributing zero, because a factor that cannot be measured and
 * a factor that did not change must not look the same to a reader. A user whose
 * top priority is {@code camera} will see it excluded from the score and named
 * in the breakdown, and the owner-evidence channel still grades it.
 */
public final class SpecFactorCatalog {

    /**
     * @param spec           the {@code phone} column name, used as the wire key
     * @param factor         which factor this spec contributes to
     * @param higherIsBetter false for specs where a smaller number is an
     *                       improvement, such as weight
     * @param improvementCap the percentage change treated as full strength
     */
    public record SpecRule(
            String spec,
            String factor,
            boolean higherIsBetter,
            double improvementCap,
            Function<Phone, Number> reader) {}

    /** Factors no V6 column can measure. See the class note. */
    public static final List<String> UNSCORED_FACTORS = List.of(
            Factors.CAMERA, Factors.BUILD_QUALITY, Factors.THERMALS,
            Factors.CONNECTIVITY, Factors.AUDIO);

    /** A spec that is text, not a number: reportable but not scorable. */
    public record TextSpecRule(String spec, Function<Phone, String> reader) {}

    /**
     * Textual specs. Not scorable, but still sent to the model and stored in the
     * breakdown as context - which is exactly what {@code SpecDelta}'s
     * {@code float | str | None} union on the Python side exists for.
     */
    public static final List<TextSpecRule> TEXT_SPECS = List.of(
            new TextSpecRule("chipset", Phone::getChipset),
            new TextSpecRule("camera_specs", Phone::getCameraSpecs),
            new TextSpecRule("ip_rating", Phone::getIpRating),
            new TextSpecRule("os", Phone::getOs));

    public static final List<SpecRule> NUMERIC_SPECS = List.of(
            new SpecRule("battery_mah", Factors.BATTERY, true, 50.0, Phone::getBatteryMah),
            new SpecRule("wired_charging_watts", Factors.BATTERY, true, 100.0, Phone::getWiredChargingWatts),
            new SpecRule("wireless_charging_watts", Factors.BATTERY, true, 100.0, Phone::getWirelessChargingWatts),
            new SpecRule("ram_gb", Factors.PERFORMANCE, true, 100.0, Phone::getRamGb),
            new SpecRule("cpu_ghz", Factors.PERFORMANCE, true, 30.0, Phone::getCpuGhz),
            new SpecRule("storage_gb", Factors.LONGEVITY, true, 100.0, Phone::getStorageGb),
            new SpecRule("refresh_rate_hz", Factors.DISPLAY, true, 100.0, Phone::getRefreshRateHz),
            new SpecRule("display_size_inches", Factors.DISPLAY, true, 20.0, Phone::getDisplaySizeInches),
            new SpecRule("pixel_density", Factors.DISPLAY, true, 30.0, Phone::getPixelDensity),
            // Lighter is better, so the sign is inverted before scoring.
            new SpecRule("weight_g", Factors.PORTABILITY, false, 20.0, Phone::getWeightG),
            new SpecRule("software_support_years", Factors.SOFTWARE_SUPPORT, true, 100.0,
                    Phone::getSoftwareSupportYears));

    /** Synthetic spec keys that do not come from a {@code phone} column. */
    public static final String BENCHMARK_SPEC = "benchmark_score";
    public static final String PRICE_SPEC = "price";

    /** Uplift treated as a full-strength performance win. */
    public static final double BENCHMARK_IMPROVEMENT_CAP = 60.0;

    /** Budget headroom, as a percentage of budget, treated as full value. */
    public static final double PRICE_IMPROVEMENT_CAP = 30.0;

    static Double toDouble(Number value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal.doubleValue();
        return value.doubleValue();
    }

    private SpecFactorCatalog() {}
}
