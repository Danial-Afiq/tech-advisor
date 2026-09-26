package com.springboot.backend.ingestion.run;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class CoordinatorState {
    public Instant anchor, nextDue, leaseUntil;
    public String activeRunId, owner;
    public Map<String, Instant> nextAllowed = new HashMap<>();
}
