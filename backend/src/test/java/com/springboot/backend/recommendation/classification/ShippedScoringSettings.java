package com.springboot.backend.recommendation.classification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The {@link ScoringSettings} exactly as {@code application.properties} ships
 * them, bound without starting a Spring context.
 *
 * <p>{@code application.properties} is the single source of truth for the
 * verdict thresholds. Unit tests read it through the same binder production
 * uses instead of restating the numbers, so recalibrating a threshold cannot
 * leave a test quietly asserting the old bands.
 *
 * <p>Environment variables are deliberately not consulted: every
 * {@code ${SCORING_...:default}} placeholder resolves to its shipped default, so
 * a developer's local override cannot change what a test run asserts.
 */
final class ShippedScoringSettings {

    private ShippedScoringSettings() {}

    static ScoringSettings load() {
        List<PropertySource<?>> sources;
        try {
            sources = new PropertiesPropertySourceLoader()
                    .load("application.properties", new ClassPathResource("application.properties"));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read application.properties", e);
        }

        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("recommendation.scoring", ScoringSettings.class)
                .orElseThrow(() -> new IllegalStateException(
                        "application.properties has no recommendation.scoring block"));
    }
}
