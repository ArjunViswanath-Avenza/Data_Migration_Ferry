package com.dataferry.target.temenos;

import com.dataferry.boarding.BatchView;
import com.dataferry.boarding.StagedRecord;
import com.dataferry.config.ConfigModel.ApprovalSettings;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a bank's own approval rules (SRC_RULE, SRC_RULE_CHECKER, SRC_GROUP_MEMBER) into what the
 * target needs per contract and customer: an approval mode, signatory groups, and one rule per
 * account, action and limit type, each with amount ranges. docs/APPROVAL_RULES.md, Route B.
 *
 * <ol>
 *   <li><b>C1</b> each source rule becomes "N approvals from a set of users or a group".</li>
 *   <li><b>C2</b> rules are grouped into contexts (customer, account, transaction type, currency) and
 *       cut into ranges: above the lower value, up to and including the upper one, no gaps, open at
 *       both ends as the product stores them (-1). Ranges no rule covers become explicit "no approval".</li>
 *   <li><b>C3</b> rules active in the same range are alternatives (OR); single-user alternatives merge.</li>
 *   <li><b>C4</b> the 80/20 rule picks user or group mode per customer; group mode designs groups so no
 *       user is in two. "ANY 2 of {A,B,C}" stays one group of three needing 2, never A AND B AND C.</li>
 *   <li><b>C5</b> what cannot be converted faithfully becomes an exception for the bank, never a guess.</li>
 * </ol>
 * Pure: no I/O. Assumption choices come in as arguments.
 */
public final class ApprovalConverter {

    // ------------------------------------------------------------------ shapes

    /** n approvals from a set of users; all = every member. sourceGroup is set when the set came from a source group. */
    public record Term(int n, boolean all, List<String> users, String sourceGroup) {
        String key() { return (all ? "ALL" : n) + "of" + users; }
    }

    /** Alternatives (OR) of AND-paths; empty = no approval needed. */
    public record Logic(List<List<Term>> paths) {
        boolean none() { return paths.isEmpty(); }
    }

    public record Range(BigDecimal lower, BigDecimal upper, Logic logic, Set<String> rules) {}     // null bound = open (-1)

    public record Context(String cif, String accountId, String txnType, boolean nonMonetary, List<Range> ranges, Set<String> rules) {}

    public record Problem(String code, String contractRef, String cif, Set<String> rules, String message) {}

    public record Group(String name, List<String> members, String existingRef) {}

    public record RuleCall(String key, String cif, String accountId, String actionId, String limitTypeId, boolean group,
                           List<Map<String, Object>> limits, Set<String> users, Set<String> groups, Set<String> rules) {}

    public record CustomerPlan(String contractRef, String cif, String mode, int contexts, int userCompatible, String complexity,
                               List<Group> groups, List<RuleCall> rules, List<Problem> problems, List<String> notes) {}

    public record Choices(String groupRule /*A-02*/, String brackets /*A-03*/, String gaps /*A-04*/, String nonMonetary /*A-06*/,
                          String accountScope /*A-15*/, ApprovalSettings settings,
                          Map<String, List<String>> approvalActions, Map<String, String> txnTypeToAction) {}

    private final Choices c;
    private final BigDecimal openFrom;

    public ApprovalConverter(Choices choices) {
        this.c = choices;
        this.openFrom = new BigDecimal(choices.settings().openUpperFrom);
    }

    // ------------------------------------------------------------------ per contract

