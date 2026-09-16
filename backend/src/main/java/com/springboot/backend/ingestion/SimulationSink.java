package com.springboot.backend.ingestion;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;

/** Demo-only durable receipts; these are not catalogue rows or production market data. */
@Component
@Profile("ingestion-demo")
public class SimulationSink implements IngestionSink {
    private final JdbcTemplate db;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    public SimulationSink(JdbcTemplate db) { this.db = db; }
    public boolean supports(IngestionSource source, Payload.Body body) { return source.simulation(); }
    public Result accept(String runId, Payload payload) {
        db.update("INSERT INTO system_log(component,status,message,metadata) VALUES "
                + "('INGESTION_DEMO_PAYLOAD','ACCEPTED','Simulation receipt, not market data',?::jsonb)",
                json.writeValueAsString(Map.of("runId", runId, "simulation", true,
                        "payloadType", payload.body().getClass().getSimpleName(), "payload", payload)));
        return Result.ACCEPTED;
    }
}
