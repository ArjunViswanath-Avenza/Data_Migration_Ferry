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
        return steps;
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