    public List<CustomerPlan> convert(BatchView view, StagedRecord contract) {
        String ref = contract.get("contractRef");
        String primary = contract.get("primaryCif");
        List<StagedRecord> rules = view.list("SRC_RULE").stream()
                .filter(r -> ref.equals(r.get("contractRef")) && "VALID".equals(r.status)).toList();
        if (rules.isEmpty()) return List.of();
        Map<String, List<StagedRecord>> checkers = view.list("SRC_RULE_CHECKER").stream()
                .filter(r -> ref.equals(r.get("contractRef")) && "VALID".equals(r.status))
                .collect(Collectors.groupingBy(r -> r.get("ruleRef"), LinkedHashMap::new, Collectors.toList()));
        Map<String, List<String>> groupMembers = new LinkedHashMap<>();
        for (StagedRecord m : view.list("SRC_GROUP_MEMBER"))
            if (ref.equals(m.get("contractRef")) && "VALID".equals(m.status) && !"MAKER".equals(m.get("groupKind")))
                groupMembers.computeIfAbsent(m.get("groupRef"), k -> new ArrayList<>()).add(m.get("userRef"));
        Set<String> contractUsers = view.users(ref).stream().map(u -> u.get("userRef")).collect(Collectors.toSet());

        List<Problem> problems = new ArrayList<>();
        // C1: one logic per rule
        Map<String, Logic> logic = new LinkedHashMap<>();
        Map<String, StagedRecord> byRef = new LinkedHashMap<>();
        for (StagedRecord r : rules) {
            String rr = r.get("ruleRef");
            String cif = Objects.requireNonNullElse(r.get("cif"), primary);
            byRef.put(rr, r);
            Logic l = c1(ref, cif, r, checkers.getOrDefault(rr, List.of()), groupMembers, contractUsers, problems);
            if (l != null) logic.put(rr, l);
        }

        // C2/C3: contexts per customer
        Map<String, List<StagedRecord>> byContext = new LinkedHashMap<>();
        for (StagedRecord r : rules) {
            if (!logic.containsKey(r.get("ruleRef"))) continue;
            String cif = Objects.requireNonNullElse(r.get("cif"), primary);
            byContext.computeIfAbsent(cif + "|" + nz(r.get("accountId")) + "|" + r.get("txnType") + "|" + nz(r.get("currency")), k -> new ArrayList<>()).add(r);
        }
        // currencies: the target has no currency on a rule; two currencies with different rules cannot be kept apart
        Map<String, Set<String>> currencies = new HashMap<>();
        byContext.keySet().forEach(k -> { String[] p = k.split("\\|", -1); currencies.computeIfAbsent(p[0] + "|" + p[1] + "|" + p[2], x -> new TreeSet<>()).add(p[3]); });

        Map<String, List<Context>> contextsByCif = new LinkedHashMap<>();
        for (Map.Entry<String, List<StagedRecord>> e : byContext.entrySet()) {
            String[] p = e.getKey().split("\\|", -1);
            Set<String> ruleRefs = e.getValue().stream().map(r -> r.get("ruleRef")).collect(Collectors.toCollection(TreeSet::new));
            if (currencies.get(p[0] + "|" + p[1] + "|" + p[2]).size() > 1) {
                problems.add(new Problem("CURRENCY_REVIEW", ref, p[0], ruleRefs, "Rules for " + p[2] + " differ by currency " + currencies.get(p[0] + "|" + p[1] + "|" + p[2])
                        + "; target rules have no currency, so the bank must say which applies."));
                continue;
            }
            Context ctx = c2(ref, p[0], p[1].isEmpty() ? null : p[1], p[2], e.getValue(), logic, problems);
            if (ctx != null) contextsByCif.computeIfAbsent(p[0], k -> new ArrayList<>()).add(ctx);
        }

        // C4/C5 per customer; group design is per contract (a user can be in one group per contract)
        List<CustomerPlan> out = new ArrayList<>();
        Map<Set<String>, Group> contractGroups = new LinkedHashMap<>();
        Map<String, Set<String>> userToSet = new HashMap<>();
        for (List<StagedRecord> members : view.groups(ref).values()) {      // groups the source already supplies (Route A)
            List<String> users = members.stream().map(m -> m.get("userRef")).filter(Objects::nonNull).distinct().toList();
            Set<String> set = new TreeSet<>(users);
            contractGroups.put(set, new Group(members.get(0).get("groupName"), users, members.get(0).get("groupRef")));
            users.forEach(u -> userToSet.put(u, set));
        }
        for (Map.Entry<String, List<Context>> e : contextsByCif.entrySet()) {
            String cif = e.getKey();
            List<Context> ctxs = e.getValue();
            List<Problem> mine = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            int compatible = (int) ctxs.stream().filter(this::userCompatible).count();
            String mode = (double) compatible / ctxs.size() >= c.settings().userModeThreshold ? "USER" : "GROUP";
            List<Context> usable = new ArrayList<>();
            for (Context ctx : ctxs) {
                if (mode.equals("USER") && !userCompatible(ctx)) {
                    mine.add(new Problem("USER_MODE_EXCEPTION", ref, cif, ctx.rules(), "Customer stays in user mode (" + compatible + " of " + ctxs.size()
                            + " contexts fit), but " + ctx.txnType() + " needs approvals user mode cannot express (more than 3, or AND of groups)."));
                    continue;
                }
                usable.add(ctx);
            }
            List<Group> groups = new ArrayList<>();
            if (mode.equals("GROUP")) {
                List<Context> kept = new ArrayList<>();
                for (Context ctx : usable) {
                    String conflict = designGroups(ctx, contractGroups, userToSet);
                    if (conflict != null) mine.add(new Problem("ONE_GROUP_CONFLICT", ref, cif, ctx.rules(), conflict));
                    else if (c.groupRule().equals("FLAGS") && ctx.ranges().stream().anyMatch(r -> r.logic().paths().stream().flatMap(List::stream).anyMatch(t -> t.n() > 1 || (t.all() && t.users().size() > 1))))
                        mine.add(new Problem("GROUP_COUNT_UNSUPPORTED", ref, cif, ctx.rules(), "Needs more than one approval from one group, and A-02 is FLAGS."));
                    else if (reusedInPath(ctx, contractGroups))
                        mine.add(new Problem("GROUP_REUSED_IN_PATH", ref, cif, ctx.rules(), "The same group would appear twice in one AND condition."));
                    else kept.add(ctx);
                }
                usable = kept;
                Set<String> used = new LinkedHashSet<>();
                for (Context ctx : usable) for (Range r : ctx.ranges()) for (List<Term> p : r.logic().paths()) for (Term t : p) used.add(contractGroups.get(new TreeSet<>(t.users())).name());
                for (Group g : contractGroups.values()) if (used.contains(g.name()) && g.existingRef() == null && !groups.contains(g)) groups.add(g);
                if (!groups.isEmpty()) notes.add(groups.size() + " signatory group(s) designed so no user is in two groups.");
            }
            List<RuleCall> calls = ruleCalls(view, contract, cif, mode, usable, contractGroups, mine);
            problems.removeIf(pr -> pr.cif().equals(cif) && mine.add(pr));
            String complexity = !mine.isEmpty() ? "HARD"
                    : usable.stream().anyMatch(x -> x.ranges().size() > 1 || x.rules().size() > 1) || mode.equals("GROUP") ? "MEDIUM" : "EASY";
            out.add(new CustomerPlan(ref, cif, mode, ctxs.size(), compatible, complexity, groups, calls, mine, notes));
        }
        // problems for customers whose every context failed earlier
        for (Problem pr : problems)
            if (out.stream().noneMatch(cp -> cp.cif().equals(pr.cif())))
                out.add(new CustomerPlan(ref, pr.cif(), "USER", 0, 0, "HARD", List.of(), List.of(), new ArrayList<>(List.of(pr)), List.of()));
            else out.stream().filter(cp -> cp.cif().equals(pr.cif())).findFirst().ifPresent(cp -> { if (!cp.problems().contains(pr)) cp.problems().add(pr); });
        return out;
    }

