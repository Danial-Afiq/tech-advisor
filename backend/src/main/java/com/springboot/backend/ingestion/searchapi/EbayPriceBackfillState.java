package com.springboot.backend.ingestion.searchapi;

/**
 * Durable one-time-sweep checkpoint ({@code system_log}-row pattern, same shape
 * {@link EbayPriceIngestionState} uses). Unlike that round-robin cursor, this one never wraps:
 * once {@code completed} is set, {@link EbayPriceBackfillSource} stays permanently done and
 * spends no further requests, even if manually re-triggered.
 */
public class EbayPriceBackfillState {
    public long cursor;
    public boolean completed;
}
