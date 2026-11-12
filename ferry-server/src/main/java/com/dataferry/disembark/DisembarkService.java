package com.dataferry.disembark;

import com.dataferry.core.FerryProperties;
import com.dataferry.store.RunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Disembark: un-enrol one customer from the Temenos Digital database.
 *
 * Three ways, safest first:
 * <ul>
 *   <li><b>Script</b>: always available, needs no connection. A reviewable SQL file that never commits.</li>
 *   <li><b>Preview</b>: connects read-only and counts what would go, with the shared-login and
 *       shared-contract checks.</li>
 *   <li><b>Execute</b>: off unless config/unenroll.json says allowExecute=true; needs the key typed again,
 *       an acknowledgement when a check found something, and stops before deleting if a table is over the guard.</li>
 * </ul>
 * The database password comes with each request and is never stored or logged.
 */
@Service
public class DisembarkService {

    private static final Logger log = LoggerFactory.getLogger(DisembarkService.class);
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");

    private final Path file;
    private final ObjectMapper om;
    private final RunStore runs;

    public DisembarkService(FerryProperties props, ObjectMapper om, RunStore runs) {
        this.file = props.configPath().resolve("unenroll.json");
        this.om = om;
        this.runs = runs;
    }

    /** Read on every call, so an edited file applies without a restart. */
    public UnenrollProfile profile() { return UnenrollProfile.load(file, om); }

    /** Where to connect. The password is used once and dropped. */
    public record Db(String host, Integer port, String schema, String user, String password) {
        @Override public String toString() { return "Db[" + host + ":" + port + "/" + schema + " as " + user + "]"; }
    }

    public record Request(String cif, String schema, String performedBy, Db db, String confirmCif, boolean acknowledgeWarnings) {}

    public record ScriptResult(String runId, String cif, String fileName, String sql, UnenrollEngine.Plan preview) {}

    public record ExecuteResult(String runId, String cif, long deleted, List<UnenrollEngine.StepResult> steps) {}

    // ------------------------------------------------------------------

    public UnenrollEngine.Plan preview(Request r) {
        UnenrollProfile p = profile();
        UnenrollEngine engine = new UnenrollEngine(p);
        engine.checkKey(r.cif());
        String schema = schema(r, p);
        String runId = runs.start("disembark", "PREVIEW", null, r.performedBy());
        try (Connection c = connect(r.db(), schema, true)) {
            UnenrollEngine.Plan plan = engine.preview(jdbc(c), r.cif(), schema);
            runs.finish(runId, "SUCCESS", (int) plan.total(), 0, 0, details(r, schema, plan.total(), plan.resolved()));
            return plan;
        } catch (SQLException e) {
            failed(runId, r, schema, e.getMessage());
            throw new IllegalStateException("Could not read the database: " + e.getMessage());
        } catch (RuntimeException e) {
            failed(runId, r, schema, e.getMessage());
            throw e;
        }
    }

    public ScriptResult script(Request r) {
        UnenrollProfile p = profile();
        UnenrollEngine engine = new UnenrollEngine(p);
        engine.checkKey(r.cif());
        String schema = schema(r, p);
        UnenrollEngine.Plan plan = r.db() == null || blank(r.db().host()) ? null : preview(r);
        String runId = runs.start("disembark", "SCRIPT", null, r.performedBy());
        String sql = engine.script(r.cif(), schema, runId, r.performedBy(), plan);
        runs.finish(runId, "SUCCESS", 0, 0, 0, details(r, schema, plan == null ? -1 : plan.total(), plan == null ? Map.of() : plan.resolved()));
        String name = "disembark_" + r.cif() + "_" + runId.substring(0, 8) + ".sql";
        return new ScriptResult(runId, r.cif(), name, sql, plan);
    }