    // ------------------------------------------------------------------ C1

    private Logic c1(String ref, String cif, StagedRecord r, List<StagedRecord> slots, Map<String, List<String>> groupMembers,
                     Set<String> contractUsers, List<Problem> problems) {
        String rr = r.get("ruleRef");
        Set<String> one = Set.of(rr);
        int required = Integer.parseInt(r.get("requiredApprovals"));
        boolean self = "Y".equals(r.get("selfApprove"));
        if ("Y".equals(r.get("sequential"))) {
            problems.add(new Problem("SEQUENTIAL_PRODUCT_GAP", ref, cif, one, "Approvals in a fixed order have no target equivalent; the bank must accept them as unordered."));
            return null;
        }
        if (required == 0) {
            if (!self) { problems.add(new Problem("ZERO_AUTHORIZERS_ANOMALY", ref, cif, one, "Needs 0 approvals but is not marked self-approving.")); return null; }
            return new Logic(List.of());
        }
        if (slots.isEmpty()) { problems.add(new Problem("NO_CHECKERS", ref, cif, one, "Needs " + required + " approval(s) but lists no checkers.")); return null; }
        slots = slots.stream().sorted(Comparator.comparingInt(s -> Integer.parseInt(s.get("position")))).toList();
        List<StagedRecord> users = slots.stream().filter(s -> !"Y".equals(s.get("isGroup"))).toList();
        List<StagedRecord> groups = slots.stream().filter(s -> "Y".equals(s.get("isGroup"))).toList();
        for (StagedRecord u : users)
            if (!contractUsers.contains(u.get("checkerRef"))) { problems.add(new Problem("UNKNOWN_CHECKER", ref, cif, one, "Checker " + u.get("checkerRef") + " is not a user of this contract.")); return null; }
        if (groups.isEmpty()) {
            List<String> set = users.stream().map(s -> s.get("checkerRef")).distinct().toList();
            if (required > set.size()) { problems.add(new Problem("INSUFFICIENT_CHECKERS", ref, cif, one, "Needs " + required + " approvals from " + set.size() + " checker(s).")); return null; }
            return new Logic(List.of(List.of(new Term(required, required == set.size(), set, null))));
        }
        List<Term> terms = new ArrayList<>();
        for (StagedRecord g : groups) {
            List<String> members = groupMembers.get(g.get("checkerRef"));
            if (members == null || members.isEmpty()) { problems.add(new Problem("MISSING_GROUP", ref, cif, one, "Group " + g.get("checkerRef") + " has no members listed; members are never guessed.")); return null; }
            for (String m : members)
                if (!contractUsers.contains(m)) { problems.add(new Problem("UNKNOWN_CHECKER", ref, cif, one, "Group member " + m + " is not a user of this contract.")); return null; }
            terms.add(new Term(1, members.size() == 1, members.stream().distinct().toList(), g.get("checkerRef")));
        }
        if (users.isEmpty() && groups.size() == 1) {
            Term t = terms.get(0);
            if (required > t.users().size()) { problems.add(new Problem("INSUFFICIENT_GROUP_MEMBERS", ref, cif, one, "Needs " + required + " approvals from group " + t.sourceGroup() + ", which has " + t.users().size() + " member(s).")); return null; }
            return new Logic(List.of(List.of(new Term(required, required == t.users().size(), t.users(), t.sourceGroup()))));
        }
        if (users.isEmpty() && required == groups.size()) return new Logic(List.of(terms));      // one from each group (AND)
        problems.add(new Problem("CHECKER_SHAPE_REVIEW", ref, cif, one, "Mixes " + users.size() + " user and " + groups.size() + " group checker(s) with " + required
                + " approvals; the meaning is not certain enough to convert."));
        return null;
    }

