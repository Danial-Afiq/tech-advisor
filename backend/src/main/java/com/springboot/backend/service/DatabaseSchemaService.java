package com.springboot.backend.service;

import com.springboot.backend.dto.DatabaseTableResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only description of the tables in the current schema (names, columns,
 * keys and exact row counts) for the admin "visualisation of all tables" panel.
 * Reads PostgreSQL's information_schema only; it never exposes row contents.
 */
@Service
public class DatabaseSchemaService {

    private final JdbcTemplate jdbc;

    public DatabaseSchemaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<DatabaseTableResponse> tables() {
        Map<String, List<Map<String, Object>>> columnsByTable = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT c.table_name, c.column_name, c.data_type, c.is_nullable
                  FROM information_schema.columns c
                  JOIN information_schema.tables t
                    ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                 WHERE c.table_schema = current_schema()
                   AND t.table_type = 'BASE TABLE'
                 ORDER BY c.table_name, c.ordinal_position
                """).forEach(row -> columnsByTable
                .computeIfAbsent((String) row.get("table_name"), name -> new ArrayList<>())
                .add(row));

        Set<String> primaryKeys = new HashSet<>();
        jdbc.queryForList("""
                SELECT kcu.table_name, kcu.column_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_schema = tc.constraint_schema
                   AND kcu.constraint_name = tc.constraint_name
                 WHERE tc.table_schema = current_schema()
                   AND tc.constraint_type = 'PRIMARY KEY'
                """).forEach(row -> primaryKeys.add(row.get("table_name") + "." + row.get("column_name")));

        Map<String, String> foreignKeys = new HashMap<>();
        jdbc.queryForList("""
                SELECT kcu.table_name, kcu.column_name, ccu.table_name AS target_table
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_schema = tc.constraint_schema
                   AND kcu.constraint_name = tc.constraint_name
                  JOIN information_schema.constraint_column_usage ccu
                    ON ccu.constraint_schema = tc.constraint_schema
                   AND ccu.constraint_name = tc.constraint_name
                 WHERE tc.table_schema = current_schema()
                   AND tc.constraint_type = 'FOREIGN KEY'
                """).forEach(row -> foreignKeys.put(
                row.get("table_name") + "." + row.get("column_name"),
                (String) row.get("target_table")));

        List<DatabaseTableResponse> tables = new ArrayList<>();
        columnsByTable.forEach((table, rows) -> {
            List<DatabaseTableResponse.Column> columns = rows.stream()
                    .map(row -> {
                        String key = table + "." + row.get("column_name");
                        return new DatabaseTableResponse.Column(
                                (String) row.get("column_name"),
                                (String) row.get("data_type"),
                                "YES".equals(row.get("is_nullable")),
                                primaryKeys.contains(key),
                                foreignKeys.get(key));
                    })
                    .toList();
            tables.add(new DatabaseTableResponse(table, countRows(table), columns));
        });
        return tables;
    }

    /** The name comes from information_schema, and is quoted as an identifier. */
    private long countRows(String table) {
        String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + quoted, Long.class);
        return count == null ? 0 : count;
    }
}
