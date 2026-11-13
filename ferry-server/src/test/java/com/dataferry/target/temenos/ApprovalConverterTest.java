package com.dataferry.target.temenos;

import com.dataferry.boarding.BatchView;
import com.dataferry.boarding.StagedRecord;
import com.dataferry.config.ConfigModel.ApprovalSettings;
import com.dataferry.target.temenos.ApprovalConverter.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The approval converter on hand-made batches: one contract, users A to E, accounts 01 and 02. */
class ApprovalConverterTest {

    private final Map<String, List<StagedRecord>> rows = new LinkedHashMap<>();

    private StagedRecord add(String type, String... kv) {
        StagedRecord r = new StagedRecord(type, rows.getOrDefault(type, List.of()).size() + 2);
        for (int i = 0; i < kv.length; i += 2) r.set(kv[i], kv[i + 1]);
        r.status = "VALID";
        rows.computeIfAbsent(type, k -> new ArrayList<>()).add(r);
        return r;
    }

    private ApprovalConverterTest() {}

    private static ApprovalConverterTest contract() {
        ApprovalConverterTest t = new ApprovalConverterTest();
        t.add("CONTRACT", "contractRef", "K1", "primaryCif", "100", "serviceDefinitionRef", "BUSINESS");
        for (String a : List.of("01", "02")) t.add("ACCOUNT", "contractRef", "K1", "cif", "100", "accountId", a);
        for (String u : List.of("A", "B", "C", "D", "E")) t.add("USER", "contractRef", "K1", "userRef", u);
        return t;
    }

    private void rule(String ref, String txn, String from, String to, int required, String self, String... checkers) {
        add("SRC_RULE", "contractRef", "K1", "ruleRef", ref, "txnType", txn, "fromAmount", from, "toAmount", to,
                "requiredApprovals", String.valueOf(required), "selfApprove", self);
        for (int i = 0; i < checkers.length; i++) {
            boolean group = checkers[i].startsWith("G:");
            add("SRC_RULE_CHECKER", "contractRef", "K1", "ruleRef", ref, "position", String.valueOf(i + 1),
                    "checkerRef", group ? checkers[i].substring(2) : checkers[i], "isGroup", group ? "Y" : "N");
        }
    }

    private void member(String group, String... users) {
        for (String u : users) add("SRC_GROUP_MEMBER", "contractRef", "K1", "groupRef", group, "groupKind", "CHECKER", "userRef", u);
    }

    private List<CustomerPlan> run(String groupRule, double threshold, String brackets) {
        ApprovalSettings s = new ApprovalSettings();
        s.userModeThreshold = threshold;
        ApprovalConverter c = new ApprovalConverter(new Choices(groupRule, brackets, "NO_APPROVAL", "SEND_WITH_LIMIT_TYPE", "PER_ACCOUNT", s,
                Map.of("BUSINESS", List.of("ACT_1", "ACT_2")), Map.of("FT", "ACT_FT", "STMT", "ACT_STMT")));
        BatchView v = new BatchView("B", rows);
        return c.convert(v, v.contracts.get("K1"));
    }

