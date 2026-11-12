package com.dataferry.disembark;

import com.dataferry.disembark.UnenrollProfile.Check;
import com.dataferry.disembark.UnenrollProfile.DeleteStep;
import com.dataferry.disembark.UnenrollProfile.ResolveStep;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Un-enrols one customer, driven entirely by {@link UnenrollProfile}.
 *
 * <ol>
 *   <li><b>Resolve first</b>: every ID linked to the CIF is looked up and held before anything is
 *       deleted, because once child rows are gone the parents can no longer be found.</li>
 *   <li><b>Preview</b>: counts per table, plus the profile's checks (shared logins, shared contracts).</li>
 *   <li><b>Execute</b>: counts everything again, stops if any table is over the guard, then deletes in
 *       profile order. The caller owns the transaction.</li>
 *   <li><b>Script</b>: the same plan as SQL a DBA runs: IDs frozen in temporary tables, live preview,
 *       checks, deletes in one transaction, verification, and no COMMIT.</li>
 * </ol>
 *
 * A table that does not exist in the connected database is reported, not fatal: releases differ.
 */
public final class UnenrollEngine {

    public record StepResult(String group, String table, String column, String using, Long rows, String state, String note) {}
    public record Finding(String item, String others) {}
    public record CheckResult(String id, String label, String message, List<Finding> findings) {}
    public record Plan(String cif, String schema, Map<String, Integer> resolved, Map<String, List<String>> sample, List<StepResult> steps,
                List<CheckResult> checks, long total, List<String> overGuard, List<String> notes) {}

    private final UnenrollProfile p;

    UnenrollEngine(UnenrollProfile profile) { this.p = profile; }

    void checkKey(String cif) {
        if (cif == null || !cif.matches(p.keyPattern))
            throw new IllegalArgumentException("'" + cif + "' is not a valid " + p.keyLabel + ".");
    }

    // ------------------------------------------------------------------ resolve

    Map<String, List<Object>> resolve(NamedParameterJdbcTemplate jdbc, String cif, List<String> notes) {
        Map<String, List<Object>> sets = new LinkedHashMap<>();
        sets.put("cif", List.of(cif));
        for (ResolveStep r : p.resolveSteps) {
            if (!r.confirmed) { sets.put(r.name, List.of()); continue; }
            try {
                List<Object> v = jdbc.query(r.sql, new MapSqlParameterSource("cif", cif), (rs, i) -> rs.getObject(1));
                sets.put(r.name, v.stream().filter(Objects::nonNull).distinct().toList());
            } catch (DataAccessException e) {
                if (!missingTable(e)) throw e;
                sets.put(r.name, List.of());
                notes.add("Lookup '" + r.name + "' uses a table this database does not have; it found nothing.");
            }
        }
        return sets;
    }

    // ------------------------------------------------------------------ preview

    Plan preview(NamedParameterJdbcTemplate jdbc, String cif, String schema) {
        checkKey(cif);
        List<String> notes = new ArrayList<>();
        Map<String, List<Object>> sets = resolve(jdbc, cif, notes);
        List<StepResult> steps = new ArrayList<>();
        List<String> overGuard = new ArrayList<>();
        long total = 0;
        for (DeleteStep d : p.deleteSteps) {
            if (!d.confirmed) { steps.add(step(d, null, "OFF")); continue; }
            List<Object> ids = sets.getOrDefault(d.using, List.of());
            if (ids.isEmpty()) { steps.add(step(d, 0L, "NO_IDS")); continue; }
            Long n = count(jdbc, schema, d, ids);
            if (n == null) { steps.add(step(d, null, "MISSING_TABLE")); continue; }
            total += n;
            if (n > p.maxRowsPerTableGuard) overGuard.add(d.table + " (" + n + " rows)");
            steps.add(step(d, n, "COUNTED"));
        }
        return new Plan(cif, schema, counts(sets), sample(sets), steps, checks(jdbc, cif, sets), total, overGuard, notes);
    }

    private List<CheckResult> checks(NamedParameterJdbcTemplate jdbc, String cif, Map<String, List<Object>> sets) {
        List<CheckResult> out = new ArrayList<>();
        for (Check c : p.checks) {
            MapSqlParameterSource params = new MapSqlParameterSource("cif", cif);
            boolean empty = false;
            for (Map.Entry<String, List<Object>> e : sets.entrySet()) {
                if (e.getKey().equals("cif")) continue;
                if (c.sql.contains(":" + e.getKey())) {
                    if (e.getValue().isEmpty()) empty = true;
                    params.addValue(e.getKey(), e.getValue());
                }
            }
            List<Finding> f = List.of();
            if (!empty) {
                try {
                    f = jdbc.query(c.sql, params, (rs, i) -> new Finding(rs.getString("item"), rs.getString("others")));
                } catch (DataAccessException e) {
                    if (!missingTable(e)) throw e;
                }
            }
            out.add(new CheckResult(c.id, c.label, c.message, f));
        }
        return out;
    }

    // ------------------------------------------------------------------ execute

