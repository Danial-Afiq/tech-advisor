package com.springboot.backend.ingestion.searchapi;

/**
 * Durable round-robin checkpoint (same {@code system_log}-row pattern
 * {@code TechApiIngestionState} already uses) - which catalogue product id this source last
 * priced, so the next run resumes after it rather than re-pricing the same handful of products
 * every single run. {@link EbayPriceRepository#nextProducts} wraps back to the start once the
 * cursor runs off the end of the catalogue, so every VERIFIED smartphone gets priced in turn,
 * on a cadence this source controls via {@code scheduleInterval()}, not the shared default.
 */
public class EbayPriceIngestionState {
    public long catalogueCursor;
}
