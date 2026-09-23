package com.springboot.backend.ingestion;

/** A sink must durably accept/upsert before returning ACCEPTED. No production no-op sink. */
public interface IngestionSink {
    enum Result { ACCEPTED, DUPLICATE }
    boolean supports(IngestionSource source, Payload.Body body);
    Result accept(String runId, Payload payload);
    default Result accept(String runId, Payload payload, SourceContext context) {
        return accept(runId, payload);
    }
}
