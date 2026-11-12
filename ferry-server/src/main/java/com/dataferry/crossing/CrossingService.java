package com.dataferry.crossing;

import com.dataferry.boarding.BatchStore;
import com.dataferry.config.ConfigModel.TargetEnvironment;
import com.dataferry.config.ConfigModel.TemenosTarget;
import com.dataferry.config.ConfigStore;
import com.dataferry.core.Ids;
import com.dataferry.core.Json;
import com.dataferry.mock.MockTarget;
import com.dataferry.register.IdRegister;
import com.dataferry.store.RunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crossing: runs a reviewed plan against the target.
 *
 * <ul>
 *   <li>Steps run in plan order inside each contract; contracts run in parallel (A-11).</li>
 *   <li>A step succeeds only when the answer is clean (A-10) <b>and</b>, with read-back on, the
 *       database holds what the call should have created.</li>
 *   <li>Nothing is created twice: the ID register is checked before every step, and a call that
 *       timed out after sending is never retried blindly: read-back looks for what it created.</li>
 *   <li>The first contracts are a pilot; the run pauses after them. It also pauses on a read-back
 *       mismatch, an unknown outcome, too many failures in a row, or too high a failure share.</li>
 * </ul>
 * Nothing is sent to an environment whose liveCalls is off, except the built-in mock target.
 */
@Service
public class CrossingService {

