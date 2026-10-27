package com.dataferry.boarding;

import com.dataferry.boarding.BatchStore.Finding;
import com.dataferry.config.ConfigModel.Rule;
import com.dataferry.config.ConfigModel.TargetEnvironment;
import com.dataferry.config.ConfigStore;
import com.dataferry.store.RunStore;
import com.dataferry.target.TargetAdapter;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Boarding, part two: decide which staged records can sail. Three layers, all reported as findings:
 *  1. field rules from validation-rules.json;
 *  2. built-in checks across record types that hold for any target (an account's contract exists,
 *     a role is configured, the primary CIF has an account …);
 *  3. the target adapter's own checks (vendor field rules).
 * Where a check depends on an open question, the record is tagged with the assumption it relied on.
 * Every record is re-judged from scratch on each run.
 */
@Service
public class ValidationService {

    private final BatchStore store;
    private final CanonicalModel model;
    private final ConfigStore config;
    private final TargetAdapter target;
    private final RunStore runs;
    private final Map<String, Pattern> patterns = new HashMap<>();

    public ValidationService(BatchStore store, CanonicalModel model, ConfigStore config, TargetAdapter target, RunStore runs) {
        this.store = store;
        this.model = model;
        this.config = config;
        this.target = target;
        this.runs = runs;
    }

    public record Summary(String batchId, String runId, int records, int valid, int invalid, int excluded,
                          int errors, int warnings, List<Map<String, Object>> countsByType,
                          List<Map<String, Object>> findingSummary) {}

    public Summary validate(String batchId, String by) {
        store.batch(batchId);
        String runId = runs.start("boarding", "VALIDATE", batchId, by);
        try {
            BatchView view = new BatchView(batchId, store.allRecords(batchId, model.types()));
            if (view.all().isEmpty()) throw new IllegalStateException("Nothing is staged in batch " + batchId + " yet. Upload a file first.");
            view.all().forEach(r -> { r.status = "VALID"; r.tags.clear(); });

            List<Finding> findings = new ArrayList<>();
            Consumer<TargetAdapter.FindingOnRecord> sink = f -> {
                findings.add(f.finding());
                if ("ERROR".equals(f.finding().severity()) && !"EXCLUDED".equals(f.record().status)) f.record().status = "INVALID";
                if (f.finding().assumptionId() != null) f.record().tags.add(f.finding().assumptionId());
            };

            fieldRules(view, sink);
            accountChecks(view, sink);
            contractChecks(view, sink);
            userChecks(view, sink);
            groupChecks(view, sink);
            target.check(view, sink);

            store.saveValidation(batchId, view.all(), findings);

            int valid = 0, invalid = 0, excluded = 0;
            for (StagedRecord r : view.all()) {
                switch (r.status) { case "VALID" -> valid++; case "INVALID" -> invalid++; case "EXCLUDED" -> excluded++; default -> {} }
            }
            int errors = (int) findings.stream().filter(f -> "ERROR".equals(f.severity())).count();
            int warnings = findings.size() - errors;
            runs.finish(runId, invalid == 0 ? "SUCCESS" : "WARN", view.all().size(), valid, invalid,
                    Map.of("errors", errors, "warnings", warnings, "excluded", excluded));
            return new Summary(batchId, runId, view.all().size(), valid, invalid, excluded, errors, warnings,
                    store.counts(batchId), store.findingSummary(batchId));
        } catch (RuntimeException e) {
            runs.fail(runId, e.getMessage());
            throw e;
        }
    }

    // ---------------- 1. field rules ----------------