    // ------------------------------------------------------------------ C2 / C3

    private Context c2(String ref, String cif, String account, String txn, List<StagedRecord> rules, Map<String, Logic> logic, List<Problem> problems) {
        Set<String> refs = rules.stream().map(r -> r.get("ruleRef")).collect(Collectors.toCollection(TreeSet::new));
        boolean allZero = rules.stream().allMatch(r -> zero(r.get("fromAmount")) && zero(r.get("toAmount")));
        boolean anyZero = rules.stream().anyMatch(r -> zero(r.get("fromAmount")) && zero(r.get("toAmount")));
        if (allZero) {
            if ("*".equals(txn)) { problems.add(new Problem("STAR_ZERO_REVIEW", ref, cif, refs, "An all-transactions rule with no amounts: it is not clear whether it means non-monetary actions only.")); return null; }
            return new Context(cif, account, txn, true, List.of(new Range(null, null, merge(rules.stream().map(r -> logic.get(r.get("ruleRef"))).toList()), refs)), refs);
        }
        if (anyZero) { problems.add(new Problem("MIXED_RANGE_REVIEW", ref, cif, refs, "Rules for " + txn + " mix amount ranges with no-amount rules.")); return null; }

        TreeSet<BigDecimal> points = new TreeSet<>();
        for (StagedRecord r : rules) {
            points.add(amount(r.get("fromAmount")));
            BigDecimal to = amount(r.get("toAmount"));
            if (to.compareTo(openFrom) < 0) points.add(to);
        }
        List<BigDecimal> pts = new ArrayList<>(points);
        List<Range> ranges = new ArrayList<>();
        if (pts.get(0).signum() > 0) ranges.add(new Range(null, pts.get(0), new Logic(List.of()), Set.of()));   // below the lowest rule: no approval
        for (int i = 0; i < pts.size(); i++) {
            BigDecimal lo = pts.get(i), hi = i + 1 < pts.size() ? pts.get(i + 1) : null;
            List<StagedRecord> active = new ArrayList<>();
            for (StagedRecord r : rules) {
                BigDecimal from = amount(r.get("fromAmount")), to = amount(r.get("toAmount"));
                boolean open = to.compareTo(openFrom) >= 0;
                if (from.compareTo(lo) <= 0 && (open || (hi != null && hi.compareTo(to) <= 0))) active.add(r);
            }
            Logic l = active.isEmpty() ? new Logic(List.of()) : merge(active.stream().map(r -> logic.get(r.get("ruleRef"))).toList());
            Set<String> ar = active.stream().map(r -> r.get("ruleRef")).collect(Collectors.toCollection(TreeSet::new));
            ranges.add(new Range(i == 0 && lo.signum() == 0 ? null : lo, hi, l, ar));
        }
        // neighbours with the same logic become one range
        List<Range> merged = new ArrayList<>();
        for (Range r : ranges) {
            if (!merged.isEmpty() && same(merged.get(merged.size() - 1).logic(), r.logic())) {
                Range prev = merged.remove(merged.size() - 1);
                Set<String> rs = new TreeSet<>(prev.rules()); rs.addAll(r.rules());
                merged.add(new Range(prev.lower(), r.upper(), prev.logic(), rs));
            } else merged.add(r);
        }
        return new Context(cif, account, txn, false, merged, refs);
    }

