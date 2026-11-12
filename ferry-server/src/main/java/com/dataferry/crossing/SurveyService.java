package com.dataferry.crossing;

import com.dataferry.boarding.BatchStore;
import com.dataferry.config.ConfigStore;
import com.dataferry.core.Ids;
import com.dataferry.core.Json;
import com.dataferry.mock.MockTarget;
import com.dataferry.register.IdRegister;
import com.dataferry.store.RunStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

/**
 * The Survey: a read-only look at the target database before a batch crosses. It confirms the
 * product facts Ferry relies on (approval codes, self-approval, approval actions, service
 * definitions) and finds what would collide with the batch: accounts already on a contract (the
 * product allows one contract per account), CIFs already enrolled, login names already taken.
 *
 * Verdict: GO, GO_WITH_WARNINGS, or NO_GO with the contracts that would fail, so they can be left out.
 */
@Service
public class SurveyService {

    private static final Set<String> APPROVAL_CODES = Set.of("ALL", "ANY_ONE", "ANY_TWO", "ANY_THREE", "NO_APPROVAL");

    private final BatchStore batches;
    private final ConfigStore config;
    private final IdRegister register;
    private final RunStore runs;
    private final MockTarget mock;
    private final Json json;

    public SurveyService(BatchStore batches, ConfigStore config, IdRegister register, RunStore runs, MockTarget mock, Json json) {
        this.batches = batches; this.config = config; this.register = register; this.runs = runs; this.mock = mock; this.json = json;
    }

    public record Request(String batchId, String source, CrossingService.Db db, String performedBy) {}
    public record Item(String id, String label, String status, String detail, List<String> contracts) {}
    public record Result(String runId, String batchId, String source, String verdict, List<Item> checks, List<String> blockedContracts) {}