    /** Deletes in profile order. The caller runs this inside one transaction and commits or rolls back. */
    List<StepResult> execute(NamedParameterJdbcTemplate jdbc, String cif, String schema) {
        Plan plan = preview(jdbc, cif, schema);
        if (!plan.overGuard().isEmpty())
            throw new IllegalStateException("Stopped before deleting anything: " + String.join(", ", plan.overGuard())
                    + " is over the guard of " + p.maxRowsPerTableGuard + " rows per table (config/unenroll.json).");
        Map<String, List<Object>> sets = resolve(jdbc, cif, new ArrayList<>());
        Map<String, String> state = new HashMap<>();
        plan.steps().forEach(s -> state.put(s.table() + "#" + s.column(), s.state()));
        List<StepResult> out = new ArrayList<>();
        for (DeleteStep d : p.deleteSteps) {
            String st = state.get(d.table + "#" + d.column);
            if (!"COUNTED".equals(st)) { out.add(step(d, null, st)); continue; }
            int n = jdbc.update("DELETE FROM " + q(schema, d.table) + " WHERE " + q(d.column) + " IN (:ids)",
                    new MapSqlParameterSource("ids", sets.get(d.using)));
            out.add(step(d, (long) n, "DELETED"));
        }
        return out;
    }

    // ------------------------------------------------------------------ script

    /** The plan as SQL. With a plan (a connected preview), counts and checks are written in as comments. */
    String script(String cif, String schema, String runId, String by, Plan plan) {
        checkKey(cif);
        String lit = "'" + cif.replace("'", "''") + "'";
        Map<String, Long> counts = new HashMap<>();
        Set<String> missing = new HashSet<>();
        if (plan != null) for (StepResult s : plan.steps()) {
            if (s.rows() != null) counts.put(s.table() + "#" + s.column(), s.rows());
            if ("MISSING_TABLE".equals(s.state())) missing.add(s.table());
        }

        // every set any step or check refers to, so switching a step on later still finds its table
        Set<String> used = new LinkedHashSet<>();
        for (ResolveStep r : p.resolveSteps) {
            boolean needed = p.deleteSteps.stream().anyMatch(d -> d.using.equals(r.name))
                    || p.checks.stream().anyMatch(c -> c.sql.contains(":" + r.name));
            if (needed && r.confirmed) used.add(r.name);
        }

        StringBuilder b = new StringBuilder();
        b.append("-- =====================================================================\n");
        b.append("-- Data Migration Ferry · Disembark: un-enrol one customer\n");
        b.append("-- ").append(p.keyLabel).append(": ").append(cif).append("\n");
        b.append("-- Schema: ").append(schema).append("   Run: ").append(runId).append("\n");
        b.append("-- Written: ").append(OffsetDateTime.now().withNano(0)).append(" by ").append(by == null || by.isBlank() ? "-" : by).append("\n");
        b.append("-- Guard: no table may lose more than ").append(p.maxRowsPerTableGuard).append(" rows.\n");
        b.append("--\n");
        b.append("-- Nothing in this file has been run. Take a backup first, then:\n");
        b.append("--   1. Run sections 1 to 3 and read the counts and the checks.\n");
        b.append("--   2. Run section 4 (deletes, inside one transaction), then section 5: every count must be 0.\n");
        b.append("--   3. Type COMMIT; to apply, or ROLLBACK; to undo. The script never commits by itself.\n");
        if (plan == null) b.append("-- Written without a database connection: the counts come from section 2 when you run it.\n");
        else b.append("-- Counts in comments were taken at writing time; section 2 counts again when you run it.\n");
        b.append("-- =====================================================================\n\n");

        if (plan != null) {
            for (CheckResult c : plan.checks())
                if (!c.findings().isEmpty()) {
                    b.append("-- !!! ").append(c.label().toUpperCase(Locale.ROOT)).append(": ").append(c.message()).append("\n");
                    for (Finding f : c.findings()) b.append("--       ").append(f.item()).append(" is also linked to ").append(f.others()).append("\n");
                    b.append("\n");
                }
            b.append("-- IDs found at writing time:\n");
            plan.sample().forEach((k, v) -> b.append("--   ").append(k).append(" [").append(plan.resolved().get(k)).append("]: ")
                    .append(v.isEmpty() ? "none" : String.join(", ", v)).append(plan.resolved().get(k) > v.size() ? ", …" : "").append("\n"));
            for (String n : plan.notes()) b.append("--   note: ").append(n).append("\n");
            b.append("\n");
        }

        b.append("USE ").append(q(schema)).append(";\n\n");
        b.append("-- ==== 1. RESOLVE: freeze the IDs before anything is deleted =========\n");
        for (String name : used) {
            String sql = p.resolveSteps.stream().filter(r -> r.name.equals(name)).findFirst().orElseThrow().sql.replace(":cif", lit);
            b.append("DROP TEMPORARY TABLE IF EXISTS ").append(tmp(name)).append(";\n");
            b.append("CREATE TEMPORARY TABLE ").append(tmp(name)).append(" AS ").append(sql).append(";\n");
        }

        b.append("\n-- ==== 2. PREVIEW: rows each delete will remove ======================\n");
        for (DeleteStep d : p.deleteSteps) {
            if (!d.confirmed || missing.contains(d.table)) continue;
            b.append("SELECT '").append(d.table).append("' AS table_name, COUNT(*) AS rows_to_delete FROM ")
                    .append(q(schema, d.table)).append(" WHERE ").append(where(d, lit)).append(";\n");
        }

        if (!p.checks.isEmpty()) {
            b.append("\n-- ==== 3. CHECKS: any row here means something is shared with another CIF\n");
            for (Check c : p.checks) {
                b.append("-- ").append(c.label).append(": ").append(c.message).append("\n");
                b.append(inline(c.sql, lit, used)).append(";\n");
            }
        }

        b.append("\n-- ==== 4. DELETE: children first, anchors last, one transaction =====\n");
        b.append("START TRANSACTION;\n");
        String group = null;
        for (DeleteStep d : p.deleteSteps) {
            if (!d.group.equals(group)) { b.append("\n-- ").append(d.group).append("\n"); group = d.group; }
            String stmt = "DELETE FROM " + q(schema, d.table) + " WHERE " + where(d, lit) + ";";
            Long n = counts.get(d.table + "#" + d.column);
            if (!d.confirmed) {
                String why = d.note == null ? "" : ": " + d.note.replaceFirst("(?i)^off:\\s*", "");
                b.append("-- OFF in config/unenroll.json").append(why).append("\n-- ").append(stmt).append("\n");
            } else if (missing.contains(d.table)) {
                b.append("-- LEFT OUT: this database has no table ").append(d.table).append("\n-- ").append(stmt).append("\n");
            } else if (n != null && n > p.maxRowsPerTableGuard) {
                b.append("-- STOP: ").append(n).append(" rows is over the guard of ").append(p.maxRowsPerTableGuard)
                        .append("; left commented out, check before enabling\n-- ").append(stmt).append("\n");
            } else {
                if (d.note != null) b.append("--   ").append(d.note).append("\n");
                if (n != null) b.append("--   ").append(n).append(" row").append(n == 1 ? "" : "s").append(" at writing time\n");
                b.append(stmt).append("\n");
            }
        }

        b.append("\n-- ==== 5. VERIFY: every count must be 0 ==============================\n");
        for (DeleteStep d : p.deleteSteps) {
            if (!d.confirmed || missing.contains(d.table)) continue;
            b.append("SELECT '").append(d.table).append("' AS table_name, COUNT(*) AS remaining FROM ")
                    .append(q(schema, d.table)).append(" WHERE ").append(where(d, lit)).append(";\n");
        }
        b.append("\n-- ==== 6. DECIDE =====================================================\n");
        b.append("-- Counts right and every remaining = 0?  COMMIT;\n");
        b.append("-- Anything wrong?                        ROLLBACK;\n\n");
        for (String name : used) b.append("-- after deciding: DROP TEMPORARY TABLE IF EXISTS ").append(tmp(name)).append(";\n");
        return b.toString();
    }

