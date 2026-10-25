package com.springboot.backend.recommendation.classification;

import com.springboot.backend.model.Gpu;
import com.springboot.backend.recommendation.classification.SpecRules.SpecRule;
import com.springboot.backend.recommendation.classification.SpecRules.TextSpecRule;
import com.springboot.backend.repository.GpuRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Maps {@code gpu} columns onto the twelve {@link Factors}.
 *
 * <p>Clocks and bus width speak to performance, VRAM to how long the card stays
 * usable, and TDP to heat, where lower is better. A graphics card has no
 * battery, camera, display or the rest, so those factors are unscored rather
 * than counted as unchanged.
 *
 * <p>The improvement caps are first estimates and still need sign-off.
 */
@Component
public class GpuSpecMapping implements CategorySpecMapping<Gpu> {

    public static final String CATEGORY = "GPU";

    public static final SpecRules<Gpu> RULES = new SpecRules<>(
            List.of(
                    new SpecRule<>("boost_clock_mhz", Factors.PERFORMANCE, true, 30.0, Gpu::getBoostClockMhz),
                    new SpecRule<>("base_clock_mhz", Factors.PERFORMANCE, true, 30.0, Gpu::getBaseClockMhz),
                    new SpecRule<>("bus_width", Factors.PERFORMANCE, true, 100.0, Gpu::getMemoryBusBits),
                    new SpecRule<>("vram", Factors.LONGEVITY, true, 100.0, Gpu::getVramGb),
                    // Less power drawn means less heat, so the sign is inverted before scoring.
                    new SpecRule<>("tdp_watts", Factors.THERMALS, false, 30.0, Gpu::getTdpWatts)),
            List.of(
                    new TextSpecRule<>("architecture", Gpu::getArchitecture),
                    new TextSpecRule<>("memory_type", Gpu::getMemoryType)),
            List.of(Factors.BATTERY, Factors.CAMERA, Factors.DISPLAY, Factors.BUILD_QUALITY,
                    Factors.SOFTWARE_SUPPORT, Factors.CONNECTIVITY, Factors.AUDIO, Factors.PORTABILITY));

    private final GpuRepository gpus;

    public GpuSpecMapping(GpuRepository gpus) {
        this.gpus = gpus;
    }

    @Override
    public String category() { return CATEGORY; }

    @Override
    public SpecRules<Gpu> rules() { return RULES; }

    @Override
    public Optional<Gpu> load(Long productId) { return gpus.findById(productId); }
}
