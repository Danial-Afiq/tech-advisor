package com.springboot.backend.recommendation.classification;

import java.util.List;
import java.util.Set;

/**
 * The Java mirror of {@code ai/app/factors.py::FACTORS} (AGENTS.md §11.4).
 *
 * <p>These twelve names are simultaneously the keys permitted in
 * {@code device_preferences.priorities}, the factor vocabulary the LLM may use
 * in its findings, and the grouping this classifier scores against. If the two
 * sides drift, the evidence grade gets scoped to factors the user never
 * expressed a view on, so {@code factors.py} and this file must always be
 * changed in the same commit.
 */
public final class Factors {

    public static final String BATTERY = "battery";
    public static final String CAMERA = "camera";
    public static final String PERFORMANCE = "performance";
    public static final String DISPLAY = "display";
    public static final String BUILD_QUALITY = "build_quality";
    public static final String THERMALS = "thermals";
    public static final String SOFTWARE_SUPPORT = "software_support";
    public static final String CONNECTIVITY = "connectivity";
    public static final String AUDIO = "audio";
    public static final String VALUE = "value";
    public static final String LONGEVITY = "longevity";
    public static final String PORTABILITY = "portability";

    public static final List<String> ALL = List.of(
            BATTERY, CAMERA, PERFORMANCE, DISPLAY, BUILD_QUALITY, THERMALS,
            SOFTWARE_SUPPORT, CONNECTIVITY, AUDIO, VALUE, LONGEVITY, PORTABILITY);

    private static final Set<String> LOOKUP = Set.copyOf(ALL);

    public static boolean isFactor(String name) {
        return name != null && LOOKUP.contains(name);
    }

    private Factors() {}
}