    /** Active rules are alternatives. "No approval" among them wins; single-user alternatives merge into ANY 1. */
    private Logic merge(List<Logic> alternatives) {
        if (alternatives.stream().anyMatch(Logic::none)) return new Logic(List.of());
        List<List<Term>> paths = new ArrayList<>();
        Set<String> singles = new TreeSet<>();
        for (Logic l : alternatives)
            for (List<Term> p : l.paths()) {
                if (p.size() == 1 && p.get(0).n() == 1 && p.get(0).users().size() == 1) singles.add(p.get(0).users().get(0));
                else if (paths.stream().noneMatch(q -> key(q).equals(key(p)))) paths.add(p);
            }
        if (!singles.isEmpty()) {
            List<String> u = new ArrayList<>(singles);
            paths.add(0, List.of(new Term(1, u.size() == 1, u, null)));
        }
        return new Logic(paths);
    }

    // ------------------------------------------------------------------ C4

    /** One path, one term, a number user mode can say: ANY 1, 2, 3 or ALL. */
    private boolean userCompatible(Context ctx) {
        for (Range r : ctx.ranges()) {
            if (r.logic().none()) continue;
            if (r.logic().paths().size() != 1 || r.logic().paths().get(0).size() != 1) return false;
            Term t = r.logic().paths().get(0).get(0);
            if (!t.all() && t.n() > 3) return false;
        }
        return true;
    }

