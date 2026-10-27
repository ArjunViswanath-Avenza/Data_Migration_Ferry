package com.dataferry.register;

import com.dataferry.config.ConfigStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Source ID -> target ID, per environment. This is what chains the calls (a user step reads the
 * contractId the contract step produced) and what makes reruns safe: anything already registered
 * is skipped instead of created twice. Rollback only ever touches IDs found here.
 *
 * Source types used: CONTRACT (source contractRef -> contractId), USER (userRef -> customerId),
 * SIGNATORY_GROUP (groupRef -> signatoryGroupId), PAYEE (payeeRef -> payeeId).
 */
@Service
public class IdRegister {

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigStore config;

    public IdRegister(NamedParameterJdbcTemplate jdbc, ConfigStore config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public record Entry(String environment, String sourceType, String sourceRef, String targetType,
                        String targetId, String batchId, String runId) {}

    /** The target ID for a source reference in the active environment, if it was created before. */
    public Optional<String> find(String sourceType, String sourceRef) {
        List<String> ids = jdbc.queryForList("SELECT target_id FROM id_register WHERE environment = :env " +
                        "AND source_type = :t AND source_ref = :r",
                new MapSqlParameterSource().addValue("env", config.environment().id)
                        .addValue("t", sourceType).addValue("r", sourceRef), String.class);
        return ids.stream().findFirst();
    }

    /**
     * Records a newly created target ID. Registering the same pair again is harmless; registering
     * a different target ID for a source already registered is refused, because it means the target
     * now holds two copies of one source item.
     */
    public void register(String sourceType, String sourceRef, String targetType, String targetId,
                         String batchId, String runId) {
        Optional<String> existing = find(sourceType, sourceRef);
        if (existing.isPresent()) {
            if (existing.get().equals(targetId)) return;
            throw new IllegalStateException(sourceType + " " + sourceRef + " is already registered as " + existing.get()
                    + " in " + config.environment().id + "; refusing to register a second target ID " + targetId + ".");
        }
        try {
            jdbc.update("INSERT INTO id_register (environment, source_type, source_ref, target_type, target_id, batch_id, run_id) " +
                            "VALUES (:env, :t, :r, :tt, :tid, :b, :run)",
                    new MapSqlParameterSource().addValue("env", config.environment().id).addValue("t", sourceType)
                            .addValue("r", sourceRef).addValue("tt", targetType).addValue("tid", targetId)
                            .addValue("b", batchId).addValue("run", runId));
        } catch (DuplicateKeyException race) {
            register(sourceType, sourceRef, targetType, targetId, batchId, runId);
        }
    }

    public List<Map<String, Object>> list(String sourceType, String query, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("env", config.environment().id);
        StringBuilder sql = new StringBuilder("SELECT source_type, source_ref, target_type, target_id, batch_id, run_id, " +
                "created_at FROM id_register WHERE environment = :env");
        if (sourceType != null && !sourceType.isBlank()) { sql.append(" AND source_type = :t"); p.addValue("t", sourceType); }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (source_ref LIKE :q OR target_id LIKE :q)");
            p.addValue("q", "%" + query.trim() + "%");
        }
        sql.append(" ORDER BY id DESC LIMIT ").append(Math.max(1, Math.min(limit, 5000)));
        return jdbc.queryForList(sql.toString(), p);
    }

    public Map<String, Object> counts() {
        return Map.of("environment", config.environment().id,
                "bySourceType", jdbc.queryForList("SELECT source_type, COUNT(*) AS entries FROM id_register " +
                        "WHERE environment = :env GROUP BY source_type ORDER BY source_type",
                        new MapSqlParameterSource("env", config.environment().id)));
    }
}