    private List<CustomerPlan> run() { return run("COUNTS", 0.8, "SHARED_BOUNDARY"); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> limits(CustomerPlan cp, String key) {
        return cp.rules().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow().limits();
    }

    // ------------------------------------------------------------------

    @Test
    void theTemplateExampleStaysInUserModeWithAnExplicitNoApprovalRange() {
        ApprovalConverterTest t = contract();
        t.rule("9001", "*", "1000", "9999999999", 2, "N", "G:SENIOR");
        t.rule("9002", "*", "0", "1000", 0, "Y");
        t.member("SENIOR", "A", "B");
        CustomerPlan cp = t.run().get(0);
        assertEquals("USER", cp.mode());
        assertEquals(4, cp.rules().size(), "2 actions x 2 accounts (A-05, A-15)");
        List<Map<String, Object>> l = limits(cp, "100:01:ACT_1:MAX_TRANSACTION_LIMIT");
        assertEquals(2, l.size());
        assertEquals(Map.of("lowerlimit", "-1.00", "upperlimit", "1000", "approvalruleId", "NO_APPROVAL", "approvers", List.of(), "numberOfApprovals", 0), l.get(0));
        assertEquals("1000", l.get(1).get("lowerlimit"), "shared boundary (A-03)");
        assertEquals("-1.00", l.get(1).get("upperlimit"));
        assertEquals(-1, l.get(1).get("numberOfApprovals"), "2 of a group of 2 is ALL");
        assertTrue(cp.problems().isEmpty());
    }

    @Test
    void anyTwoOfThreeIsOneGroupNeedingTwoNeverThreeGroupsJoinedByAnd() {
        ApprovalConverterTest t = contract();
        t.rule("R1", "FT", "0", "9999999999", 2, "N", "A", "B", "C");
        CustomerPlan cp = t.run("COUNTS", 1.1, "SHARED_BOUNDARY").get(0);          // threshold above 100% forces group mode
        assertEquals("GROUP", cp.mode());
        assertEquals(1, cp.groups().size());
        assertEquals(List.of("A", "B", "C"), cp.groups().get(0).members());
        Map<String, Object> range = limits(cp, "100:01:ACT_FT:MAX_TRANSACTION_LIMIT").get(0);
        assertEquals("[[2]]", range.get("groupRule"), "regression: the earlier tool sent [[1,1,1]] (A AND B AND C)");
        assertEquals("[{{SIGNATORY_GROUP:K1~Approvers 1.signatoryGroupId}}]", range.get("groupList"));
    }

    @Test
    void withFlagsOnlyACountAboveOneIsHeldBackNotRewritten() {
        ApprovalConverterTest t = contract();
        t.rule("R1", "FT", "0", "9999999999", 2, "N", "A", "B", "C");
        CustomerPlan cp = t.run("FLAGS", 1.1, "SHARED_BOUNDARY").get(0);
        assertTrue(cp.rules().isEmpty());
        assertEquals("GROUP_COUNT_UNSUPPORTED", cp.problems().get(0).code());
    }

    @Test
    void uncoveredAmountsBecomeExplicitNoApprovalRanges() {
        ApprovalConverterTest t = contract();
        t.rule("R1", "FT", "1000", "5000", 1, "N", "A");
        List<Map<String, Object>> l = limits(t.run().get(0), "100:01:ACT_FT:MAX_TRANSACTION_LIMIT");
        assertEquals(3, l.size());
        assertEquals(List.of("-1.00", "1000", "5000"), l.stream().map(x -> x.get("lowerlimit")).toList());
        assertEquals(List.of("1000", "5000", "-1.00"), l.stream().map(x -> x.get("upperlimit")).toList());
        assertEquals("NO_APPROVAL", l.get(0).get("approvalruleId"));
        assertEquals("NO_APPROVAL", l.get(2).get("approvalruleId"));
    }

    @Test
    void overlappingRulesAreAlternativesAndSingleApproversMerge() {
        ApprovalConverterTest t = contract();
        t.rule("R1", "FT", "0", "5000", 1, "N", "A");
        t.rule("R2", "FT", "0", "9999999999", 1, "N", "B");
        List<Map<String, Object>> l = limits(t.run().get(0), "100:01:ACT_FT:MAX_TRANSACTION_LIMIT");
        assertEquals(2, l.size());
        assertEquals(2, ((List<?>) l.get(0).get("approvers")).size(), "A or B up to 5000: ANY 1 of {A,B}");
        assertEquals(1, l.get(0).get("numberOfApprovals"));
        assertEquals(1, ((List<?>) l.get(1).get("approvers")).size(), "only B above 5000");
    }

    @Test
    void nonMonetaryRulesUseTheProductsFormat() {
        ApprovalConverterTest t = contract();
        t.rule("R1", "STMT", "0", "0", 1, "N", "A");
        CustomerPlan cp = t.run().get(0);
        List<Map<String, Object>> l = limits(cp, "100:01:ACT_STMT:NON_MONETARY_LIMIT");
        assertEquals(1, l.size());
        assertEquals("-1.00", l.get(0).get("lowerlimit"));
        assertEquals("-1.00", l.get(0).get("upperlimit"));
    }

    @Test
    void aUserNeededInTwoDifferentGroupsIsAConflictForTheBank() {
        ApprovalConverterTest t = contract();
        t.rule("R1", "FT", "0", "9999999999", 1, "N", "G:X");
        t.rule("R2", "ACH", "0", "9999999999", 1, "N", "G:Y");
        t.member("X", "A", "B");
        t.member("Y", "B", "C");
        CustomerPlan cp = t.run("COUNTS", 1.1, "SHARED_BOUNDARY").get(0);
        assertTrue(cp.problems().stream().anyMatch(p -> p.code().equals("ONE_GROUP_CONFLICT")), cp.problems().toString());
        assertEquals("HARD", cp.complexity());
    }

    @Test
    void whatCannotBeConvertedIsReportedNeverGuessed() {
        ApprovalConverterTest t = contract();
        t.add("SRC_RULE", "contractRef", "K1", "ruleRef", "SEQ", "txnType", "FT", "fromAmount", "0", "toAmount", "9999999999",
                "requiredApprovals", "2", "selfApprove", "N", "sequential", "Y");
        t.rule("ZERO", "FT", "0", "9999999999", 0, "N");
        t.rule("MISS", "FT", "0", "9999999999", 1, "N", "G:NOBODY");
        t.rule("UNK", "WIRE", "0", "9999999999", 1, "N", "A");
        Set<String> codes = new TreeSet<>();
        t.run().forEach(cp -> cp.problems().forEach(p -> codes.add(p.code())));
        assertEquals(Set.of("SEQUENTIAL_PRODUCT_GAP", "ZERO_AUTHORIZERS_ANOMALY", "MISSING_GROUP", "UNKNOWN_ACTION"), codes);
    }

    @Test
    void theEightyTwentyRuleKeepsUserModeAndReportsTheRest() {
        ApprovalConverterTest t = contract();
        int n = 0;
        for (String txn : List.of("FT", "STMT"))
            for (String acc : List.of("01", "02")) {
                boolean money = txn.equals("FT");
                t.add("SRC_RULE", "contractRef", "K1", "ruleRef", "U" + (++n), "accountId", acc, "txnType", txn, "fromAmount", "0",
                        "toAmount", money ? "9999999999" : "0", "requiredApprovals", "1", "selfApprove", "N");
                t.add("SRC_RULE_CHECKER", "contractRef", "K1", "ruleRef", "U" + n, "position", "1", "checkerRef", "A", "isGroup", "N");
            }
        t.rule("G1", "FT", "0", "9999999999", 2, "N", "G:X", "G:Y");            // AND of two groups: only group mode can say it
        t.member("X", "B");
        t.member("Y", "C");
        CustomerPlan cp = t.run().get(0);
        assertEquals("USER", cp.mode(), "4 of 5 contexts fit user mode: 80%");
        assertEquals(4, cp.userCompatible());
        assertEquals(List.of("USER_MODE_EXCEPTION"), cp.problems().stream().map(Problem::code).toList());
        assertEquals(4, cp.rules().size());
    }

    @Test
    void theOldPlusOneBoundariesStayAvailableAsASwitch() {
        ApprovalConverterTest t = contract();
        t.rule("R1", "FT", "0", "1000", 1, "N", "A");
        t.rule("R2", "FT", "1000", "9999999999", 1, "N", "B");
        List<Map<String, Object>> l = limits(t.run("COUNTS", 0.8, "UPPER_PLUS_ONE").get(0), "100:01:ACT_FT:MAX_TRANSACTION_LIMIT");
        assertEquals("1001", l.get(1).get("lowerlimit"));
    }
}