    /** Every user set in the context becomes a group; a user already in a different group is a conflict. */
    private String designGroups(Context ctx, Map<Set<String>, Group> groups, Map<String, Set<String>> userToSet) {
        Map<Set<String>, Group> pending = new LinkedHashMap<>();
        Map<String, Set<String>> pendingUsers = new HashMap<>();
        for (Range r : ctx.ranges())
            for (List<Term> p : r.logic().paths())
                for (Term t : p) {
                    Set<String> set = new TreeSet<>(t.users());
                    if (groups.containsKey(set) || pending.containsKey(set)) continue;
                    for (String u : set) {
                        Set<String> other = userToSet.containsKey(u) ? userToSet.get(u) : pendingUsers.get(u);
                        if (other != null && !other.equals(set))
                            return "User " + u + " would need to be in groups " + other + " and " + set + "; a user can be in one group per contract, so the bank must design the groups.";
                    }
                    String name = t.sourceGroup() != null ? clean(t.sourceGroup()) : "Approvers " + (groups.size() + pending.size() + 1);
                    pending.put(set, new Group(uniqueName(name, groups, pending), new ArrayList<>(set), null));
                    set.forEach(u -> pendingUsers.put(u, set));
                }
        groups.putAll(pending);
        userToSet.putAll(pendingUsers);
        return null;
    }

    private boolean reusedInPath(Context ctx, Map<Set<String>, Group> groups) {
        for (Range r : ctx.ranges())
            for (List<Term> p : r.logic().paths()) {
                Set<String> seen = new HashSet<>();
                for (Term t : p) if (!seen.add(groups.get(new TreeSet<>(t.users())).name())) return true;
            }
        return false;
    }

    // ------------------------------------------------------------------ calls

    private List<RuleCall> ruleCalls(BatchView view, StagedRecord contract, String cif, String mode, List<Context> ctxs,
                                     Map<Set<String>, Group> groups, List<Problem> problems) {
        String ref = contract.get("contractRef");
        Map<String, RuleCall> byKey = new LinkedHashMap<>();
        Map<String, Integer> specificity = new HashMap<>();
        for (Context ctx : ctxs) {
            List<String> actions;
            if ("*".equals(ctx.txnType())) {
                actions = c.approvalActions().get(contract.get("serviceDefinitionRef"));
                if (actions == null || actions.isEmpty()) {
                    problems.add(new Problem("STAR_NEEDS_ACTION_LIST", ref, cif, ctx.rules(), "An all-transactions rule needs the approval actions of service definition "
                            + contract.get("serviceDefinitionRef") + " in reference-data.json (approvalActions)."));
                    continue;
                }
            } else {
                String a = c.txnTypeToAction().get(ctx.txnType());
                if (a == null) { problems.add(new Problem("UNKNOWN_ACTION", ref, cif, ctx.rules(), "Transaction type " + ctx.txnType() + " is not in codeMaps.txnTypeToAction.")); continue; }
                actions = List.of(a);
            }
            String limitType;
            if (ctx.nonMonetary()) {
                if (!"SEND_WITH_LIMIT_TYPE".equals(c.nonMonetary())) { problems.add(new Problem("NON_MONETARY_PARKED", ref, cif, ctx.rules(), "Non-monetary rule held back (A-06 is PARK).")); continue; }
                limitType = "NON_MONETARY_LIMIT";
            } else limitType = c.settings().defaultLimitType;
            List<String> accounts;
            if (ctx.accountId() != null) accounts = List.of(ctx.accountId());
            else if ("CONTRACT_WIDE".equals(c.accountScope())) accounts = Collections.singletonList(null);
            else {
                accounts = view.usableAccounts(ref).stream().filter(a -> cif.equals(a.get("cif"))).map(a -> a.get("accountId")).toList();
                if (accounts.isEmpty()) { problems.add(new Problem("NO_ACCOUNTS", ref, cif, ctx.rules(), "Rules are kept per account (A-15) but customer " + cif + " has no usable account on this contract.")); continue; }
            }
            int spec = (ctx.accountId() != null ? 2 : 0) + ("*".equals(ctx.txnType()) ? 0 : 1);
            List<Map<String, Object>> limits = limits(ctx, mode, groups, ref);
            Set<String> users = new TreeSet<>(), groupNames = new TreeSet<>();
            for (Range r : ctx.ranges()) for (List<Term> p : r.logic().paths()) for (Term t : p) {
                if (mode.equals("USER")) users.addAll(t.users()); else groupNames.add(groups.get(new TreeSet<>(t.users())).name());
            }
            for (String action : actions)
                for (String acc : accounts) {
                    String key = cif + ":" + (acc == null ? "*" : acc) + ":" + action + ":" + limitType;
                    Integer had = specificity.get(key);
                    if (had != null && had >= spec) continue;          // a more specific source rule already covers it
                    specificity.put(key, spec);
                    byKey.put(key, new RuleCall(key, cif, acc, action, limitType, mode.equals("GROUP"), limits, users, groupNames, ctx.rules()));
                }
        }
        return new ArrayList<>(byKey.values());
    }

