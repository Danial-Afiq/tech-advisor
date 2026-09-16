CREATE TABLE system_log (
    id BIGSERIAL PRIMARY KEY,
    component VARCHAR(80) NOT NULL,
    status VARCHAR(30) NOT NULL,
    message TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX system_log_coordinator ON system_log (component)
    WHERE component = 'INGESTION_COORDINATOR';
CREATE UNIQUE INDEX system_log_run_id ON system_log ((metadata->>'runId'))
    WHERE component = 'INGESTION_RUN';
CREATE UNIQUE INDEX system_log_manual_key ON system_log
    ((metadata->>'requestedBy'), (metadata->>'idempotencyKey'))
    WHERE component = 'INGESTION_RUN' AND metadata->>'triggerType' = 'MANUAL';
CREATE INDEX system_log_history ON system_log (component, created_at DESC);
