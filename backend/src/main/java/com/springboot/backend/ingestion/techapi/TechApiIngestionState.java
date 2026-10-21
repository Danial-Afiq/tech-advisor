package com.springboot.backend.ingestion.techapi;

import java.time.Instant;

/** Durable checkpoint for the TechAPI backfill and incremental feed. */
public class TechApiIngestionState {
    public String phase = "INITIAL_BACKFILL";
    public String snapshotRevision;
    public String cursor;
    public long completedUnits;
    public long totalUnits;
    public long catalogueSnapshotMax;
    public long catalogueCursor;
    public boolean initialBackfillComplete;
    public Instant initialBackfillCompletedAt;
    public String baselineRevision;
    public String pendingRevision;
    public String pendingCursor;
    public long pendingCompletedUnits;
    public long pendingTotalUnits;
}
