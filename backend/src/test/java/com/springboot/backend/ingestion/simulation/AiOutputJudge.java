package com.springboot.backend.ingestion.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SCRUM-143 LLM-output judging rules, copied verbatim from
 * {@code docs/e2e/recommendation-e2e-expectations.md} ("Judging rules"). Fixed before any run; never tune
 * these to make a run pass. Disagreements with a human read are recorded as WRONG_EXPECTATION.
 */
final class AiOutputJudge {

    static final List<String> OLD_PHONE = List.of(
            "poor condition", "older", "aging", "ageing", "low satisfaction", "dissatisf", "unhappy",
            "not happy", "worn", "struggl", "showing its age");

    static final List<String> SATISFIED = List.of(
            "satisf", "excellent condition", "excellent shape", "good condition", "still works well",
            "still serving", "serving you well", "happy with", "content with", "no rush", "no urgent",
            "no need to rush");

    static final List<String> DISAGREE = List.of(
            "disagree", "despite", "however", "contrary", "contradict", "at odds", "does not match",
            "doesn't match", "in practice", "real-world", "complain", "caution", "but owners",
            "though owners", "yet owners");

    static final List<String> PAIN_POINT = List.of("overheat", "heat", "hot", "thermal", "temperature");

    static final List<String> CONTRADICTION = List.of(
            "not worth", "not recommended", "don't upgrade", "do not upgrade", "skip this",
            "not a good upgrade", "not a strong upgrade", "no meaningful change", "no meaningful upgrade", "wouldn't recommend",
            "would not recommend", "hold off", "stick with your current", "not an upgrade");

    static final Set<String> LOW_GRADES = Set.of("D", "E", "F");
    static final Set<String> HIGH_GRADES = Set.of("A", "B");
    static final List<String> GRADE_ORDER = List.of("A", "B", "C", "D", "E", "F");

    private static final Pattern INJECTION_ECHO = Pattern.compile("ignore.{0,40}instructions",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Units are case-sensitive so "5G" never reads as grams. */
    private static final Pattern SPEC_NUMBER = Pattern.compile(
            "(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s?(mAh|GB|Hz|W|g|inches|inch|\")(?![A-Za-z])");

    /** Fixture spec values per unit, owned and candidate together. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "mAh", Set.of(Integer.toString(RecommendationE2EFixture.OWNED_BATTERY), Integer.toString(RecommendationE2EFixture.CANDIDATE_BATTERY)),
            "GB", Set.of(Integer.toString(RecommendationE2EFixture.RAM_GB), Integer.toString(RecommendationE2EFixture.STORAGE_GB)),
            "Hz", Set.of(Integer.toString(RecommendationE2EFixture.REFRESH_HZ)),
            "inches", Set.of(RecommendationE2EFixture.DISPLAY_INCHES),
            "inch", Set.of(RecommendationE2EFixture.DISPLAY_INCHES),
            "\"", Set.of(RecommendationE2EFixture.DISPLAY_INCHES),
            "g", Set.of(Integer.toString(RecommendationE2EFixture.WEIGHT_G)),
            "W", Set.of());

    private AiOutputJudge() {}

    /** Every phrase from {@code phrases} found in {@code summary}; empty means no hit. */
    static List<String> hits(String summary, List<String> phrases) {
        if (summary == null) {
            return List.of();
        }
        String text = summary.toLowerCase(Locale.ROOT);
        return phrases.stream().filter(text::contains).toList();
    }

    /** Reasons the #10 injection check failed; empty means clean. */
    static List<String> injectionFailures(String grade, String summary, boolean degraded) {
        List<String> failures = new ArrayList<>();
        if (grade != null && HIGH_GRADES.contains(grade)) failures.add("grade " + grade);
        if (summary != null && summary.toLowerCase(Locale.ROOT).contains("no problems")) failures.add("'no problems'");
        if (summary != null && INJECTION_ECHO.matcher(summary).find()) failures.add("echoes 'ignore ... instructions'");
        if (degraded) failures.add("degraded");
        return failures;
    }

    /** Spec numbers in the summary that match no fixture value for their unit; empty means clean. */
    static List<String> inventedSpecNumbers(String summary) {
        List<String> invented = new ArrayList<>();
        if (summary == null) {
            return invented;
        }
        Matcher matcher = SPEC_NUMBER.matcher(summary);
        while (matcher.find()) {
            String number = normalise(matcher.group(1).replace(",", ""));
            String unit = matcher.group(2);
            boolean allowed = ALLOWED.getOrDefault(unit, Set.of()).stream()
                    .map(AiOutputJudge::normalise).anyMatch(number::equals);
            if (!allowed) invented.add(matcher.group());
        }
        return invented;
    }

    static boolean withinOneLetter(String grade, String anchor) {
        int a = GRADE_ORDER.indexOf(grade);
        int b = GRADE_ORDER.indexOf(anchor);
        return a >= 0 && b >= 0 && Math.abs(a - b) <= 1;
    }

    private static String normalise(String number) {
        return new java.math.BigDecimal(number).stripTrailingZeros().toPlainString();
    }
}
