package com.springboot.backend.recommendation.trigger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes the one {@code system_log} row each trigger run ends with (AGENTS.md §14.13). */
@Component
public class TriggerRunLog {

    private final JdbcTemplate db;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public TriggerRunLog(JdbcTemplate db) {
        this.db = db;
    }

    public void write(TriggerRun run) {
        db.update(
                "INSERT INTO system_log(component,status,message,metadata) VALUES (?,?,?,?::jsonb)",
                run.component(),
                run.status().name(),
                run.message(),
                json.writeValueAsString(run.metadata()));
    }
}
