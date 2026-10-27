package com.springboot.backend.recommendation.classification;

import java.util.List;
import java.util.function.Function;

/**
 * Which columns of one category's spec table speak to which {@link Factors},
 * in which direction, and how large a change counts as a full-strength
 * improvement.
 *
 * <p>Pure data, so the scoring rules for a category stay reviewable on one
 * screen and can be unit-tested without a database.
 *
 * @param <T>             the category's spec entity, such as {@code Phone}
 * @param numericSpecs    comparable columns that feed the score
 * @param textSpecs       textual columns, reported but never scored
 * @param unscoredFactors factors no column of this category can measure. Listed
 *                        rather than silently contributing zero, because a
 *                        factor that cannot be measured and a factor that did
 *                        not change must not look the same to a reader
 */
public record SpecRules<T>(
        List<SpecRule<T>> numericSpecs,
        List<TextSpecRule<T>> textSpecs,
        List<String> unscoredFactors) {

    public SpecRules {
        numericSpecs = List.copyOf(numericSpecs);
        textSpecs = List.copyOf(textSpecs);
        unscoredFactors = List.copyOf(unscoredFactors);
    }

    /**
     * @param spec           the column name, used as the wire key
     * @param factor         which factor this spec contributes to
     * @param higherIsBetter false for specs where a smaller number is an
     *                       improvement, such as weight
     * @param improvementCap the percentage change treated as full strength
     */
    public record SpecRule<T>(
            String spec,
            String factor,
            boolean higherIsBetter,
            double improvementCap,
            Function<T, Number> reader) {}

    /** A spec that is text, not a number: reportable but not scorable. */
    public record TextSpecRule<T>(String spec, Function<T, String> reader) {}
}