    /** limits[] as the vendor sample shows; IDs as placeholders filled by Crossing. */
    private List<Map<String, Object>> limits(Context ctx, String mode, Map<Set<String>, Group> groups, String ref) {
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal prevUpper = null;
        for (Range r : ctx.ranges()) {
            if (r.logic().none() && "OMIT".equals(c.gaps())) { prevUpper = r.upper(); continue; }
            Map<String, Object> m = new LinkedHashMap<>();
            BigDecimal lower = r.lower();
            if (lower != null && "UPPER_PLUS_ONE".equals(c.brackets()) && prevUpper != null) lower = prevUpper.add(BigDecimal.ONE);
            m.put("lowerlimit", lower == null ? "-1.00" : lower.toPlainString());
            m.put("upperlimit", r.upper() == null ? "-1.00" : r.upper().toPlainString());
            if (r.logic().none()) {
                m.put("approvalruleId", "NO_APPROVAL");
                if (mode.equals("USER")) { m.put("approvers", List.of()); m.put("numberOfApprovals", 0); }
                else { m.put("groupList", "[]"); m.put("groupRule", "[]"); }          // group-mode format to be confirmed by sea trial S4
            } else if (mode.equals("USER")) {
                Term t = r.logic().paths().get(0).get(0);
                m.put("approvers", t.users().stream().map(u -> Map.of("approverId", "{{USER:" + u + ".userId}}")).toList());
                m.put("numberOfApprovals", t.all() ? -1 : t.n());
            } else {
                List<Group> used = new ArrayList<>();
                for (List<Term> p : r.logic().paths()) for (Term t : p) { Group g = groups.get(new TreeSet<>(t.users())); if (!used.contains(g)) used.add(g); }
                List<List<Integer>> rule = new ArrayList<>();
                for (List<Term> p : r.logic().paths()) {
                    List<Integer> row = new ArrayList<>(Collections.nCopies(used.size(), 0));
                    for (Term t : p) row.set(used.indexOf(groups.get(new TreeSet<>(t.users()))), t.all() ? t.users().size() : t.n());
                    rule.add(row);
                }
                m.put("groupList", "[" + used.stream().map(g -> g.existingRef() != null ? "{{SIGNATORY_GROUP:" + g.existingRef() + ".signatoryGroupId}}"
                        : "{{SIGNATORY_GROUP:" + ref + "~" + g.name() + ".signatoryGroupId}}").collect(Collectors.joining(",")) + "]");
                m.put("groupRule", rule.toString().replace(" ", ""));
            }
            out.add(m);
            prevUpper = r.upper();
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean same(Logic a, Logic b) {
        return a.paths().stream().map(ApprovalConverter::key).collect(Collectors.toSet())
                .equals(b.paths().stream().map(ApprovalConverter::key).collect(Collectors.toSet()));
    }

    private static String key(List<Term> path) { return path.stream().map(Term::key).sorted().collect(Collectors.joining("&")); }

    private static String uniqueName(String base, Map<Set<String>, Group> a, Map<Set<String>, Group> b) {
        Function<String, Boolean> taken = n -> a.values().stream().anyMatch(g -> g.name().equalsIgnoreCase(n)) || b.values().stream().anyMatch(g -> g.name().equalsIgnoreCase(n));
        String n = base;
        for (int i = 2; taken.apply(n); i++) n = base + " " + i;
        return n;
    }

    private static String clean(String s) {
        String c = s.replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return c.isEmpty() ? "Approvers" : c.length() > 90 ? c.substring(0, 90) : c;
    }

    private static boolean zero(String v) { return amount(v).signum() == 0; }
    private static BigDecimal amount(String v) { return new BigDecimal(v.trim()); }
    private static String nz(String s) { return s == null ? "" : s; }
}
