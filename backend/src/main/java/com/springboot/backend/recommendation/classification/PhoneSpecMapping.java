package com.springboot.backend.recommendation.classification;

import com.springboot.backend.model.Phone;
import com.springboot.backend.recommendation.classification.SpecRules.SpecRule;
import com.springboot.backend.recommendation.classification.SpecRules.TextSpecRule;
import com.springboot.backend.repository.PhoneRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Maps {@code phone} columns onto the twelve {@link Factors}.
 *
 * <p><strong>Five factors are deliberately unscored.</strong> {@code camera},
 * {@code build_quality}, {@code thermals}, {@code connectivity} and
 * {@code audio} have no numerically comparable column in V6 -
 * {@code camera_specs} and {@code ip_rating} are free text, and the other three
 * have no column at all. A user whose top priority is {@code camera} will see it
 * excluded from the score and named in the breakdown, and the owner-evidence
 * channel still grades it.
 */
@Component
public class PhoneSpecMapping implements CategorySpecMapping<Phone> {

    public static final String CATEGORY = "SMARTPHONE";

    public static final SpecRules<Phone> RULES = new SpecRules<>(
            List.of(
                    new SpecRule<>("battery_mah", Factors.BATTERY, true, 50.0, Phone::getBatteryMah),
                    new SpecRule<>("wired_charging_watts", Factors.BATTERY, true, 100.0, Phone::getWiredChargingWatts),
                    new SpecRule<>("wireless_charging_watts", Factors.BATTERY, true, 100.0,
                            Phone::getWirelessChargingWatts),
                    new SpecRule<>("ram_gb", Factors.PERFORMANCE, true, 100.0, Phone::getRamGb),
                    new SpecRule<>("cpu_ghz", Factors.PERFORMANCE, true, 30.0, Phone::getCpuGhz),
                    new SpecRule<>("storage_gb", Factors.LONGEVITY, true, 100.0, Phone::getStorageGb),
                    new SpecRule<>("refresh_rate_hz", Factors.DISPLAY, true, 100.0, Phone::getRefreshRateHz),
                    new SpecRule<>("display_size_inches", Factors.DISPLAY, true, 20.0, Phone::getDisplaySizeInches),
                    new SpecRule<>("pixel_density", Factors.DISPLAY, true, 30.0, Phone::getPixelDensity),
                    // Lighter is better, so the sign is inverted before scoring.
                    new SpecRule<>("weight_g", Factors.PORTABILITY, false, 20.0, Phone::getWeightG),
                    new SpecRule<>("software_support_years", Factors.SOFTWARE_SUPPORT, true, 100.0,
                            Phone::getSoftwareSupportYears)),
            List.of(
                    new TextSpecRule<>("chipset", Phone::getChipset),
                    new TextSpecRule<>("camera_specs", Phone::getCameraSpecs),
                    new TextSpecRule<>("ip_rating", Phone::getIpRating),
                    new TextSpecRule<>("os", Phone::getOs)),
            List.of(Factors.CAMERA, Factors.BUILD_QUALITY, Factors.THERMALS,
                    Factors.CONNECTIVITY, Factors.AUDIO));

    private final PhoneRepository phones;

    public PhoneSpecMapping(PhoneRepository phones) {
        this.phones = phones;
    }

    @Override
    public String category() { return CATEGORY; }

    @Override
    public SpecRules<Phone> rules() { return RULES; }

    @Override
    public Optional<Phone> load(Long productId) { return phones.findById(productId); }
}
