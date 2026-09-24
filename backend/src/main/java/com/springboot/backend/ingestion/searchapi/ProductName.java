package com.springboot.backend.ingestion.searchapi;

/** Admin-entered canonical identity: the first word is the brand, the rest is the model. */
public record ProductName(String brand, String model) {
    public static ProductName parse(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\p{Z}\\s]+", " ").trim();
        int separator = normalized.indexOf(' ');
        if (separator < 1 || separator == normalized.length() - 1)
            throw new IllegalArgumentException("Enter both the smartphone brand and model");
        return new ProductName(normalized.substring(0, separator), normalized.substring(separator + 1));
    }

    public String canonicalName() { return brand + " " + model; }
}
