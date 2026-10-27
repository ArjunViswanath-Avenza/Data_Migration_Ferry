package com.dataferry.store;

import com.dataferry.core.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Who changed what and when: settings, assumptions, live switches. Best-effort, never blocking. */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;

    public AuditLog(NamedParameterJdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String subject, Map<String, Object> details) {
        try {
            jdbc.update("INSERT INTO audit_event (actor, action, subject, details) VALUES (:a, :act, :s, :d)",
                    new MapSqlParameterSource().addValue("a", actor == null || actor.isBlank() ? "unknown" : actor)
                            .addValue("act", action).addValue("s", subject).addValue("d", json.write(details)));
        } catch (Exception e) {
            log.error("Audit event not recorded (non-fatal) {} {}: {}", action, subject, e.getMessage());
        }
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("SELECT id, occurred_at, actor, action, subject, details FROM audit_event " +
                "ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 500)), new MapSqlParameterSource());
    }
}