    private void fieldRules(BatchView view, Consumer<TargetAdapter.FindingOnRecord> sink) {
        for (Rule rule : config.get().validationRules().rules) {
            List<StagedRecord> recs = view.list(rule.recordType.toUpperCase(Locale.ROOT));
            if (recs.isEmpty()) continue;
            switch (rule.type) {
                case "required" -> recs.forEach(r -> rule.fields.forEach(f -> {
                    if (!r.has(f)) report(sink, r, rule, f, label(r.type, f) + " is required.");
                }));
                case "allowed" -> recs.forEach(r -> model.type(r.type).fields.stream().filter(f -> f.allowed != null).forEach(f -> {
                    String v = r.get(f.name);
                    if (v != null && !f.allowed.contains(v))
                        report(sink, r, rule, f.name, label(r.type, f.name) + " '" + v + "' is not one of " + f.allowed + ".");
                }));
                case "regex" -> {
                    Pattern p = patterns.computeIfAbsent(rule.pattern, Pattern::compile);
                    recs.forEach(r -> {
                        String v = r.get(rule.field);
                        if (v != null && !p.matcher(v).matches()) report(sink, r, rule, rule.field, msg(rule, label(r.type, rule.field) + " has an unexpected format."));
                    });
                }
                case "length" -> recs.forEach(r -> {
                    String v = r.get(rule.field);
                    if (v != null && ((rule.min != null && v.length() < rule.min) || (rule.max != null && v.length() > rule.max)))
                        report(sink, r, rule, rule.field, msg(rule, label(r.type, rule.field) + " must be " + rule.min + "–" + rule.max + " characters."));
                });
                case "date" -> recs.forEach(r -> {
                    String v = r.get(rule.field);
                    if (v == null) return;
                    try { LocalDate.parse(v); }
                    catch (DateTimeParseException e) { report(sink, r, rule, rule.field, msg(rule, label(r.type, rule.field) + " '" + v + "' is not a yyyy-MM-dd date.")); }
                });
                case "unique" -> {
                    Map<String, List<StagedRecord>> seen = new HashMap<>();
                    for (StagedRecord r : recs) {
                        String v = r.get(rule.field);
                        if (v == null) continue;
                        String scope = rule.scope == null ? "" : String.valueOf(r.get(rule.scope));
                        seen.computeIfAbsent(scope + "\u001f" + v, k -> new ArrayList<>()).add(r);
                    }
                    seen.values().stream().filter(l -> l.size() > 1).forEach(l -> l.forEach(r ->
                            report(sink, r, rule, rule.field, msg(rule, label(r.type, rule.field) + " '" + r.get(rule.field)
                                    + "' appears on rows " + l.stream().map(x -> String.valueOf(x.rowNum)).collect(Collectors.joining(", ")) + "."))));
                }
                case "conditional-required" -> recs.forEach(r -> {
                    if (rule.when == null) return;
                    String field = rule.when.get("field"), equals = rule.when.get("equals");
                    if (equals != null && equals.equalsIgnoreCase(r.get(field)))
                        rule.requires.forEach(f -> { if (!r.has(f)) report(sink, r, rule, f, msg(rule, label(r.type, f) + " is required when " + field + " is " + equals + ".")); });
                });
                default -> throw new IllegalStateException("validation-rules.json " + rule.id + ": unknown type '" + rule.type + "'");
            }
        }
    }

    // ---------------- 2. checks across record types ----------------

    private void accountChecks(BatchView view, Consumer<TargetAdapter.FindingOnRecord> sink) {
        for (StagedRecord a : view.list("ACCOUNT")) {
            String c = a.get("contractRef");
            if (c != null && !view.contracts.containsKey(c))
                report(sink, a, "XR_ACCOUNT_CONTRACT", "ERROR", "contractRef", "Contract '" + c + "' is not in the Contracts data of this batch.", null);
        }
        // A-09: an account listed under more than one contract (joint account)
        String a09 = config.choice("A-09");
        Map<String, Set<String>> contractsPerAccount = new HashMap<>();
        for (StagedRecord a : view.list("ACCOUNT"))
            if (a.get("accountId") != null && a.get("contractRef") != null)
                contractsPerAccount.computeIfAbsent(a.get("accountId"), k -> new TreeSet<>()).add(a.get("contractRef"));
        for (StagedRecord a : view.list("ACCOUNT")) {
            Set<String> cs = contractsPerAccount.getOrDefault(a.get("accountId"), Set.of());
            if (cs.size() < 2) continue;
            if ("EXCLUDE".equals(a09)) {
                a.status = "EXCLUDED";
                report(sink, a, "XR_JOINT_ACCOUNT", "WARN", "accountId", "Account " + a.get("accountId") + " is listed under "
                        + cs.size() + " contracts " + cs + "; left out of all of them (A-09).", "A-09");
            } else {
                report(sink, a, "XR_JOINT_ACCOUNT", "WARN", "accountId", "Account " + a.get("accountId") + " is listed under "
                        + cs.size() + " contracts " + cs + " and is included in each (A-09).", "A-09");
            }
        }
    }

