package com.dataferry.boarding;

import com.dataferry.core.Ids;
import com.dataferry.core.Json;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Persistence for Boarding: batches, uploaded files, staged records, findings and plans.
 * A batch moves STAGED → VALIDATED → PLANNED; any new upload sends it back to STAGED and clears
 * its findings and plan, so a plan can never describe data that has since changed.
 */
@Service
public class BatchStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;

    public BatchStore(NamedParameterJdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Finding(String recordType, int rowNum, String recordKey, String ruleId, String severity,
                          String field, String message, String assumptionId) {}

    // ---------------- batches ----------------

    public String createBatch(String name, String sourceSystem, String by) {
        String id = Ids.newBatchId();
        jdbc.update("INSERT INTO ferry_batch (batch_id, name, source_system, status, created_by, updated_at) " +
                        "VALUES (:id, :n, :s, 'NEW', :by, :at)",
                new MapSqlParameterSource().addValue("id", id).addValue("n", name).addValue("s", sourceSystem)
                        .addValue("by", by).addValue("at", now()));
        return id;
    }

    public Map<String, Object> batch(String batchId) {
        Ids.requireKey(batchId, "batch id");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ferry_batch WHERE batch_id = :id",
                new MapSqlParameterSource("id", batchId));
        if (rows.isEmpty()) throw new NoSuchElementException("No batch " + batchId);
        return rows.get(0);
    }

    public List<Map<String, Object>> batches(int limit) {
        return jdbc.queryForList("SELECT b.*, " +
                "(SELECT COUNT(*) FROM ferry_record r WHERE r.batch_id = b.batch_id) AS total_records, " +
                "(SELECT COUNT(*) FROM ferry_record r WHERE r.batch_id = b.batch_id AND r.status = 'INVALID') AS invalid_records " +
                "FROM ferry_batch b ORDER BY b.created_at DESC LIMIT " + Math.max(1, Math.min(limit, 500)),
                new MapSqlParameterSource());
    }

    public void setBatchStatus(String batchId, String status) {
        jdbc.update("UPDATE ferry_batch SET status = :s, updated_at = :at WHERE batch_id = :id",
                new MapSqlParameterSource().addValue("s", status).addValue("at", now()).addValue("id", batchId));
    }

    /** Record counts per type and status, for the batch summary. */
    public List<Map<String, Object>> counts(String batchId) {
        return jdbc.queryForList("SELECT record_type, status, COUNT(*) AS records FROM ferry_record " +
                "WHERE batch_id = :b GROUP BY record_type, status ORDER BY record_type, status",
                new MapSqlParameterSource("b", batchId));
    }

    // ---------------- files and records ----------------

    public void recordFile(String batchId, String type, String fileName, String sheet, String profileId, int rows,
                           Map<String, String> resolved, List<String> unmapped, String by) {
        jdbc.update("INSERT INTO ferry_file (batch_id, record_type, file_name, sheet_name, profile_id, rows_read, " +
                        "resolved_columns, unmapped_columns, uploaded_by) VALUES (:b, :t, :f, :s, :p, :n, :rc, :uc, :by)",
                new MapSqlParameterSource().addValue("b", batchId).addValue("t", type).addValue("f", fileName)
                        .addValue("s", sheet).addValue("p", profileId).addValue("n", rows)
                        .addValue("rc", json.write(resolved)).addValue("uc", json.write(unmapped)).addValue("by", by));
    }

    public List<Map<String, Object>> files(String batchId) {
        return jdbc.queryForList("SELECT record_type, file_name, sheet_name, profile_id, rows_read, unmapped_columns, " +
                "uploaded_by, uploaded_at FROM ferry_file WHERE batch_id = :b ORDER BY id DESC",
                new MapSqlParameterSource("b", batchId));
    }

    /** Replaces every staged record of one type: re-uploading a corrected file starts that type afresh. */
    @Transactional
    public void replaceRecords(String batchId, String type, List<StagedRecord> records) {
        MapSqlParameterSource bt = new MapSqlParameterSource().addValue("b", batchId).addValue("t", type);
        jdbc.update("DELETE FROM ferry_record WHERE batch_id = :b AND record_type = :t", bt);
        SqlParameterSource[] batch = records.stream().map(r -> new MapSqlParameterSource()
                .addValue("b", batchId).addValue("t", type).addValue("row", r.rowNum).addValue("k", r.key)
                .addValue("c", r.contractRef).addValue("st", "STAGED").addValue("tags", null)
                .addValue("d", json.write(r.data)).addValue("raw", json.write(r.raw))).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("INSERT INTO ferry_record (batch_id, record_type, row_num, record_key, contract_ref, status, tags, " +
                "record_data, raw_data) VALUES (:b, :t, :row, :k, :c, :st, :tags, :d, :raw)", batch);
        invalidate(batchId);
    }

    /** Findings and plan no longer match the data once anything is re-uploaded. */
    public void invalidate(String batchId) {
        MapSqlParameterSource b = new MapSqlParameterSource("b", batchId);
        jdbc.update("DELETE FROM ferry_finding WHERE batch_id = :b", b);
        jdbc.update("DELETE FROM ferry_plan WHERE batch_id = :b", b);
        jdbc.update("UPDATE ferry_record SET status = 'STAGED', tags = NULL WHERE batch_id = :b", b);
        setBatchStatus(batchId, "STAGED");
    }

    /** All records of a batch, grouped by type in canonical order. */
    public Map<String, List<StagedRecord>> allRecords(String batchId, Collection<String> typesInOrder) {
        Map<String, List<StagedRecord>> out = new LinkedHashMap<>();
        typesInOrder.forEach(t -> out.put(t, new ArrayList<>()));
        jdbc.query("SELECT id, record_type, row_num, record_key, contract_ref, status, tags, record_data, raw_data " +
                        "FROM ferry_record WHERE batch_id = :b ORDER BY record_type, row_num",
                new MapSqlParameterSource("b", batchId), rs -> {
                    StagedRecord r = toRecord(rs);
                    out.computeIfAbsent(r.type, k -> new ArrayList<>()).add(r);
                });
        return out;
    }

    public List<StagedRecord> records(String batchId, String type, String status, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("b", batchId);
        StringBuilder sql = new StringBuilder("SELECT id, record_type, row_num, record_key, contract_ref, status, tags, " +
                "record_data, raw_data FROM ferry_record WHERE batch_id = :b");
        if (type != null && !type.isBlank()) { sql.append(" AND record_type = :t"); p.addValue("t", type.toUpperCase()); }
        if (status != null && !status.isBlank()) { sql.append(" AND status = :s"); p.addValue("s", status.toUpperCase()); }
        sql.append(" ORDER BY record_type, row_num LIMIT ").append(Math.max(1, Math.min(limit, 100000)));
        return jdbc.query(sql.toString(), p, (rs, i) -> toRecord(rs));
    }

    /** Records in any batch that relied on an assumption — what to re-check when its answer changes. */
    public List<Map<String, Object>> recordsTagged(String assumptionId, int limit) {
        return jdbc.queryForList("SELECT batch_id, record_type, row_num, record_key, contract_ref, status FROM ferry_record " +
                        "WHERE tags LIKE :t ORDER BY batch_id, record_type, row_num LIMIT " + Math.max(1, Math.min(limit, 10000)),
                new MapSqlParameterSource("t", "%" + assumptionId + "%"));
    }

    private StagedRecord toRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        StagedRecord r = new StagedRecord(rs.getString("record_type"), rs.getInt("row_num"));
        r.id = rs.getLong("id");
        r.key = rs.getString("record_key");
        r.contractRef = rs.getString("contract_ref");
        r.status = rs.getString("status");
        String tags = rs.getString("tags");
        if (tags != null && !tags.isBlank()) r.tags.addAll(Arrays.asList(tags.split(",")));
        json.readMap(rs.getString("record_data")).forEach((k, v) -> r.data.put(k, v == null ? null : String.valueOf(v)));
        json.readMap(rs.getString("raw_data")).forEach((k, v) -> r.raw.put(k, v == null ? null : String.valueOf(v)));
        return r;
    }

    // ---------------- validation results ----------------

    @Transactional
    public void saveValidation(String batchId, Collection<StagedRecord> records, List<Finding> findings) {
        jdbc.update("DELETE FROM ferry_finding WHERE batch_id = :b", new MapSqlParameterSource("b", batchId));
        jdbc.update("DELETE FROM ferry_plan WHERE batch_id = :b", new MapSqlParameterSource("b", batchId));
        // record_data too: validation adds derived values such as userTypeResolved (assumption A-01)
        jdbc.batchUpdate("UPDATE ferry_record SET status = :s, tags = :t, record_data = :d WHERE id = :id",
                records.stream().map(r -> new MapSqlParameterSource().addValue("s", r.status)
                        .addValue("t", r.tags.isEmpty() ? null : String.join(",", r.tags))
                        .addValue("d", json.write(r.data)).addValue("id", r.id))
                        .toArray(SqlParameterSource[]::new));
        jdbc.batchUpdate("INSERT INTO ferry_finding (batch_id, record_type, row_num, record_key, rule_id, severity, field, " +
                        "message, assumption_id) VALUES (:b, :t, :row, :k, :rule, :sev, :f, :m, :a)",
                findings.stream().map(f -> new MapSqlParameterSource().addValue("b", batchId).addValue("t", f.recordType())
                        .addValue("row", f.rowNum()).addValue("k", f.recordKey()).addValue("rule", f.ruleId())
                        .addValue("sev", f.severity()).addValue("f", f.field())
                        .addValue("m", f.message() == null ? null : f.message().substring(0, Math.min(f.message().length(), 1000)))
                        .addValue("a", f.assumptionId())).toArray(SqlParameterSource[]::new));
        setBatchStatus(batchId, "VALIDATED");
    }

    public List<Map<String, Object>> findings(String batchId, String severity, String type, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("b", batchId);
        StringBuilder sql = new StringBuilder("SELECT record_type, row_num, record_key, rule_id, severity, field, message, " +
                "assumption_id FROM ferry_finding WHERE batch_id = :b");
        if (severity != null && !severity.isBlank()) { sql.append(" AND severity = :s"); p.addValue("s", severity.toUpperCase()); }
        if (type != null && !type.isBlank()) { sql.append(" AND record_type = :t"); p.addValue("t", type.toUpperCase()); }
        sql.append(" ORDER BY CASE severity WHEN 'ERROR' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END, record_type, row_num LIMIT ")
                .append(Math.max(1, Math.min(limit, 50000)));
        return jdbc.queryForList(sql.toString(), p);
    }

    public List<Map<String, Object>> findingSummary(String batchId) {
        return jdbc.queryForList("SELECT rule_id, severity, record_type, assumption_id, COUNT(*) AS occurrences, " +
                "MIN(message) AS example FROM ferry_finding WHERE batch_id = :b " +
                "GROUP BY rule_id, severity, record_type, assumption_id ORDER BY severity, occurrences DESC",
                new MapSqlParameterSource("b", batchId));
    }

    // ---------------- plans ----------------

    @Transactional
    public void savePlans(String batchId, List<Map<String, Object>> voyages) {
        jdbc.update("DELETE FROM ferry_plan WHERE batch_id = :b", new MapSqlParameterSource("b", batchId));
        jdbc.batchUpdate("INSERT INTO ferry_plan (batch_id, contract_ref, status, plan_json) VALUES (:b, :c, :s, :p)",
                voyages.stream().map(v -> new MapSqlParameterSource().addValue("b", batchId)
                        .addValue("c", v.get("contractRef")).addValue("s", v.get("status"))
                        .addValue("p", json.write(v))).toArray(SqlParameterSource[]::new));
        setBatchStatus(batchId, "PLANNED");
    }

    public List<Map<String, Object>> plans(String batchId, String status) {
        MapSqlParameterSource p = new MapSqlParameterSource("b", batchId);
        String where = "";
        if (status != null && !status.isBlank()) { where = " AND status = :s"; p.addValue("s", status.toUpperCase()); }
        return jdbc.query("SELECT plan_json FROM ferry_plan WHERE batch_id = :b" + where + " ORDER BY contract_ref", p,
                (rs, i) -> json.readMap(rs.getString("plan_json")));
    }

    private static Timestamp now() { return Timestamp.from(Instant.now()); }
}
