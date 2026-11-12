package com.dataferry.disembark;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Disembark against an in-memory database in MySQL mode, with every table the real
 * config/unenroll.json touches. Three customers: C100 on its own contract, C200 and C201 sharing a
 * contract and a login, and C300, who must never be touched.
 */
class DisembarkTest {

    private static final Path PROFILE = Path.of("../config/unenroll.json");
    private Connection db;
    private NamedParameterJdbcTemplate jdbc;
    private UnenrollProfile profile;

    /** Columns the resolve lookups read, on top of the columns the delete steps use. */
    private static final Map<String, List<String>> LOOKUP_COLUMNS = Map.of(
            "contractcorecustomers", List.of("contractId", "coreCustomerId"),
            "contractcustomers", List.of("customerId", "coreCustomerId", "contractId"),
            "signatorygroup", List.of("signatoryGroupId", "coreCustomerId"),
            "customerapprovalmatrix", List.of("approvalMatrixId", "customerId"),
            "contractcustomrole", List.of("customRoleId", "coreCustomerId"),
            "customeraddress", List.of("Address_id", "Customer_id"),
            "contractaddress", List.of("addressId", "contractId"),
            "customeraccounts", List.of("Account_id", "Customer_id"),
            "customer", List.of("id", "UserName"),
            "customerrequest", List.of("id", "Customer_id"));

    @BeforeEach
    void setUp() throws Exception {
        profile = UnenrollProfile.load(PROFILE, new ObjectMapper());
        db = DriverManager.getConnection("jdbc:h2:mem:disembark" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "sa", "");
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        for (UnenrollProfile.DeleteStep d : profile.deleteSteps) tables.computeIfAbsent(d.table, k -> new LinkedHashSet<>()).add(d.column);
        LOOKUP_COLUMNS.forEach((t, cols) -> tables.computeIfAbsent(t, k -> new LinkedHashSet<>()).addAll(cols));
        try (Statement s = db.createStatement()) {
            s.execute("CREATE SCHEMA dbxdb");
            s.execute("SET SCHEMA dbxdb");
            for (Map.Entry<String, Set<String>> t : tables.entrySet()) {
                Set<String> cols = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                cols.addAll(t.getValue());
                s.execute("CREATE TABLE " + t.getKey() + " (" + cols.stream().map(c -> c + " VARCHAR(64)").collect(Collectors.joining(", ")) + ")");
            }
        }
        jdbc = DisembarkService.jdbc(db);
        seed();
    }

    @AfterEach
    void tearDown() throws Exception { db.close(); }

