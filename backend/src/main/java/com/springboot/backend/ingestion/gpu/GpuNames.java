package com.springboot.backend.ingestion.gpu;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The seed file and TechSpot spell the same card differently ("NVIDIA GeForce RTX 4070" vs
 * "GeForce RTX 4070"). products is unique on (brand, model_name), so both must collapse to one
 * spelling or a second run would insert duplicates instead of nothing.
 */
public final class GpuNames {
    private static final Pattern NVIDIA_PREFIX = Pattern.compile("(?i)^nvidia\\s+");
    private static final Pattern AMD_PREFIX = Pattern.compile("(?i)^(amd|ati)\\s+");
    private static final Pattern INTEL_PREFIX = Pattern.compile("(?i)^intel\\s+");
    private static final Pattern SYMBOLS = Pattern.compile("[®™]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private GpuNames() {}

    public static String brand(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "nvidia", "nvidia corporation" -> "NVIDIA";
            case "amd", "advanced micro devices", "ati" -> "AMD";
            case "intel" -> "Intel";
            default -> null;
        };
    }

    /** Casing of the remainder is kept because users see it; key() is what handles identity. */
    public static String model(String canonicalBrand, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String name = WHITESPACE.matcher(SYMBOLS.matcher(raw).replaceAll(" ")).replaceAll(" ").trim();
        String brand = brand(canonicalBrand);
        Pattern prefix = brand == null ? null : switch (brand) {
            case "NVIDIA" -> NVIDIA_PREFIX;
            case "AMD" -> AMD_PREFIX;
            default -> INTEL_PREFIX;
        };
        if (prefix != null) name = prefix.matcher(name).replaceFirst("");
        return name.isEmpty() ? null : name;
    }

    /** Case-insensitive identity, so a re-cased source spelling still matches the stored row. */
    public static String key(String canonicalBrand, String model) {
        String brand = brand(canonicalBrand);
        String name = model(canonicalBrand, model);
        if (brand == null || name == null)
            throw new IllegalArgumentException("GPU key needs a known brand and a model name");
        return (brand + "|" + name).toLowerCase(Locale.ROOT);
    }
}