    public ExecuteResult execute(Request r) {
        UnenrollProfile p = profile();
        if (!p.allowExecute)
            throw new IllegalStateException("Deleting from the database is switched off (allowExecute in config/unenroll.json). Write the script and run it instead.");
        UnenrollEngine engine = new UnenrollEngine(p);
        engine.checkKey(r.cif());
        if (!r.cif().equals(r.confirmCif()))
            throw new IllegalArgumentException("Type the " + p.keyLabel + " again to confirm; it does not match.");
        if (r.db() == null || blank(r.db().host())) throw new IllegalArgumentException("Enter the database connection first.");
        String schema = schema(r, p);
        String runId = runs.start("disembark", "EXECUTE", null, r.performedBy());
        try (Connection c = connect(r.db(), schema, false)) {
            c.setAutoCommit(false);
            try {
                NamedParameterJdbcTemplate jdbc = jdbc(c);
                UnenrollEngine.Plan plan = engine.preview(jdbc, r.cif(), schema);
                boolean found = plan.checks().stream().anyMatch(x -> !x.findings().isEmpty());
                if (found && !r.acknowledgeWarnings())
                    throw new IllegalStateException("A check found something shared with another CIF. Read the preview and tick the acknowledgement to go ahead.");
                List<UnenrollEngine.StepResult> steps = engine.execute(jdbc, r.cif(), schema);
                c.commit();
                long total = steps.stream().filter(s -> "DELETED".equals(s.state())).mapToLong(UnenrollEngine.StepResult::rows).sum();
                runs.finish(runId, "SUCCESS", (int) total, (int) total, 0, details(r, schema, total, plan.resolved()));
                log.warn("Disembark executed run={} cif={} schema={} rowsDeleted={}", runId, r.cif(), schema, total);
                return new ExecuteResult(runId, r.cif(), total, steps);
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            failed(runId, r, schema, e.getMessage());
            throw new IllegalStateException("The database refused: " + e.getMessage() + ". Nothing was committed.");
        } catch (RuntimeException e) {
            failed(runId, r, schema, e.getMessage());
            throw e;
        }
    }

    public List<Map<String, Object>> history(int limit) { return runs.recent("disembark", limit); }

    // ------------------------------------------------------------------

    /** A failed run still records which customer it was for. */
    private void failed(String runId, Request r, String schema, String message) {
        runs.finish(runId, "FAILED", 0, 0, 0, details(r, schema, -1, Map.of()));
        runs.fail(runId, message);
    }

    private static String schema(Request r, UnenrollProfile p) {
        String s = r.db() != null && !blank(r.db().schema()) ? r.db().schema() : !blank(r.schema()) ? r.schema() : p.schema;
        if (!UnenrollProfile.IDENT.matcher(s).matches()) throw new IllegalArgumentException("Schema '" + s + "' is not a plain name.");
        return s;
    }

    private static Connection connect(Db db, String schema, boolean readOnly) throws SQLException {
        if (db == null || blank(db.host())) throw new IllegalArgumentException("Enter the database connection to preview.");
        if (!HOST.matcher(db.host()).matches()) throw new IllegalArgumentException("The host may use letters, digits, dots and dashes only.");
        int port = db.port() == null ? 3306 : db.port();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("The port must be between 1 and 65535.");
        if (blank(db.user())) throw new IllegalArgumentException("Enter the database user.");
        String url = "jdbc:mysql://" + db.host() + ":" + port + "/" + schema
                + "?connectTimeout=8000&socketTimeout=120000&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8";
        Connection c = DriverManager.getConnection(url, db.user(), db.password() == null ? "" : db.password());
        c.setReadOnly(readOnly);
        return c;
    }

    static NamedParameterJdbcTemplate jdbc(Connection c) {
        return new NamedParameterJdbcTemplate(new SingleConnectionDataSource(c, true));
    }

    private static Map<String, Object> details(Request r, String schema, long total, Map<String, Integer> resolved) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("cif", r.cif());
        d.put("schema", schema);
        if (r.db() != null && !blank(r.db().host())) d.put("database", r.db().host() + ":" + (r.db().port() == null ? 3306 : r.db().port()));
        d.put("rows", total);
        d.put("resolved", resolved);
        return d;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