    private String where(DeleteStep d, String lit) {
        return d.using.equals("cif") ? q(d.column) + " = " + lit : q(d.column) + " IN (SELECT * FROM " + tmp(d.using) + ")";
    }

    /** A check's named parameters replaced by the frozen temporary tables. */
    private static String inline(String sql, String lit, Set<String> used) {
        String s = sql;
        List<String> names = new ArrayList<>(used);
        names.sort(Comparator.comparingInt(String::length).reversed());
        for (String n : names) s = s.replace(":" + n, "SELECT * FROM " + tmp(n));
        return s.replace(":cif", lit);
    }

    // ------------------------------------------------------------------ helpers

    private Long count(NamedParameterJdbcTemplate jdbc, String schema, DeleteStep d, List<Object> ids) {
        try {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + q(schema, d.table) + " WHERE " + q(d.column) + " IN (:ids)",
                    new MapSqlParameterSource("ids", ids), Long.class);
        } catch (DataAccessException e) {
            if (missingTable(e)) return null;
            throw e;
        }
    }

    private static StepResult step(DeleteStep d, Long rows, String state) {
        return new StepResult(d.group, d.table, d.column, d.using, rows, state, d.note);
    }

    private static Map<String, Integer> counts(Map<String, List<Object>> sets) {
        Map<String, Integer> m = new LinkedHashMap<>();
        sets.forEach((k, v) -> m.put(k, v.size()));
        return m;
    }

    private static Map<String, List<String>> sample(Map<String, List<Object>> sets) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        sets.forEach((k, v) -> m.put(k, v.stream().limit(10).map(String::valueOf).collect(Collectors.toList())));
        return m;
    }

    /** MySQL 1146 / SQLSTATE 42S02 (and H2's 42S02/42S22 in tests): the table is not in this database. */
    static boolean missingTable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof SQLException s && s.getSQLState() != null && (s.getSQLState().equals("42S02") || s.getErrorCode() == 1146))
                return true;
        return false;
    }

    static String q(String name) { return "`" + name.replace("`", "``") + "`"; }
    static String q(String schema, String name) { return q(schema) + "." + q(name); }
    private static String tmp(String name) { return "`_disembark_" + name + "`"; }
}