    private void contractChecks(BatchView view, Consumer<TargetAdapter.FindingOnRecord> sink) {
        Set<String> sds = config.get().referenceData().serviceDefinitions.stream().map(s -> s.ref).collect(Collectors.toSet());
        TargetEnvironment env = config.environment();
        for (StagedRecord c : view.list("CONTRACT")) {
            String ref = c.get("contractRef");
            String sd = c.get("serviceDefinitionRef");
            if (sd != null && !sds.contains(sd))
                report(sink, c, "XR_SERVICE_DEFINITION", "ERROR", "serviceDefinitionRef", "Service definition '" + sd
                        + "' is not in reference-data.json. Known: " + new TreeSet<>(sds) + ".", null);
            if (!c.has("legalEntityRef") && (env.defaultLegalEntityId == null || env.defaultLegalEntityId.isBlank()))
                report(sink, c, "XR_LEGAL_ENTITY", "ERROR", "legalEntityRef", "No legal entity on the row and no default for environment " + env.id + ".", null);
            if (ref == null || view.contracts.get(ref) != c) continue;   // duplicates already reported

            List<StagedRecord> usable = view.usableAccounts(ref);
            if (usable.isEmpty()) {
                report(sink, c, "XR_CONTRACT_NO_ACCOUNTS", "ERROR", null, view.accounts(ref).isEmpty()
                        ? "The contract has no accounts in this batch; the target needs at least one."
                        : "None of the contract's accounts can be used (all invalid or excluded); the target needs at least one.", null);
                continue;
            }
            String primary = c.get("primaryCif");
            if (primary != null && usable.stream().noneMatch(a -> primary.equals(a.get("cif"))))
                report(sink, c, "XR_PRIMARY_CIF_ACCOUNT", "ERROR", "primaryCif", "Primary CIF " + primary
                        + " has no usable account on this contract; every CIF sent to the target needs one.", null);
        }
    }

    private void userChecks(BatchView view, Consumer<TargetAdapter.FindingOnRecord> sink) {
        Set<String> roles = config.get().referenceData().roles.stream().map(r -> r.ref).collect(Collectors.toSet());
        String a01 = config.choice("A-01");
        for (StagedRecord u : view.list("USER")) {
            String c = u.get("contractRef");
            if (c != null && !view.contracts.containsKey(c))
                report(sink, u, "XR_USER_CONTRACT", "ERROR", "contractRef", "Contract '" + c + "' is not in the Contracts data of this batch.", null);
            String role = u.get("roleRef");
            if (role != null && !roles.contains(role))
                report(sink, u, "XR_ROLE", "ERROR", "roleRef", "Role '" + role + "' is not in reference-data.json. Known: " + new TreeSet<>(roles) + ".", null);

            // A-01: linked (own CIF) or virtual user
            String given = u.get("userType");
            String resolved;
            if ("ALWAYS_VIRTUAL".equals(a01)) {
                resolved = "VIRTUAL";
                if (!"VIRTUAL".equals(given)) u.tags.add("A-01");
            } else if (given != null) {
                resolved = given;
            } else {
                resolved = u.has("cif") ? "LINKED" : "VIRTUAL";
                u.tags.add("A-01");
            }
            u.set("userTypeResolved", resolved);
            if ("LINKED".equals(resolved) && !u.has("cif"))
                report(sink, u, "XR_LINKED_NEEDS_CIF", "ERROR", "cif", "A linked user needs their own CIF.", null);

            if (c != null && view.contracts.containsKey(c)) {
                Set<String> onContract = view.usableAccounts(c).stream().map(a -> a.get("accountId")).collect(Collectors.toSet());
                List<String> unknown = u.list("accountIds").stream().filter(a -> !onContract.contains(a)).toList();
                if (!unknown.isEmpty())
                    report(sink, u, "XR_USER_ACCOUNTS", "ERROR", "accountIds", "Accounts " + unknown
                            + " are not usable accounts of contract " + c + ".", null);
            }
        }
    }

