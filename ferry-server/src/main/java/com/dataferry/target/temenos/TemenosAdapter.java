package com.dataferry.target.temenos;

import com.dataferry.boarding.BatchStore.Finding;
import com.dataferry.boarding.BatchView;
import com.dataferry.boarding.StagedRecord;
import com.dataferry.config.ConfigModel.*;
import com.dataferry.config.ConfigStore;
import com.dataferry.core.Json;
import com.dataferry.register.IdRegister;
import com.dataferry.target.TargetAdapter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Temenos Digital through the DataMigration Fabric app. Payload shapes follow the vendor guide and
 * Postman collection: several fields are JSON sent as a *string* (cifList, accounts, signatories,
 * communicationDetails), and IDs created by earlier steps appear as {{TYPE:ref.field}} placeholders
 * until Crossing fills them in from the ID register.
 */
@Component
public class TemenosAdapter implements TargetAdapter {

    private final ConfigStore config;
    private final Json json;
    private final IdRegister register;

    public TemenosAdapter(ConfigStore config, Json json, IdRegister register) {
        this.config = config;
        this.json = json;
        this.register = register;
    }

    @Override
    public String id() { return "temenos-datamigration"; }

    // ---------------- vendor checks ----------------

    @Override
    public void check(BatchView view, Consumer<FindingOnRecord> sink) {
        TemenosTarget t = config.get().temenos();

        // A-08: CreateVirtualUser marks every personal field mandatory
        boolean block = "BLOCK".equals(config.choice("A-08"));
        for (StagedRecord u : view.list("USER")) {
            if (!"VIRTUAL".equals(u.get("userTypeResolved"))) continue;
            List<String> missing = t.virtualUserMandatory.stream().filter(f -> !u.has(f)).toList();
            if (missing.isEmpty()) continue;
            add(sink, u, "TM_VIRTUAL_USER_FIELDS", block ? "ERROR" : "WARN", String.join(",", missing),
                    "Virtual user is missing " + missing + ", which the vendor marks mandatory for CreateVirtualUser"
                            + (block ? "; blocked (A-08)." : "; they will be sent blank (A-08)."), "A-08");
        }

        Pattern name = t.signatoryGroupNamePattern == null ? null : Pattern.compile(t.signatoryGroupNamePattern);
        for (Map.Entry<String, LinkedHashMap<String, List<StagedRecord>>> byContract : view.groupsByContract.entrySet()) {
            Map<String, List<String>> groupsByName = new HashMap<>();
            Map<String, List<String>> groupsByUser = new HashMap<>();
            for (Map.Entry<String, List<StagedRecord>> g : byContract.getValue().entrySet()) {
                StagedRecord first = g.getValue().get(0);
                String gname = first.get("groupName");
                if (gname != null) groupsByName.computeIfAbsent(gname.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(g.getKey());
                for (StagedRecord m : g.getValue()) {
                    if (name != null && gname != null && !name.matcher(gname).matches())
                        add(sink, m, "TM_GROUP_NAME_CHARS", "ERROR", "groupName", "Group name '" + gname
                                + "' has characters the vendor refuses (ERR_21023).", null);
                    if (m.get("userRef") != null) groupsByUser.computeIfAbsent(m.get("userRef"), k -> new ArrayList<>()).add(g.getKey());
                }
            }
            groupsByName.forEach((n, groups) -> {
                if (groups.size() > 1)
                    byContract.getValue().entrySet().stream().filter(e -> groups.contains(e.getKey())).forEach(e -> e.getValue().forEach(m ->
                            add(sink, m, "TM_GROUP_NAME_DUPLICATE", "ERROR", "groupName", "Groups " + groups
                                    + " share the name '" + m.get("groupName") + "' on one contract (ERR_21020).", null)));
            });
            groupsByUser.forEach((user, groups) -> {
                Set<String> distinct = new TreeSet<>(groups);
                if (distinct.size() > 1)
                    byContract.getValue().entrySet().stream().filter(e -> distinct.contains(e.getKey())).forEach(e -> e.getValue().stream()
                            .filter(m -> user.equals(m.get("userRef"))).forEach(m ->
                                    add(sink, m, "TM_ONE_GROUP_PER_USER", "ERROR", "userRef", "User " + user + " is in groups "
                                            + distinct + "; a user can be in only one group on a contract (ERR_21021).", null)));
            });
        }
        checkApprovals(view, sink);
    }

    private static final Map<String, String> PROBLEM_ASSUMPTION = Map.of("GROUP_COUNT_UNSUPPORTED", "A-02", "NON_MONETARY_PARKED", "A-06",
            "STAR_NEEDS_ACTION_LIST", "A-05", "NO_ACCOUNTS", "A-15");

    /** Approval rules that cannot be converted faithfully are held back and reported on the source rule rows. */
    private void checkApprovals(BatchView view, Consumer<FindingOnRecord> sink) {
        for (StagedRecord contract : view.contracts.values()) {
            for (ApprovalConverter.CustomerPlan cp : approvals(view, contract)) {
                for (ApprovalConverter.Problem pr : cp.problems()) {
                    List<StagedRecord> rows = view.list("SRC_RULE").stream()
                            .filter(r -> contract.get("contractRef").equals(r.get("contractRef")) && pr.rules().contains(r.get("ruleRef"))).toList();
                    if (rows.isEmpty()) rows = List.of(contract);
                    for (StagedRecord r : rows)
                        add(sink, r, "AP_" + pr.code(), "WARN", "ruleRef", pr.message() + " Held back; the bank decides.", PROBLEM_ASSUMPTION.get(pr.code()));
                }
            }
        }
    }

    private static void add(Consumer<FindingOnRecord> sink, StagedRecord r, String rule, String severity, String field,
                            String message, String assumption) {
        sink.accept(new FindingOnRecord(r, new Finding(r.type, r.rowNum, r.key, rule, severity, field, message, assumption)));
    }

    // ---------------- planning ----------------

    @Override
    public List<PlannedStep> plan(BatchView view, StagedRecord contract) {
        ConfigStore.Snapshot s = config.get();
        String ref = contract.get("contractRef");
        String primary = contract.get("primaryCif");
        String legalEntity = Objects.requireNonNullElse(contract.get("legalEntityRef"), s.environment().defaultLegalEntityId);
        List<PlannedStep> steps = new ArrayList<>();
        List<StagedRecord> usable = view.usableAccounts(ref);

        // 1. CreateContract
        String contractStep = "contract";
        boolean contractOk = "VALID".equals(contract.status);
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("legalEntityId", legalEntity);
        cp.put("serviceDefinitionId", serviceDefinitionId(contract.get("serviceDefinitionRef")));
        cp.put("cifList", json.compact(cifList(primary, usable)));
        cp.put("primaryCif", primary);
        Map<String, Object> comm = communication(contract);
        if (comm != null) cp.put("communicationDetails", json.compact(comm));
        List<String> contractAssumptions = new ArrayList<>();
        if (view.accounts(ref).stream().anyMatch(a -> a.tags.contains("A-09"))) contractAssumptions.add("A-09");
        steps.add(step(contractStep, "CreateContract", "Create contract", "CONTRACT", ref, List.of(),
                contractOk ? List.of() : List.of("The contract record has errors; see its findings."), contractAssumptions, cp));

        // 2. Users
        Map<String, String> userSteps = new HashMap<>();
        for (StagedRecord u : view.users(ref)) {
            String uref = u.get("userRef");
            boolean virtual = "VIRTUAL".equals(u.get("userTypeResolved"));
            List<String> accounts = u.list("accountIds").isEmpty()
                    ? usable.stream().map(a -> a.get("accountId")).toList() : u.list("accountIds");
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("legalEntityId", legalEntity);
            p.put("contractId", placeholder("CONTRACT", ref, "contractId"));
            p.put("contractCif", primary);
            p.put("accounts", json.compact(accounts.stream().map(a -> Map.of("accountId", a)).toList()));
            p.put("roleId", roleId(u.get("roleRef")));
            if (virtual) {
                p.put("email", blank(u.get("email")));
                p.put("phone", blank(u.get("phone")));
                p.put("phoneCountryCode", blank(u.get("phoneCountryCode")));
                p.put("firstName", blank(u.get("firstName")));
                p.put("lastName", blank(u.get("lastName")));
                p.put("dob", blank(u.get("dateOfBirth")));
                p.put("taxId", blank(u.get("taxId")));
                p.put("middleName", blank(u.get("middleName")));
            } else {
                p.put("cif", u.get("cif"));
            }
            if (u.has("legacyUserName")) p.put("legacyUserName", u.get("legacyUserName"));

            List<String> reasons = new ArrayList<>();
            if (!contractOk) reasons.add("The contract cannot be created.");
            if (!"VALID".equals(u.status)) reasons.add("The user record has errors; see its findings.");
            String id = "user:" + uref;
            userSteps.put(uref, id);
            steps.add(step(id, virtual ? "CreateVirtualUser" : "LinkUserToContract",
                    (virtual ? "Create virtual user " : "Link user ") + uref, "USER", uref, List.of(contractStep),
                    reasons, new ArrayList<>(u.tags), p));
        }

        // 3. Signatory groups (business contracts)
        for (Map.Entry<String, List<StagedRecord>> g : view.groups(ref).entrySet()) {
            StagedRecord first = g.getValue().get(0);
            List<String> members = g.getValue().stream().map(m -> m.get("userRef")).filter(Objects::nonNull).distinct().toList();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("signatoryGroupName", first.get("groupName"));
            p.put("signatoryGroupDescription", Objects.requireNonNullElse(first.get("description"), Objects.requireNonNullElse(first.get("groupName"), "")));
            p.put("cif", primary);
            p.put("contractId", placeholder("CONTRACT", ref, "contractId"));
            p.put("signatories", json.compact(members.stream().map(m -> Map.of("userId", placeholder("USER", m, "userId"))).toList()));

            List<String> reasons = new ArrayList<>();
            if (!contractOk) reasons.add("The contract cannot be created.");
            if (g.getValue().stream().anyMatch(m -> !"VALID".equals(m.status))) reasons.add("Rows of this group have errors; see their findings.");
            List<String> deps = new ArrayList<>(List.of(contractStep));
            for (String m : members) {
                String us = userSteps.get(m);
                if (us != null) deps.add(us);
                StagedRecord u = view.usersByRef.get(m);
                if (u == null || !"VALID".equals(u.status)) reasons.add("Member " + m + " cannot be created.");
            }
            steps.add(step("group:" + g.getKey(), "CreateSignatoryGroup", "Create signatory group " + first.get("groupName"),
                    "SIGNATORY_GROUP", g.getKey(), deps, reasons, List.of(), p));
        }
        planApprovals(view, contract, contractOk, userSteps, steps);
        return steps;
    }

    // ---------------- approval rules (docs/APPROVAL_RULES.md) ----------------

    private final Map<BatchView, Map<String, List<ApprovalConverter.CustomerPlan>>> approvalCache = Collections.synchronizedMap(new WeakHashMap<>());

    /** The approval design for one contract, worked out once per loaded batch. */
    List<ApprovalConverter.CustomerPlan> approvals(BatchView view, StagedRecord contract) {
        Map<String, List<ApprovalConverter.CustomerPlan>> byContract = approvalCache.computeIfAbsent(view, v -> new HashMap<>());
        return byContract.computeIfAbsent(contract.get("contractRef"), ref -> converter().convert(view, contract));
    }

    private ApprovalConverter converter() {
        ConfigStore.Snapshot s = config.get();
        Map<String, String> txn = new HashMap<>(s.referenceData().codeMaps.getOrDefault("txnTypeToAction", Map.of()));
        txn.keySet().removeIf(k -> k.startsWith("_"));
        return new ApprovalConverter(new ApprovalConverter.Choices(config.choice("A-02"), config.choice("A-03"), config.choice("A-04"),
                config.choice("A-06"), config.choice("A-15"), s.referenceData().approvalSettings, s.referenceData().approvalActions, txn));
    }

    private void planApprovals(BatchView view, StagedRecord contract, boolean contractOk, Map<String, String> userSteps, List<PlannedStep> steps) {
        String ref = contract.get("contractRef");
        Set<String> made = new HashSet<>();
        for (ApprovalConverter.CustomerPlan cp : approvals(view, contract)) {
            Map<String, String> groupSteps = new HashMap<>();
            for (List<StagedRecord> g : view.groups(ref).values()) groupSteps.put(g.get(0).get("groupName"), "group:" + g.get(0).get("groupRef"));
            for (ApprovalConverter.Group g : cp.groups()) {
                String id = "group:~" + g.name();
                groupSteps.put(g.name(), id);
                if (!made.add(id)) continue;
                List<String> reasons = new ArrayList<>(), deps = new ArrayList<>(List.of("contract"));
                if (!contractOk) reasons.add("The contract cannot be created.");
                for (String u : g.members()) {
                    if (userSteps.containsKey(u)) deps.add(userSteps.get(u));
                    StagedRecord ur = view.usersByRef.get(u);
                    if (ur == null || !"VALID".equals(ur.status)) reasons.add("Member " + u + " cannot be created.");
                }
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("signatoryGroupName", g.name());
                p.put("signatoryGroupDescription", "Approvers for migrated approval rules");
                p.put("cif", cp.cif());
                p.put("contractId", placeholder("CONTRACT", ref, "contractId"));
                p.put("signatories", json.compact(g.members().stream().map(m -> Map.of("userId", placeholder("USER", m, "userId"))).toList()));
                steps.add(step(id, "CreateSignatoryGroup", "Create signatory group " + g.name() + " (designed for approval rules)",
                        "SIGNATORY_GROUP", ref + "~" + g.name(), deps, reasons, List.of("A-02"), p));
            }
            String modeStep = null;
            if (cp.mode().equals("GROUP")) {
                modeStep = "mode:" + cp.cif();
                List<String> deps = new ArrayList<>(List.of("contract"));
                cp.groups().forEach(g -> deps.add(groupSteps.get(g.name())));
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("cif", cp.cif());
                p.put("contractId", placeholder("CONTRACT", ref, "contractId"));
                p.put("isGroupLevel", "1");
                steps.add(step(modeStep, "UpdateApprovalMatrixMode", "Set signatory-group mode for " + cp.cif(), "APPROVAL_MODE", ref + "~" + cp.cif(),
                        deps, contractOk ? List.of() : List.of("The contract cannot be created."), List.of("A-14"), p));
            }
            for (ApprovalConverter.RuleCall rc : cp.rules()) {
                List<String> deps = new ArrayList<>(List.of("contract")), reasons = new ArrayList<>();
                if (!contractOk) reasons.add("The contract cannot be created.");
                if (modeStep != null) deps.add(modeStep);
                for (String u : rc.users()) {
                    if (userSteps.containsKey(u)) deps.add(userSteps.get(u));
                    StagedRecord ur = view.usersByRef.get(u);
                    if (ur == null || !"VALID".equals(ur.status)) reasons.add("Approver " + u + " cannot be created.");
                }
                for (String gname : rc.groups()) if (groupSteps.containsKey(gname)) deps.add(groupSteps.get(gname));
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("contractId", placeholder("CONTRACT", ref, "contractId"));
                p.put("cif", rc.cif());
                if (rc.accountId() != null) p.put("accountId", rc.accountId());
                p.put("isGroupMatrix", rc.group() ? "1" : "0");
                p.put("actionId", rc.actionId());
                p.put("limitTypeId", rc.limitTypeId());
                p.put("limits", rc.limits());
                List<String> as = new ArrayList<>(List.of("A-03", "A-15"));
                if (rc.group()) as.add("A-02");
                if (rc.limits().stream().anyMatch(l -> "NO_APPROVAL".equals(l.get("approvalruleId")))) as.add("A-04");
                if (rc.limitTypeId().equals("NON_MONETARY_LIMIT")) as.add("A-06");
                steps.add(step("rule:" + rc.key(), "CreateApprovalMatrixRule", "Approval rule " + rc.actionId() + (rc.accountId() == null ? "" : " on " + rc.accountId())
                        + " (" + rc.limits().size() + " range" + (rc.limits().size() == 1 ? "" : "s") + ")", "APPROVAL_RULE", ref + "~" + rc.key(), deps, reasons, as, p));
            }
        }
    }

    @Override
    public Map<String, Object> summary(BatchView view, StagedRecord contract) {
        List<ApprovalConverter.CustomerPlan> plans = approvals(view, contract);
        if (plans.isEmpty()) return Map.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApprovalConverter.CustomerPlan cp : plans) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cif", cp.cif());
            m.put("mode", cp.mode());
            m.put("contexts", cp.contexts());
            m.put("userCompatible", cp.userCompatible());
            m.put("complexity", cp.complexity());
            m.put("rules", cp.rules().size());
            m.put("groups", cp.groups().stream().map(g -> Map.of("name", g.name(), "members", g.members())).toList());
            m.put("problems", cp.problems().stream().map(pr -> Map.of("code", pr.code(), "message", pr.message(), "rules", pr.rules())).toList());
            m.put("notes", cp.notes());
            out.add(m);
        }
        return Map.of("approvals", out);
    }

    /** ALREADY_DONE when this environment's ID register already holds the item; BLOCKED when there are reasons. */
    private PlannedStep step(String id, String op, String label, String subjectType, String subjectRef, List<String> deps,
                             List<String> reasons, List<String> assumptions, Map<String, Object> payload) {
        Optional<String> done = subjectRef == null ? Optional.empty() : register.find(subjectType, subjectRef);
        String status = done.isPresent() ? "ALREADY_DONE" : reasons.isEmpty() ? "PLANNED" : "BLOCKED";
        return new PlannedStep(id, op, label, subjectType, subjectRef, deps, status, reasons,
                assumptions.stream().sorted().toList(), payload, done.orElse(null));
    }

    /** [{cif, accounts:[{accountId}]}], primary CIF first, then CIFs in source order. */
    private List<Map<String, Object>> cifList(String primary, List<StagedRecord> accounts) {
        LinkedHashMap<String, List<Map<String, String>>> byCif = new LinkedHashMap<>();
        if (primary != null) byCif.put(primary, new ArrayList<>());
        for (StagedRecord a : accounts)
            byCif.computeIfAbsent(a.get("cif"), k -> new ArrayList<>()).add(Map.of("accountId", a.get("accountId")));
        List<Map<String, Object>> out = new ArrayList<>();
        byCif.forEach((cif, accs) -> { if (!accs.isEmpty()) out.add(new LinkedHashMap<>(Map.of("cif", cif, "accounts", accs))); });
        out.forEach(m -> { Object accs = m.remove("accounts"); m.put("accounts", accs); });   // keep "cif" first, as in the vendor samples
        return out;
    }

    /** Vendor shape; null when the source gives no communication details (the target then uses the primary CIF's). */
    private Map<String, Object> communication(StagedRecord c) {
        boolean any = List.of("email", "phoneNumber", "phoneCountryCode", "addressLine1", "addressLine2", "city", "state",
                "country", "zipCode", "faxId").stream().anyMatch(c::has);
        if (!any) return null;
        Map<String, Object> commEntry = new LinkedHashMap<>();
        commEntry.put("phoneNumber", blank(c.get("phoneNumber")));
        commEntry.put("phoneCountryCode", blank(c.get("phoneCountryCode")));
        commEntry.put("email", blank(c.get("email")));
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("country", blank(c.get("country")));
        address.put("cityName", blank(c.get("city")));
        address.put("state", blank(c.get("state")));
        address.put("zipCode", blank(c.get("zipCode")));
        address.put("addressLine1", blank(c.get("addressLine1")));
        address.put("addressLine2", blank(c.get("addressLine2")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("communication", List.of(commEntry));
        out.put("address", List.of(address));
        out.put("faxId", blank(c.get("faxId")));
        return out;
    }

    private String serviceDefinitionId(String ref) {
        return config.get().referenceData().serviceDefinitions.stream().filter(s -> s.ref.equals(ref)).map(s -> s.targetId)
                .findFirst().orElse("{{UNKNOWN serviceDefinitionRef " + ref + "}}");
    }

    private String roleId(String ref) {
        return config.get().referenceData().roles.stream().filter(r -> r.ref.equals(ref)).map(r -> r.targetId)
                .findFirst().orElse("{{UNKNOWN roleRef " + ref + "}}");
    }

    /** A target ID that only exists once an earlier step has run. */
    public static String placeholder(String type, String ref, String field) { return "{{" + type + ":" + ref + "." + field + "}}"; }

    private static String blank(String v) { return v == null ? "" : v; }
}