    @SuppressWarnings("unchecked")
    public Result survey(Request r) {
        String batchId = Ids.requireKey(r.batchId(), "batch id");
        List<Map<String, Object>> plans = batches.plans(batchId, null);
        if (plans.isEmpty()) throw new IllegalStateException("Plan batch " + batchId + " in Boarding first.");
        String source = r.source() == null ? (CrossingService.isBuiltInMock(config.environment()) ? "MOCK" : "DATABASE") : r.source().toUpperCase(Locale.ROOT);
        String runId = runs.start("survey", "SURVEY", batchId, r.performedBy());

        // what the batch would send
        Map<String, List<String>> accountToContracts = new LinkedHashMap<>(), cifToContracts = new LinkedHashMap<>(), loginToContracts = new LinkedHashMap<>();
        Set<String> serviceDefinitions = new TreeSet<>();
        for (Map<String, Object> v : plans) {
            String ref = String.valueOf(v.get("contractRef"));
            for (Map<String, Object> s : (List<Map<String, Object>>) v.get("steps")) {
                Map<String, Object> p = (Map<String, Object>) s.get("payload");
                if ("CreateContract".equals(s.get("operation"))) {
                    if (p.get("serviceDefinitionId") != null) serviceDefinitions.add(String.valueOf(p.get("serviceDefinitionId")));
                    try {
                        for (Object c : json.mapper().readValue(String.valueOf(p.get("cifList")), List.class)) {
                            Map<?, ?> cm = (Map<?, ?>) c;
                            cifToContracts.computeIfAbsent(String.valueOf(cm.get("cif")), k -> new ArrayList<>()).add(ref);
                            for (Object a : (List<?>) cm.get("accounts"))
                                accountToContracts.computeIfAbsent(String.valueOf(((Map<?, ?>) a).get("accountId")), k -> new ArrayList<>()).add(ref);
                        }
                    } catch (Exception ignored) { /* a malformed cifList shows up in Crossing as a failure */ }
                }
                if (p != null && p.get("legacyUserName") != null)
                    loginToContracts.computeIfAbsent(String.valueOf(p.get("legacyUserName")), k -> new ArrayList<>()).add(ref);
            }
        }

        Connection conn = null;
        try {
            NamedParameterJdbcTemplate db;
            if (source.equals("MOCK")) db = new NamedParameterJdbcTemplate(mock.database());
            else {
                conn = open(r.db());
                db = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(conn, true));
            }
            List<Item> items = new ArrayList<>();
            Set<String> blocked = new TreeSet<>();
            NamedParameterJdbcTemplate q = db;

            items.add(check("APPROVAL_CODES", "Approval codes the product uses", () -> {
                Set<String> codes = new TreeSet<>(q.queryForList("SELECT id FROM approvalrule", Map.of(), String.class));
                Set<String> missing = new TreeSet<>(APPROVAL_CODES); missing.removeAll(codes);
                return missing.isEmpty() ? new Item("APPROVAL_CODES", "", "OK", "All five found: " + String.join(", ", codes) + ".", List.of())
                        : new Item("APPROVAL_CODES", "", "WARN", "Missing " + missing + "; approval rules may be stored differently in this release.", List.of());
            }));
            items.add(check("SELF_APPROVAL", "Self-approval (bank-wide)", () -> {
                List<Object> v = q.queryForList("SELECT isSelfApprovalEnabled FROM application", Map.of(), Object.class);
                String on = v.isEmpty() ? "not set" : truthy(v.get(0)) ? "on" : "off";
                return new Item("SELF_APPROVAL", "", "INFO", "Self-approval is " + on + " for the whole bank (A-13).", List.of());
            }));
            items.add(check("APPROVAL_ACTIONS", "Actions that take approval rules", () -> {
                Long n = q.queryForObject("SELECT COUNT(*) FROM featureaction WHERE isApprovalAction = 1", Map.of(), Long.class);
                return new Item("APPROVAL_ACTIONS", "", n != null && n > 0 ? "INFO" : "WARN", n + " actions are flagged for approval rules in this database.", List.of());
            }));
            items.add(check("SERVICE_DEFINITIONS", "Service definitions in the plan", () -> {
                if (serviceDefinitions.isEmpty()) return new Item("SERVICE_DEFINITIONS", "", "OK", "None to check.", List.of());
                Set<String> found = new TreeSet<>(q.queryForList("SELECT id FROM servicedefinition WHERE id IN (:ids)", Map.of("ids", serviceDefinitions), String.class));
                Set<String> missing = new TreeSet<>(serviceDefinitions); missing.removeAll(found);
                List<String> affected = new ArrayList<>();
                if (!missing.isEmpty()) for (Map<String, Object> v : plans) for (Map<String, Object> s : (List<Map<String, Object>>) v.get("steps"))
                    if ("CreateContract".equals(s.get("operation")) && missing.contains(String.valueOf(((Map<String, Object>) s.get("payload")).get("serviceDefinitionId"))))
                        affected.add(String.valueOf(v.get("contractRef")));
                return missing.isEmpty() ? new Item("SERVICE_DEFINITIONS", "", "OK", "All " + found.size() + " exist.", List.of())
                        : new Item("SERVICE_DEFINITIONS", "", "ERROR", "Not in this database: " + missing + ". Check reference-data.json.", affected);
            }));
            items.add(collide(q, "ACCOUNTS_TAKEN", "Accounts already on a contract", "SELECT accountId AS k, contractId AS v FROM contractaccounts WHERE accountId IN (:ids)",
                    accountToContracts, "ERROR", "The product allows one contract per account, so these contracts would fail"));
            items.add(collide(q, "CIFS_ENROLLED", "CIFs already on a contract", "SELECT coreCustomerId AS k, contractId AS v FROM contractcorecustomers WHERE coreCustomerId IN (:ids)",
                    cifToContracts, "WARN", "These customers are already enrolled; check before crossing"));
            items.add(collide(q, "LOGINS_TAKEN", "Login names already used", "SELECT UserName AS k, id AS v FROM customer WHERE UserName IN (:ids)",
                    loginToContracts, "WARN", "These login names exist already"));
            for (Item i : items) if (i.status().equals("ERROR")) blocked.addAll(i.contracts());

            String verdict = items.stream().anyMatch(i -> i.status().equals("ERROR")) ? "NO_GO"
                    : items.stream().anyMatch(i -> i.status().equals("WARN")) ? "GO_WITH_WARNINGS" : "GO";
            runs.finish(runId, "SUCCESS", items.size(), (int) items.stream().filter(i -> !i.status().equals("ERROR")).count(), blocked.size(),
                    Map.of("verdict", verdict, "source", source, "blocked", blocked));
            return new Result(runId, batchId, source, verdict, items, new ArrayList<>(blocked));
        } catch (RuntimeException e) {
            runs.fail(runId, e.getMessage());
            throw e;
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ignored) { }
        }
    }

    private interface Body { Item run(); }

    private static Item check(String id, String label, Body body) {
        try {
            Item i = body.run();
            return new Item(id, label, i.status(), i.detail(), i.contracts());
        } catch (DataAccessException e) {
            return new Item(id, label, "NOT_AVAILABLE", "Could not check: " + root(e), List.of());
        }
    }

    /** Values the batch would send that the database already holds; skips those Ferry itself created (re-runs). */
    private Item collide(NamedParameterJdbcTemplate q, String id, String label, String sql, Map<String, List<String>> wanted, String severity, String what) {
        return check(id, label, () -> {
            if (wanted.isEmpty()) return new Item(id, label, "OK", "Nothing to check.", List.of());
            List<String> keys = new ArrayList<>(wanted.keySet());
            List<String> hits = new ArrayList<>();
            Set<String> contracts = new TreeSet<>();
            for (int i = 0; i < keys.size(); i += 1000) {
                for (Map<String, Object> row : q.queryForList(sql, new MapSqlParameterSource("ids", keys.subList(i, Math.min(keys.size(), i + 1000))))) {
                    String k = String.valueOf(row.get("k")), v = String.valueOf(row.get("v"));
                    List<String> refs = wanted.getOrDefault(k, List.of());
                    boolean ours = refs.stream().anyMatch(ref -> register.find("CONTRACT", ref).map(v::equals).orElse(false));
                    if (ours) continue;
                    hits.add(k + " (" + v + ")");
                    contracts.addAll(refs);
                }
            }
            if (hits.isEmpty()) return new Item(id, label, "OK", "None of the " + keys.size() + " in the batch.", List.of());
            return new Item(id, label, severity, what + ": " + String.join(", ", hits.subList(0, Math.min(20, hits.size())))
                    + (hits.size() > 20 ? " and " + (hits.size() - 20) + " more" : "") + ".", new ArrayList<>(contracts));
        });
    }

    private static Connection open(CrossingService.Db db) {
        if (db == null || db.host() == null || db.host().isBlank() || db.user() == null || db.user().isBlank())
            throw new IllegalArgumentException("Enter the target database connection for the survey.");
        if (!db.host().matches("[A-Za-z0-9.-]{1,253}")) throw new IllegalArgumentException("The host may use letters, digits, dots and dashes only.");
        String schema = db.schema() == null || db.schema().isBlank() ? "dbxdb" : db.schema();
        if (!schema.matches("[A-Za-z0-9_$]{1,64}")) throw new IllegalArgumentException("Schema '" + schema + "' is not a plain name.");
        try {
            Connection c = DriverManager.getConnection("jdbc:mysql://" + db.host() + ":" + (db.port() == null ? 3306 : db.port()) + "/" + schema
                    + "?connectTimeout=8000&socketTimeout=60000&allowPublicKeyRetrieval=true", db.user(), db.password() == null ? "" : db.password());
            c.setReadOnly(true);
            return c;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open the database: " + e.getMessage());
        }
    }

    private static boolean truthy(Object o) {
        if (o instanceof byte[] b) return b.length > 0 && b[0] != 0;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o);
        return s.equals("1") || s.equalsIgnoreCase("true");
    }

    private static String root(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }
}
