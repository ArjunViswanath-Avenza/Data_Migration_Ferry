package com.dataferry.crossing;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.*;

/**
 * Reads the target's database (read-only) after each call to prove the call did what it said, in
 * the shape the product itself stores (config/crossing.json, readBack). Also finds a new ID when a
 * response carries none or a call timed out, so a create is never sent twice.
 */
public final class ReadBack {

    public record Check(String label, String expected, Long actual, boolean ok, String error) {}
    public record Result(String verdict, List<Check> checks) {                 // MATCH, MISMATCH, NOT_CHECKED
        static final Result NOT_CHECKED = new Result("NOT_CHECKED", List.of());
    }

    private final NamedParameterJdbcTemplate jdbc;     // null = no read-back
    private final CrossingConfig.ReadBackChecks checks;
    final String source;                                // NONE, MOCK, DATABASE

    ReadBack(NamedParameterJdbcTemplate jdbc, CrossingConfig.ReadBackChecks checks, String source) {
        this.jdbc = jdbc;
        this.checks = checks;
        this.source = source;
    }

    boolean enabled() { return jdbc != null; }

    /** Exactly one row, or empty. More than one row is ambiguous and also empty. */
    synchronized Optional<String> lookup(String operation, Map<String, Object> params) {
        CrossingConfig.OperationChecks c = checks.byOperation.get(operation);
        if (jdbc == null || c == null || c.lookup == null) return Optional.empty();
        try {
            List<String> ids = jdbc.queryForList(c.lookup, source(params), String.class);
            return ids.size() == 1 ? Optional.ofNullable(ids.get(0)) : Optional.empty();
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    synchronized Result verify(String operation, Map<String, Object> params) {
        CrossingConfig.OperationChecks c = checks.byOperation.get(operation);
        if (jdbc == null || c == null || c.verify.isEmpty()) return Result.NOT_CHECKED;
        List<Check> out = new ArrayList<>();
        boolean ok = true;
        MapSqlParameterSource p = source(params);
        for (CrossingConfig.Check k : c.verify) {
            java.util.regex.Matcher em = java.util.regex.Pattern.compile("(>=|<=|=)\\s*(\\S+)").matcher(k.expect.trim());
            if (!em.matches()) { ok = false; out.add(new Check(k.label, k.expect, null, false, "expect is not understood")); continue; }
            String op = em.group(1), rhs = em.group(2);
            Long expected = rhs.startsWith(":") ? toLong(params.get(rhs.substring(1))) : Long.valueOf(rhs);
            try {
                Long actual = jdbc.queryForObject(k.sql, p, Long.class);
                boolean pass = expected != null && actual != null && switch (op) {
                    case ">=" -> actual >= expected;
                    case "<=" -> actual <= expected;
                    default -> actual.longValue() == expected.longValue();
                };
                ok &= pass;
                out.add(new Check(k.label, op + " " + expected, actual, pass, null));
            } catch (DataAccessException e) {
                ok = false;
                out.add(new Check(k.label, op + " " + expected, null, false, rootMessage(e)));
            }
        }
        return new Result(ok ? "MATCH" : "MISMATCH", out);
    }

    private static MapSqlParameterSource source(Map<String, Object> params) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        params.forEach((k, v) -> p.addValue(k, v instanceof String || v instanceof Number || v == null ? v : String.valueOf(v)));
        return p;
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return o == null ? null : Long.valueOf(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }
}