    private void ins(String table, Object... kv) {
        StringBuilder cols = new StringBuilder(), vals = new StringBuilder();
        Map<String, Object> p = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) { cols.append(", "); vals.append(", "); }
            cols.append(kv[i]); vals.append(":v").append(i);
            p.put("v" + i, kv[i + 1]);
        }
        jdbc.update("INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ")", p);
    }

    private void seed() {
        for (String[] c : new String[][]{{"C100", "K1", "U1"}, {"C300", "K3", "U3"}}) {
            ins("contractcorecustomers", "contractId", c[1], "coreCustomerId", c[0]);
            ins("contractcustomers", "customerId", c[2], "coreCustomerId", c[0], "contractId", c[1]);
            ins("contract", "id", c[1]);
            ins("customer", "id", c[2], "UserName", "user-" + c[2]);
            ins("credentialchecker", "UserName", "user-" + c[2]);
            ins("contractaccounts", "contractId", c[1]);
            ins("customeraccounts", "Account_id", "AC-" + c[0], "Customer_id", c[2]);
            ins("accounts", "Account_id", "AC-" + c[0]);
            ins("customeraddress", "Address_id", "AD-" + c[0], "Customer_id", c[2]);
            ins("address", "id", "AD-" + c[0]);
            ins("signatorygroup", "signatoryGroupId", "SG-" + c[0], "coreCustomerId", c[0]);
            ins("customerapprovalmatrix", "approvalMatrixId", "AM-" + c[0], "customerId", c[2]);
            ins("signatorygroupmatrix", "approvalMatrixId", "AM-" + c[0]);
            ins("customerrequest", "id", "R-" + c[0], "Customer_id", c[2]);
            ins("requestmessage", "CustomerRequest_id", "R-" + c[0]);
            ins("intrabankpayee", "createdby", c[2]);
            for (int i = 0; i < 3; i++) ins("customeraction", "Customer_id", c[2]);
        }
        // C200 and C201: one contract, one login across both
        ins("contractcorecustomers", "contractId", "K2", "coreCustomerId", "C200");
        ins("contractcorecustomers", "contractId", "K2", "coreCustomerId", "C201");
        ins("contractcustomers", "customerId", "U2", "coreCustomerId", "C200", "contractId", "K2");
        ins("contractcustomers", "customerId", "U2", "coreCustomerId", "C201", "contractId", "K2");
        ins("contract", "id", "K2");
        ins("customer", "id", "U2", "UserName", "user-U2");
    }

    private long rows(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Map.of(), Long.class); }

    private Map<String, UnenrollEngine.StepResult> byTable(List<UnenrollEngine.StepResult> steps) {
        Map<String, UnenrollEngine.StepResult> m = new LinkedHashMap<>();
        for (UnenrollEngine.StepResult s : steps) m.putIfAbsent(s.table(), s);
        return m;
    }

    // ------------------------------------------------------------------

    @Test
    void thePreviewResolvesFirstAndCountsEveryTable() {
        UnenrollEngine.Plan plan = new UnenrollEngine(profile).preview(jdbc, "C100", "dbxdb");
        assertEquals(1, plan.resolved().get("contractId"));
        assertEquals(1, plan.resolved().get("customerId"));
        assertEquals(List.of("U1"), plan.sample().get("customerId"));
        Map<String, UnenrollEngine.StepResult> t = byTable(plan.steps());
        assertEquals(3L, t.get("customeraction").rows());
        assertEquals(1L, t.get("requestmessage").rows(), "found through the request IDs resolved first");
        assertEquals(1L, t.get("credentialchecker").rows(), "found through the user name");
        assertEquals("OFF", t.get("intrabankpayee").state(), "payment history is off by default");
        assertEquals("OFF", t.get("accounts").state(), "shared accounts are off by default");
        assertEquals(0L, t.get("customrolesignatorygroup").rows(), "IDs found, no rows");
        assertEquals(0, plan.resolved().get("customRoleId"));
        assertTrue(plan.checks().stream().allMatch(c -> c.findings().isEmpty()), "a single customer shares nothing");
        assertEquals(0, rows("customer") - 3, "a preview deletes nothing");
    }

    @Test
    void sharedLoginsAndContractsAreReported() {
        UnenrollEngine.Plan plan = new UnenrollEngine(profile).preview(jdbc, "C200", "dbxdb");
        Map<String, UnenrollEngine.CheckResult> c = plan.checks().stream().collect(Collectors.toMap(UnenrollEngine.CheckResult::id, x -> x));
        assertEquals(List.of(new UnenrollEngine.Finding("U2", "C201")), c.get("SHARED_LOGIN").findings());
        assertEquals(List.of(new UnenrollEngine.Finding("K2", "C201")), c.get("SHARED_CONTRACT").findings());
    }

    @Test
    void executeRemovesOnlyThatCustomer() throws Exception {
        db.setAutoCommit(false);
        List<UnenrollEngine.StepResult> steps = new UnenrollEngine(profile).execute(jdbc, "C100", "dbxdb");
        db.commit();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE id = 'U1'", Map.of(), Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM contract WHERE id = 'K1'", Map.of(), Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM requestmessage WHERE CustomerRequest_id = 'R-C100'", Map.of(), Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE id = 'U3'", Map.of(), Long.class), "C300 is untouched");
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM customeraction WHERE Customer_id = 'U3'", Map.of(), Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM intrabankpayee WHERE createdby = 'U1'", Map.of(), Long.class), "off steps stay");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE Account_id = 'AC-C100'", Map.of(), Long.class), "accounts stay");
        assertEquals(3L, byTable(steps).get("customeraction").rows());
        assertEquals("DELETED", byTable(steps).get("customer").state());
    }

    @Test
    void theGuardStopsBeforeAnythingIsDeleted() throws Exception {
        profile.maxRowsPerTableGuard = 2;                       // customeraction has 3 rows for C100
        db.setAutoCommit(false);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new UnenrollEngine(profile).execute(jdbc, "C100", "dbxdb"));
        db.rollback();
        assertTrue(e.getMessage().contains("customeraction (3 rows)"), e.getMessage());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE id = 'U1'", Map.of(), Long.class));
    }

    @Test
    void aTableThisReleaseDoesNotHaveIsReportedNotFatal() throws Exception {
        try (Statement s = db.createStatement()) { s.execute("DROP TABLE coremembership"); s.execute("DROP TABLE contractcustomrole"); }
        UnenrollEngine.Plan plan = new UnenrollEngine(profile).preview(jdbc, "C100", "dbxdb");
        assertEquals("MISSING_TABLE", byTable(plan.steps()).get("coremembership").state());
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("customRoleId")), plan.notes().toString());
        String sql = new UnenrollEngine(profile).script("C100", "dbxdb", "run-1", "tester", plan);
        assertTrue(sql.contains("-- LEFT OUT: this database has no table coremembership"));
    }

    @Test
    void theScriptFreezesIdsAndNeverCommits() {
        UnenrollEngine e = new UnenrollEngine(profile);
        String offline = e.script("C100", "dbxdb", "run-1", "tester", null);
        assertTrue(offline.contains("USE `dbxdb`;"));
        assertTrue(offline.contains("CREATE TEMPORARY TABLE `_disembark_customerId` AS SELECT customerId FROM contractcustomers WHERE coreCustomerId = 'C100';"));
        assertTrue(offline.contains("DELETE FROM `dbxdb`.`customer` WHERE `id` IN (SELECT * FROM `_disembark_customerId`);"));
        assertTrue(offline.contains("DELETE FROM `dbxdb`.`manageapprovalmatrix` WHERE `coreCustomerId` = 'C100';"));
        assertTrue(offline.contains("-- OFF in config/unenroll.json"));
        assertTrue(offline.contains("WHERE customerId IN (SELECT * FROM `_disembark_customerId`) AND coreCustomerId <> 'C100'"), "checks use the frozen IDs");
        assertTrue(offline.indexOf("DELETE FROM `dbxdb`.`requestmessage`") < offline.indexOf("DELETE FROM `dbxdb`.`customerrequest`"));
        assertTrue(offline.indexOf("DELETE FROM `dbxdb`.`customer` ") > offline.indexOf("DELETE FROM `dbxdb`.`customeraction`"), "anchors last");
        assertTrue(Arrays.stream(offline.split("\n")).noneMatch(l -> l.trim().toUpperCase(Locale.ROOT).startsWith("COMMIT")), "never commits");

        String shared = e.script("C200", "dbxdb", "run-2", "tester", e.preview(jdbc, "C200", "dbxdb"));
        assertTrue(shared.contains("!!! LOGIN SHARED WITH ANOTHER CIF"));
        assertTrue(shared.contains("U2 is also linked to C201"));
    }

    @Test
    void aBadKeyAndABadProfileAreRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new UnenrollEngine(profile).preview(jdbc, "C100' OR '1'='1", "dbxdb"));
        Path bad = Files.createTempFile("unenroll", ".json");
        Files.writeString(bad, Files.readString(PROFILE).replace("\"maxRowsPerTableGuard\"", "\"maxRowsPerTableGaurd\""));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> UnenrollProfile.load(bad, new ObjectMapper()));
        assertTrue(e.getMessage().contains("maxRowsPerTableGaurd"), e.getMessage());
        Files.writeString(bad, Files.readString(PROFILE).replace("\"using\": \"customerId\" }", "\"using\": \"customerID\" }"));
        assertThrows(IllegalStateException.class, () -> UnenrollProfile.load(bad, new ObjectMapper()));
        Files.delete(bad);
    }
}
