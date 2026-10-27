package com.dataferry.store;

import com.dataferry.config.ConfigStore;
import com.dataferry.core.Ids;
import com.dataferry.core.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One row per action in any area (upload, validate, plan, crossing, rollback). Bookkeeping must
 * never break the operation it records, so every write runs in its own transaction and a
 * failure is logged and swallowed.
 */
@Service
public class RunStore {

    private static final Logger log = LoggerFactory.getLogger(RunStore.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigStore config;
    private final Json json;

    public RunStore(NamedParameterJdbcTemplate jdbc, ConfigStore config, Json json) {
        this.jdbc = jdbc;
        this.config = config;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String start(String area, String action, String batchId, String performedBy) {
        String runId = Ids.newRunId();
        try {
            jdbc.update("INSERT INTO ferry_run (run_id, area, action, status, batch_id, environment, performed_by, started_at) " +
                            "VALUES (:id, :area, :action, 'RUNNING', :batch, :env, :by, :at)",
                    new MapSqlParameterSource().addValue("id", runId).addValue("area", area).addValue("action", action)
                            .addValue("batch", batchId).addValue("env", config.environment().id)
                            .addValue("by", performedBy == null || performedBy.isBlank() ? "unknown" : performedBy)
                            .addValue("at", Timestamp.from(Instant.now())));
        } catch (Exception e) {
            log.error("Run start not recorded (non-fatal): {}", e.getMessage());
        }
        return runId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String runId, String status, int total, int succeeded, int failed, Map<String, Object> details) {
        try {
            jdbc.update("UPDATE ferry_run SET status = :st, finished_at = :at, total_items = :t, succeeded = :ok, " +
                            "failed = :bad, details = :d WHERE run_id = :id",
                    new MapSqlParameterSource().addValue("st", status).addValue("at", Timestamp.from(Instant.now()))
                            .addValue("t", total).addValue("ok", succeeded).addValue("bad", failed)
                            .addValue("d", json.write(details)).addValue("id", runId));
        } catch (Exception e) {
            log.error("Run finish not recorded (non-fatal) run={}: {}", runId, e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String runId, String error) {
        try {
            jdbc.update("UPDATE ferry_run SET status = 'FAILED', finished_at = :at, error_message = :e WHERE run_id = :id",
                    new MapSqlParameterSource().addValue("at", Timestamp.from(Instant.now()))
                            .addValue("e", error == null ? null : error.substring(0, Math.min(error.length(), 2000)))
                            .addValue("id", runId));
        } catch (Exception e) {
            log.error("Run failure not recorded (non-fatal) run={}: {}", runId, e.getMessage());
        }
    }

    public List<Map<String, Object>> recent(String area, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (area != null && !area.isBlank()) { where = " WHERE area = :area"; p.addValue("area", area); }
        return jdbc.queryForList("SELECT run_id, area, action, status, batch_id, environment, performed_by, started_at, " +
                "finished_at, total_items, succeeded, failed, error_message FROM ferry_run" + where +
                " ORDER BY started_at DESC LIMIT " + Math.max(1, Math.min(limit, 500)), p);
    }
}