    private static final Logger log = LoggerFactory.getLogger(CrossingService.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+):([^{}]+)\\.(\\w+)\\}\\}");
    private static final Map<String, String> TARGET_TYPE = Map.of("CONTRACT", "contractId", "USER", "customerId", "SIGNATORY_GROUP", "signatoryGroupId");

    private final ConfigStore config;
    private final BatchStore batches;
    private final IdRegister register;
    private final RunStore runs;
    private final Json json;
    private final ObjectMapper om;
    private final NamedParameterJdbcTemplate store;
    private final MockTarget mock;
    private final Map<String, Run> active = new ConcurrentHashMap<>();
    private final ExecutorService launcher = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "crossing"); t.setDaemon(true); return t; });

    public CrossingService(ConfigStore config, BatchStore batches, IdRegister register, RunStore runs, Json json,
                           NamedParameterJdbcTemplate store, MockTarget mock) {
        this.config = config;
        this.batches = batches;
        this.register = register;
        this.runs = runs;
        this.json = json;
        this.om = json.mapper();
        this.store = store;
        this.mock = mock;
    }

    public record Db(String host, Integer port, String schema, String user, String password) {
        @Override public String toString() { return "Db[" + host + ":" + port + "/" + schema + "]"; }
    }

    /** readBack: NONE, MOCK (the built-in mock's database) or DATABASE (a read-only MySQL connection). */
    public record StartRequest(String batchId, List<String> contractRefs, Integer pilot, Integer parallelism,
                               String readBack, Db db, String performedBy) {}

    // ================================================================== start, continue, stop

    public Map<String, Object> start(StartRequest req) {
        String batchId = Ids.requireKey(req.batchId(), "batch id");
        Map<String, Object> batch = batches.batch(batchId);
        if (!"PLANNED".equals(batch.get("status")))
            throw new IllegalStateException("Plan batch " + batchId + " in Boarding first (it is " + batch.get("status") + ").");
        for (Run r : active.values())
            if (r.batchId.equals(batchId) && r.isLive()) throw new IllegalStateException("Batch " + batchId + " already has a crossing in progress (" + r.id + ").");

        ConfigStore.Snapshot snap = config.get();
        TargetEnvironment env = snap.environment();
        boolean mockEnv = isBuiltInMock(env);
        if (!env.liveCalls && !mockEnv)
            throw new IllegalStateException("Live calls are off for " + env.label + ". Switch liveCalls on in config/environments.json only when you mean to write to it.");
        String key = System.getenv(String.valueOf(env.appKeyVariable)), secret = System.getenv(String.valueOf(env.appSecretVariable));
        if (blank(key) || blank(secret)) {
            if (!mockEnv) throw new IllegalStateException("Set the environment variables " + env.appKeyVariable + " and " + env.appSecretVariable
                    + " (the app key and secret) before starting the server.");
            key = "mock-key"; secret = "mock-secret";
        }
        CrossingConfig cfg = CrossingConfig.load(snap.configDir().resolve("crossing.json"), om);

        List<Map<String, Object>> voyages = new ArrayList<>(batches.plans(batchId, null));
        if (req.contractRefs() != null && !req.contractRefs().isEmpty()) {
            Set<String> want = new HashSet<>(req.contractRefs());
            voyages.removeIf(v -> !want.contains(String.valueOf(v.get("contractRef"))));
        }
        if (voyages.isEmpty()) throw new IllegalArgumentException("No planned contracts to run.");

        String mode = req.readBack() == null ? (mockEnv ? "MOCK" : "NONE") : req.readBack().toUpperCase(Locale.ROOT);
        ReadBack readBack;
        Connection conn = null;
        switch (mode) {
            case "NONE" -> readBack = new ReadBack(null, cfg.readBack, "NONE");
            case "MOCK" -> {
                if (!mockEnv) throw new IllegalArgumentException("The built-in mock database only applies to the mock target.");
                readBack = new ReadBack(new NamedParameterJdbcTemplate(mock.database()), cfg.readBack, "MOCK");
            }
            case "DATABASE" -> {
                conn = connect(req.db());
                readBack = new ReadBack(new NamedParameterJdbcTemplate(new SingleConnectionDataSource(conn, true)), cfg.readBack, "DATABASE");
            }
            default -> throw new IllegalArgumentException("readBack must be NONE, MOCK or DATABASE.");
        }

        FabricClient client = new FabricClient(env, snap.temenos().basePath, key, secret, cfg, om);
        try {
            client.login();
        } catch (RuntimeException e) {
            closeQuietly(conn);
            throw e;
        }

        int pilot = req.pilot() != null ? Math.max(0, req.pilot()) : cfg.pilotContracts;
        int parallelism = req.parallelism() != null ? req.parallelism() : parseInt(config.choice("A-11"), 4);
        parallelism = Math.max(1, Math.min(parallelism, 16));
        String runId = runs.start("crossing", "RUN", batchId, req.performedBy());

        store.update("INSERT INTO ferry_crossing (run_id, batch_id, environment, state, read_back, pilot, parallelism, contracts, started_by, updated_at) " +
                        "VALUES (:r, :b, :e, 'RUNNING', :rb, :p, :n, :c, :by, :at)",
                new MapSqlParameterSource("r", runId).addValue("b", batchId).addValue("e", env.id).addValue("rb", mode).addValue("p", pilot)
                        .addValue("n", parallelism).addValue("c", voyages.size()).addValue("by", req.performedBy()).addValue("at", now()));
        int seq = 0;
        for (Map<String, Object> v : voyages)
            store.update("INSERT INTO ferry_crossing_voyage (run_id, contract_ref, seq, outcome) VALUES (:r, :c, :s, 'QUEUED')",
                    new MapSqlParameterSource("r", runId).addValue("c", v.get("contractRef")).addValue("s", ++seq));

        Run run = new Run(runId, batchId, env, cfg, snap.temenos(), client, readBack, conn, voyages, pilot, parallelism, req.performedBy());
        active.put(runId, run);
        launcher.submit(run::execute);
        log.info("Crossing {} started: batch {} -> {} ({} contracts, pilot {}, parallel {}, read-back {})",
                runId, batchId, env.id, voyages.size(), pilot, parallelism, mode);
        return status(runId);
    }

    public Map<String, Object> resume(String runId, String by) {
        Run r = active.get(Ids.requireKey(runId, "run id"));
        if (r == null || !r.isLive()) throw new IllegalStateException("Run " + runId + " is not paused.");
        r.resume(by);
        return status(runId);
    }

    public Map<String, Object> stop(String runId, String by) {
        Run r = active.get(Ids.requireKey(runId, "run id"));
        if (r == null || !r.isLive()) throw new IllegalStateException("Run " + runId + " is not running.");
        r.stop(by);
        return status(runId);
    }

    // ================================================================== read

    public List<Map<String, Object>> list(String batchId, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (batchId != null && !batchId.isBlank()) { where = " WHERE batch_id = :b"; p.addValue("b", batchId); }
        return store.queryForList("SELECT run_id, batch_id, environment, state, reason, read_back, pilot, parallelism, contracts, started_by, started_at, finished_at " +
                "FROM ferry_crossing" + where + " ORDER BY started_at DESC LIMIT " + Math.max(1, Math.min(limit, 200)), p);
    }

    public Map<String, Object> status(String runId) {
        List<Map<String, Object>> rows = store.queryForList("SELECT run_id, batch_id, environment, state, reason, read_back, pilot, parallelism, contracts, " +
                "started_by, started_at, updated_at, finished_at FROM ferry_crossing WHERE run_id = :r", Map.of("r", runId));
        if (rows.isEmpty()) throw new NoSuchElementException("No crossing run " + runId);
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> voyages = store.queryForList("SELECT contract_ref, seq, outcome, reason, started_at, finished_at FROM ferry_crossing_voyage " +
                "WHERE run_id = :r ORDER BY seq", Map.of("r", runId));
        Map<String, Integer> by = new TreeMap<>();
        voyages.forEach(v -> by.merge(String.valueOf(v.get("outcome")), 1, Integer::sum));
        out.put("byOutcome", by);
        out.put("voyages", voyages);
        out.put("stepsByOutcome", store.queryForList("SELECT outcome, COUNT(*) AS steps FROM ferry_crossing_step WHERE run_id = :r GROUP BY outcome ORDER BY outcome", Map.of("r", runId)));
        return out;
    }

    public List<Map<String, Object>> steps(String runId, String contractRef) {
        return store.queryForList("SELECT step_id, operation, subject_type, subject_ref, outcome, attempts, target_id, error_code, message, " +
                        "CAST(request_json AS VARCHAR(100000)) AS request_json, CAST(response_json AS VARCHAR(100000)) AS response_json, " +
                        "CAST(read_back AS VARCHAR(100000)) AS read_back, millis, at FROM ferry_crossing_step WHERE run_id = :r AND contract_ref = :c ORDER BY id",
                Map.of("r", runId, "c", contractRef));
    }

    // ================================================================== one run

    final class Run {
        final String id, batchId, by;
        final TargetEnvironment env;
        final CrossingConfig cfg;
        final TemenosTarget temenos;
        final FabricClient client;
        final ReadBack readBack;
        final Connection conn;
        final List<Map<String, Object>> voyages;
        final int pilot, parallelism;

        private String state = "RUNNING", reason;
        private boolean pilotApproved;
        private int index, started, inFlight, completed, failed, consecutive;
        private volatile long backoffUntil;

        Run(String id, String batchId, TargetEnvironment env, CrossingConfig cfg, TemenosTarget temenos, FabricClient client, ReadBack readBack,
            Connection conn, List<Map<String, Object>> voyages, int pilot, int parallelism, String by) {
            this.id = id; this.batchId = batchId; this.env = env; this.cfg = cfg; this.temenos = temenos; this.client = client;
            this.readBack = readBack; this.conn = conn; this.voyages = voyages; this.pilot = pilot; this.parallelism = parallelism; this.by = by;
            this.pilotApproved = pilot == 0;
        }

        synchronized boolean isLive() { return state.equals("RUNNING") || state.equals("PAUSED"); }

        void execute() {
            ExecutorService workers = Executors.newFixedThreadPool(parallelism, r -> { Thread t = new Thread(r, "crossing-" + id.substring(0, 8)); t.setDaemon(true); return t; });
            try {
                List<Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < parallelism; i++) fs.add(workers.submit(this::work));
                for (Future<?> f : fs) f.get();
                synchronized (this) {
                    if (state.equals("RUNNING")) setState("COMPLETED", null);
                }
            } catch (Exception e) {
                log.error("Crossing {} failed", id, e);
                synchronized (this) { setState("FAILED", "The run stopped unexpectedly: " + e.getMessage()); }
            } finally {
                workers.shutdownNow();
                closeQuietly(conn);
                synchronized (this) {
                    store.update("UPDATE ferry_crossing SET finished_at = :at WHERE run_id = :r", Map.of("at", now(), "r", id));
                    runs.finish(id, state, completed, completed - failed, failed, Map.of("state", state, "reason", reason == null ? "" : reason));
                }
            }
        }

        private void work() {
            Map<String, Object> v;
            while ((v = next()) != null) {
                String ref = String.valueOf(v.get("contractRef"));
                String[] outcome;
                try {
                    outcome = voyage(v);
                } catch (RuntimeException e) {
                    log.error("Voyage {} failed in run {}", ref, id, e);
                    outcome = new String[]{"FAILED", "Unexpected error: " + e.getMessage()};
                }
                finished(ref, outcome[0], outcome[1]);
            }
        }

        private synchronized Map<String, Object> next() {
            while (true) {
                if (!isLive()) return null;
                if (state.equals("PAUSED")) { waitQuietly(); continue; }
                if (index >= voyages.size()) return null;
                if (!pilotApproved && started >= pilot) {
                    if (inFlight == 0) pause("The pilot of " + pilot + " contract" + (pilot == 1 ? "" : "s") + " is done. Check the results, then continue.");
                    else waitQuietly();
                    continue;
                }
                started++;
                inFlight++;
                Map<String, Object> v = voyages.get(index++);
                store.update("UPDATE ferry_crossing_voyage SET outcome = 'RUNNING', started_at = :at WHERE run_id = :r AND contract_ref = :c",
                        Map.of("at", now(), "r", id, "c", v.get("contractRef")));
                return v;
            }
        }

        private synchronized void finished(String ref, String outcome, String why) {
            inFlight--;
            completed++;
            store.update("UPDATE ferry_crossing_voyage SET outcome = :o, reason = :w, finished_at = :at WHERE run_id = :r AND contract_ref = :c",
                    new MapSqlParameterSource("o", outcome).addValue("w", cut(why, 1000)).addValue("at", now()).addValue("r", id).addValue("c", ref));
            boolean bad = Set.of("FAILED", "UNKNOWN", "MISMATCH").contains(outcome);
            if (bad) { failed++; consecutive++; } else if (!outcome.equals("SKIPPED")) consecutive = 0;
            CrossingConfig.StopRules s = cfg.stopRules;
            if (outcome.equals("MISMATCH") && s.stopOnReadBackMismatch)
                pause("Contract " + ref + ": the database does not hold what the call reported. A guess about the target may be wrong; check it before continuing.");
            else if (outcome.equals("UNKNOWN") && s.stopOnUnknownOutcome)
                pause("Contract " + ref + ": the outcome of a call is unknown (" + why + "). Check the target before continuing; the step will not be sent again blindly.");
            else if (s.maxConsecutiveFailures > 0 && consecutive >= s.maxConsecutiveFailures)
                pause(consecutive + " contracts failed in a row. The last: " + ref + " (" + why + ").");
            else if (completed >= s.minVoyagesBeforeShare && (double) failed / completed > s.maxFailedShare)
                pause(failed + " of " + completed + " contracts failed, more than " + Math.round(s.maxFailedShare * 100) + "%.");
            notifyAll();
        }

        synchronized void resume(String who) {
            if (!state.equals("PAUSED")) throw new IllegalStateException("Run " + id + " is " + state + ".");
            if (!pilotApproved && started >= pilot) pilotApproved = true;
            consecutive = 0;
            runs.finish(id, "RUNNING", completed, completed - failed, failed, Map.of("resumedBy", who == null ? "unknown" : who));
            setState("RUNNING", null);
            notifyAll();
        }

        synchronized void stop(String who) {
            setState("STOPPED", "Stopped by " + (who == null || who.isBlank() ? "the operator" : who) + ". Contracts already sent are kept; queued ones were not sent.");
            notifyAll();
        }

        private void pause(String why) { setState("PAUSED", why); }

        private void setState(String s, String why) {
            state = s;
            reason = why;
            store.update("UPDATE ferry_crossing SET state = :s, reason = :w, updated_at = :at WHERE run_id = :r",
                    new MapSqlParameterSource("s", s).addValue("w", cut(why, 1000)).addValue("at", now()).addValue("r", id));
            if (!s.equals("RUNNING")) log.info("Crossing {} {}: {}", id, s, why);
        }

        private void waitQuietly() {
            try { wait(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); setState("STOPPED", "Interrupted"); }
        }

        // -------------------------------------------------------------- one contract

        /** Returns {outcome, reason}: SUCCESS, PARTIAL, FAILED, UNKNOWN, MISMATCH, SKIPPED. */
        @SuppressWarnings("unchecked")
        private String[] voyage(Map<String, Object> v) {
            String ref = String.valueOf(v.get("contractRef"));
            List<Map<String, Object>> steps = (List<Map<String, Object>>) v.get("steps");
            Map<String, String> outcomeByStep = new HashMap<>();
            String contractOutcome = null, firstProblem = null;
            boolean partial = false, mismatch = false, unknown = false;
            for (Map<String, Object> s : steps) {
                if (!isLiveForSteps()) { outcomeByStep.put(str(s.get("stepId")), "SKIPPED_STOPPED"); continue; }
                StepResult r = step(ref, s, outcomeByStep);
                outcomeByStep.put(str(s.get("stepId")), r.outcome);
                if (contractOutcome == null) contractOutcome = r.outcome;
                if (!Set.of("SUCCESS", "ALREADY_DONE", "RECOVERED").contains(r.outcome)) {
                    partial = true;
                    if (firstProblem == null) firstProblem = str(s.get("label")) + ": " + r.outcome + (r.message == null ? "" : " (" + r.message + ")");
                }
                mismatch |= r.outcome.equals("MISMATCH");
                unknown |= r.outcome.equals("UNKNOWN");
            }
            if ("SKIPPED_BLOCKED".equals(contractOutcome)) return new String[]{"SKIPPED", "The contract was blocked in the plan."};
            if (mismatch) return new String[]{"MISMATCH", firstProblem};
            if (unknown) return new String[]{"UNKNOWN", firstProblem};
            if (!Set.of("SUCCESS", "ALREADY_DONE", "RECOVERED").contains(contractOutcome)) return new String[]{"FAILED", firstProblem};
            return partial ? new String[]{"PARTIAL", firstProblem} : new String[]{"SUCCESS", null};
        }

        private synchronized boolean isLiveForSteps() { return !state.equals("STOPPED") && !state.equals("FAILED"); }

        private record StepResult(String outcome, String message) {}

        @SuppressWarnings("unchecked")
        private StepResult step(String ref, Map<String, Object> s, Map<String, String> earlier) {
            String stepId = str(s.get("stepId")), op = str(s.get("operation")), type = str(s.get("subjectType")), subject = str(s.get("subjectRef"));
            Map<String, Object> planned = (Map<String, Object>) s.get("payload");
            if ("BLOCKED".equals(s.get("status")))
                return record(ref, s, "SKIPPED_BLOCKED", 0, null, null, String.join("; ", (List<String>) s.getOrDefault("reasons", List.of())), planned, null, null, 0);
            Optional<String> done = register.find(type, subject);
            if (done.isPresent()) return record(ref, s, "ALREADY_DONE", 0, done.get(), null, "Already created; found in the ID register.", planned, null, null, 0);
            for (String dep : (List<String>) s.getOrDefault("dependsOn", List.of())) {
                String o = earlier.get(dep);
                if (!Set.of("SUCCESS", "ALREADY_DONE", "RECOVERED").contains(String.valueOf(o)))
                    return record(ref, s, "SKIPPED_DEPENDENCY", 0, null, null, "Step " + dep + " did not succeed (" + o + ").", planned, null, null, 0);
            }
            Map<String, Object> payload;
            try { payload = resolve(planned); }
            catch (IllegalStateException e) { return record(ref, s, "SKIPPED_DEPENDENCY", 0, null, null, e.getMessage(), planned, null, null, 0); }

            String path = Optional.ofNullable(temenos.operations.get(op)).map(o -> o.path).orElse("/" + op);
            Map<String, Object> params = readBackParams(payload);
            int attempts = 0;
            long millis = 0;
            FabricClient.Response resp = null;
            List<Integer> backoff = cfg.retry.backoffSeconds;
            while (attempts < Math.max(1, cfg.retry.attempts)) {
                waitForBackoff();
                attempts++;
                resp = client.call(path, payload);
                millis += resp.millis();
                if (resp.kind() == FabricClient.Kind.BODY || resp.kind() == FabricClient.Kind.UNKNOWN) break;
                long secs = backoff.isEmpty() ? 2 : backoff.get(Math.min(attempts - 1, backoff.size() - 1));
                if (resp.kind() == FabricClient.Kind.RETRYABLE) backoffUntil = Math.max(backoffUntil, System.currentTimeMillis() + secs * 1000);
                else sleep(secs * 1000);
            }

            if (resp.kind() == FabricClient.Kind.UNKNOWN) {
                Optional<String> found = readBack.lookup(op, params);
                if (found.isPresent()) return succeed(ref, s, "RECOVERED", attempts, found.get(), payload, params, resp, millis,
                        "No answer in time, but read-back found what the call created.");
                return record(ref, s, "UNKNOWN", attempts, null, null, resp.text() + (readBack.enabled() ? "; read-back found nothing." : "; read-back is off, so it was not sent again."),
                        payload, resp, null, millis);
            }
            if (resp.kind() != FabricClient.Kind.BODY)
                return record(ref, s, "FAILED", attempts, null, String.valueOf(resp.httpStatus()), "Gave up after " + attempts + " attempts: " + resp.text(), payload, resp, null, millis);

            String error = judge(resp);
            if (error != null) {
                String code = errorCode(resp.body());
                String catalogue = code == null ? null : temenos.errorCatalogue.get("ERR_" + code);
                return record(ref, s, "FAILED", attempts, null, code, catalogue == null ? error : error + " (" + catalogue + ")", payload, resp, null, millis);
            }
            String newId = null;
            for (String f : cfg.resultIds.byOperation.getOrDefault(op, List.of("id")))
                if (resp.body().get(f) != null && !String.valueOf(resp.body().get(f)).isBlank()) { newId = String.valueOf(resp.body().get(f)); break; }
            if (newId == null) {
                Optional<String> found = readBack.lookup(op, params);
                if (found.isPresent()) return succeed(ref, s, "RECOVERED", attempts, found.get(), payload, params, resp, millis, "The answer had no ID; read-back found it.");
                return record(ref, s, "UNKNOWN", attempts, null, null, "Success reported without an ID" + (readBack.enabled() ? ", and read-back could not find one." : "; turn read-back on so it can be found."),
                        payload, resp, null, millis);
            }
            return succeed(ref, s, "SUCCESS", attempts, newId, payload, params, resp, millis, null);
        }

        private StepResult succeed(String ref, Map<String, Object> s, String outcome, int attempts, String newId, Map<String, Object> payload,
                                   Map<String, Object> params, FabricClient.Response resp, long millis, String note) {
            String type = str(s.get("subjectType"));
            try {
                register.register(type, str(s.get("subjectRef")), TARGET_TYPE.getOrDefault(type, "id"), newId, batchId, id);
            } catch (IllegalStateException e) {
                return record(ref, s, "FAILED", attempts, newId, null, e.getMessage(), payload, resp, null, millis);
            }
            params.put("id", newId);
            ReadBack.Result rb = readBack.verify(str(s.get("operation")), params);
            if (rb.verdict().equals("MISMATCH")) {
                String what = rb.checks().stream().filter(c -> !c.ok()).map(c -> c.label() + ": expected " + c.expected() + ", found "
                        + (c.actual() == null ? c.error() : c.actual())).reduce((a, b) -> a + "; " + b).orElse("");
                return record(ref, s, "MISMATCH", attempts, newId, null, what, payload, resp, rb, millis);
            }
            return record(ref, s, outcome, attempts, newId, null, note, payload, resp, rb, millis);
        }

        private StepResult record(String ref, Map<String, Object> s, String outcome, int attempts, String targetId, String code, String message,
                                  Map<String, Object> request, FabricClient.Response resp, ReadBack.Result rb, long millis) {
            store.update("INSERT INTO ferry_crossing_step (run_id, contract_ref, step_id, operation, subject_type, subject_ref, outcome, attempts, target_id, " +
                            "error_code, message, request_json, response_json, read_back, millis) VALUES (:r, :c, :s, :o, :t, :sr, :out, :a, :tid, :code, :m, :req, :resp, :rb, :ms)",
                    new MapSqlParameterSource("r", id).addValue("c", ref).addValue("s", s.get("stepId")).addValue("o", s.get("operation"))
                            .addValue("t", s.get("subjectType")).addValue("sr", s.get("subjectRef")).addValue("out", outcome).addValue("a", attempts)
                            .addValue("tid", targetId).addValue("code", code).addValue("m", cut(message, 2000))
                            .addValue("req", request == null ? null : json.write(request))
                            .addValue("resp", resp == null ? null : resp.kind() == FabricClient.Kind.BODY ? json.write(Map.of("http", resp.httpStatus(), "body", resp.body())) : json.write(Map.of("note", resp.text())))
                            .addValue("rb", rb == null ? null : json.write(rb)).addValue("ms", millis));
            return new StepResult(outcome, message);
        }

        /** Success per the response rules (A-10). Null when clean, otherwise what went wrong. */
        private String judge(FabricClient.Response r) {
            var rules = temenos.response;
            if (r.httpStatus() != 200) return "HTTP " + r.httpStatus();
            Object status = r.body().get(rules.statusField);
            if (status == null && rules.requireStatusField) return "No " + rules.statusField + " in the answer";
            if (status != null && !rules.successValues.contains(String.valueOf(status))) return rules.statusField + " " + status;
            for (String f : rules.errorFields) {
                Object v = r.body().get(f);
                if (v != null && !String.valueOf(v).isBlank()) {
                    List<String> msg = new ArrayList<>();
                    for (String m : rules.messageFields) if (r.body().get(m) != null) msg.add(String.valueOf(r.body().get(m)).trim());
                    return msg.isEmpty() ? f + " " + v : String.join(" ", msg);
                }
            }
            return null;
        }

        private void waitForBackoff() {
            long wait = backoffUntil - System.currentTimeMillis();
            if (wait > 0) sleep(wait);
        }
    }

    // ================================================================== helpers

    /** The inner vendor code when present (FailureReason "[ErrorCode=10401, ...]"), else dbpErrCode. */
    static String errorCode(Map<String, Object> body) {
        Object fr = body.get("FailureReason");
        if (fr != null) {
            Matcher m = Pattern.compile("ErrorCode=(\\d+)").matcher(String.valueOf(fr));
            if (m.find()) return m.group(1);
        }
        Object c = body.get("dbpErrCode");
        return c == null ? null : String.valueOf(c);
    }

    /** Fills {{TYPE:ref.field}} from the ID register; a missing ID means an earlier step did not succeed. */
    Map<String, Object> resolve(Map<String, Object> planned) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : planned.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s && s.contains("{{")) {
                Matcher m = PLACEHOLDER.matcher(s);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    String found = register.find(m.group(1), m.group(2))
                            .orElseThrow(() -> new IllegalStateException("No ID yet for " + m.group(1).toLowerCase(Locale.ROOT) + " " + m.group(2) + "."));
                    m.appendReplacement(sb, Matcher.quoteReplacement(found));
                }
                m.appendTail(sb);
                if (sb.toString().contains("{{")) throw new IllegalStateException("The request still holds an unfilled value: " + sb);
                v = sb.toString();
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    /** Request fields plus the counts read-back checks compare with. */
    private Map<String, Object> readBackParams(Map<String, Object> payload) {
        Map<String, Object> p = new HashMap<>(payload);
        try {
            if (payload.get("cifList") instanceof String s) {
                long n = 0;
                for (Object c : om.readValue(s, List.class)) {
                    Object accs = ((Map<?, ?>) c).get("accounts");
                    if (accs instanceof List<?> l) n += l.size();
                }
                p.put("accountCount", n);
            }
            if (payload.get("signatories") instanceof String s) p.put("signatoryCount", (long) om.readValue(s, List.class).size());
        } catch (Exception ignored) { /* counts stay absent; the check then fails visibly */ }
        return p;
    }

    static boolean isBuiltInMock(TargetEnvironment env) {
        try {
            URI u = URI.create(env.hostUrl);
            return Set.of("localhost", "127.0.0.1").contains(u.getHost()) && u.getPath() != null && u.getPath().startsWith("/mock");
        } catch (Exception e) { return false; }
    }

    private static Connection connect(Db db) {
        if (db == null || blank(db.host()) || blank(db.user())) throw new IllegalArgumentException("Enter the database connection for read-back.");
        if (!db.host().matches("[A-Za-z0-9.-]{1,253}")) throw new IllegalArgumentException("The host may use letters, digits, dots and dashes only.");
        String schema = blank(db.schema()) ? "dbxdb" : db.schema();
        if (!schema.matches("[A-Za-z0-9_$]{1,64}")) throw new IllegalArgumentException("Schema '" + schema + "' is not a plain name.");
        try {
            Connection c = DriverManager.getConnection("jdbc:mysql://" + db.host() + ":" + (db.port() == null ? 3306 : db.port()) + "/" + schema
                    + "?connectTimeout=8000&socketTimeout=60000&allowPublicKeyRetrieval=true", db.user(), db.password() == null ? "" : db.password());
            c.setReadOnly(true);
            return c;
        } catch (Exception e) {
            throw new IllegalStateException("Could not open the database for read-back: " + e.getMessage());
        }
    }

    private static void closeQuietly(Connection c) { try { if (c != null) c.close(); } catch (Exception ignored) { } }
    private static void sleep(long ms) { try { Thread.sleep(Math.max(0, ms)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static Timestamp now() { return Timestamp.from(Instant.now()); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String cut(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }
    private static int parseInt(String s, int d) { try { return Integer.parseInt(s); } catch (Exception e) { return d; } }
}