    private void groupChecks(BatchView view, Consumer<TargetAdapter.FindingOnRecord> sink) {
        Map<String, String> contractTypeBySd = new HashMap<>();
        config.get().referenceData().serviceDefinitions.forEach(s -> contractTypeBySd.put(s.ref, s.contractType));
        Map<String, Set<String>> namesPerGroup = new HashMap<>(), contractsPerGroup = new HashMap<>();
        for (StagedRecord g : view.list("SIGNATORY_GROUP")) {
            String gr = String.valueOf(g.get("groupRef"));
            if (g.get("groupName") != null) namesPerGroup.computeIfAbsent(gr, k -> new TreeSet<>()).add(g.get("groupName"));
            if (g.get("contractRef") != null) contractsPerGroup.computeIfAbsent(gr, k -> new TreeSet<>()).add(g.get("contractRef"));
        }
        for (StagedRecord g : view.list("SIGNATORY_GROUP")) {
            String c = g.get("contractRef"), gr = String.valueOf(g.get("groupRef"));
            if (namesPerGroup.getOrDefault(gr, Set.of()).size() > 1 || contractsPerGroup.getOrDefault(gr, Set.of()).size() > 1)
                report(sink, g, "XR_GROUP_CONSISTENT", "ERROR", "groupRef", "Rows of group " + gr + " disagree on its name or contract: names "
                        + namesPerGroup.get(gr) + ", contracts " + contractsPerGroup.get(gr) + ".", null);
            if (c != null && !view.contracts.containsKey(c)) {
                report(sink, g, "XR_GROUP_CONTRACT", "ERROR", "contractRef", "Contract '" + c + "' is not in the Contracts data of this batch.", null);
                continue;
            }
            if (c != null) {
                String sd = view.contracts.get(c).get("serviceDefinitionRef");
                if ("RETAIL".equals(contractTypeBySd.get(sd)))
                    report(sink, g, "XR_GROUP_RETAIL", "ERROR", "contractRef", "Contract " + c + " uses a retail service definition; signatory groups are for business contracts.", null);
            }
            String member = g.get("userRef");
            StagedRecord u = member == null ? null : view.usersByRef.get(member);
            if (member != null && (u == null || !Objects.equals(u.get("contractRef"), c)))
                report(sink, g, "XR_GROUP_MEMBER", "ERROR", "userRef", "User " + member + " is not a user of contract " + c + " in this batch.", null);
        }
    }

    // ---------------- helpers ----------------

    private void report(Consumer<TargetAdapter.FindingOnRecord> sink, StagedRecord r, Rule rule, String field, String message) {
        report(sink, r, rule.id, rule.severity, field, message, null);
    }

    static void report(Consumer<TargetAdapter.FindingOnRecord> sink, StagedRecord r, String ruleId, String severity,
                       String field, String message, String assumptionId) {
        sink.accept(new TargetAdapter.FindingOnRecord(r,
                new Finding(r.type, r.rowNum, r.key, ruleId, severity, field, message, assumptionId)));
    }

    private String label(String type, String field) {
        return model.field(type, field).map(f -> f.label).orElse(field);
    }

    private static String msg(Rule rule, String fallback) { return rule.message == null ? fallback : rule.message; }
}
