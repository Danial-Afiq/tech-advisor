package com.springboot.backend.marketevent;

/**
 * Published by {@link MarketEventService} whenever a {@code market_events} row is
 * written, whichever path wrote it. Listeners that read the row back (the
 * recommendation trigger) must listen after commit, not on publish.
 *
 * @param marketEventId the {@code market_events.id} that was recorded
 */
public record MarketEventRecorded(long marketEventId) {}
