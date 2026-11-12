package com.dataferry.drydock;

import com.dataferry.drydock.SchemaComparer.Comparison;
import com.dataferry.drydock.SchemaComparer.Difference;
import com.dataferry.drydock.SchemaModel.Table;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Dry Dock against the two sample files in samples/drydock, plus the awkward corners of SQL scripts. */
class DryDockTest {

    private static final Path SAMPLES = Path.of("../samples/drydock");

    private static SchemaModel load(String file) throws Exception {
        return DdlParser.parse(Files.readString(SAMPLES.resolve(file)), file);
    }

    private static Comparison sample() throws Exception {
        Comparison c = SchemaComparer.compare(load("source_customised.sql"), load("target_new_release.sql"), SchemaComparer.Options.defaults());
        c.id = "DD-TEST";
        return c;
    }

    private static Map<String, Difference> byId(Comparison c) {
        return c.differences.stream().collect(Collectors.toMap(d -> d.id, d -> d));
    }

    // ------------------------------------------------------------------ reading

    @Test
    void aMysqldumpIsReadWithoutItsDataAndWithItsRoutines() throws Exception {
        SchemaModel s = load("source_customised.sql");
        assertEquals("bank_live", s.database);
        assertEquals(List.of("branch_master", "customer", "account", "cust_audit_log"), new ArrayList<>(s.tables.keySet()),
                "the view's stand-in table must be gone");
        assertEquals(0, s.statementsNotUnderstood, s.warnings.toString());
        assertTrue(s.statementsSkipped >= 4, "INSERT / LOCK / SET statements are skipped");
        assertEquals(Set.of("VIEW:v_active_customer", "PROCEDURE:sp_close_dormant", "TRIGGER:trg_customer_audit"), s.routines.keySet());
        SchemaModel.Routine p = s.routines.get("PROCEDURE:sp_close_dormant");
        assertTrue(p.sql.contains("LIKE '%dormant;%'"), "the body is kept whole, semicolons included");
        assertFalse(p.sql.contains("DEFINER"), "the definer is removed so the script runs as whoever runs it");
        assertEquals("customer", s.routines.get("TRIGGER:trg_customer_audit").table);

        Table customer = s.table("customer");
        assertEquals("varchar", customer.column("first_name").dataType);
        assertEquals("100", customer.column("first_name").args);
        assertEquals("'L'", customer.column("risk_rating").defaultValue);
        assertEquals(List.of("id"), customer.primaryKey);
        assertEquals("branch_master", customer.foreignKeys.get("fk_customer_branch").refTable);
        assertEquals("utf8mb3", customer.charset, "utf8 is read as utf8mb3");
    }

    @Test
    void anUpgradeScriptIsReplayedOntoTheBaseSchema() throws Exception {
        SchemaModel t = load("target_new_release.sql");
        assertEquals("bank_next", t.database);
        Table customer = t.table("customer");
        List<String> cols = customer.columns.stream().map(c -> c.name).toList();
        assertEquals(List.of("id", "customer_id", "first_name", "last_name", "email_address", "phone", "preferred_language",
                "status_id", "kyc_level", "created_ts"), cols, "ALTER ... AFTER puts columns in place");
        assertTrue(t.routines.containsKey("VIEW:v_active_customer"));
    }

    @Test
    void awkwardScriptsAreReadCorrectly() {
        String sql = """
                # hash comment; with a semicolon
                CREATE TABLE a (id INT PRIMARY KEY, note VARCHAR(10) DEFAULT 'x;y', price NUMERIC(8), flag BOOLEAN NOT NULL DEFAULT b'0');
                CREATE TABLE b LIKE a;
                ALTER TABLE b CHANGE COLUMN note memo VARCHAR(40) NULL, ADD COLUMN extra TEXT FIRST, DROP COLUMN price;
                ALTER TABLE b RENAME COLUMN memo TO memo2, MODIFY flag TINYINT(1) NULL;
                CREATE UNIQUE INDEX ux_memo ON b (memo2(20));
                RENAME TABLE b TO c;
                DELIMITER //
                CREATE FUNCTION f1() RETURNS INT DETERMINISTIC BEGIN RETURN 1; END //
                DELIMITER ;
                DROP TABLE IF EXISTS nothing_here;
                """;
        SchemaModel s = DdlParser.parse(sql, "awkward.sql");
        assertEquals(0, s.statementsNotUnderstood, s.warnings.toString());
        assertEquals("'x;y'", s.table("a").column("note").defaultValue);
        assertEquals("8,0", s.table("a").column("price").args);
        assertEquals("tinyint", s.table("a").column("flag").dataType);
        Table c = s.table("c");
        assertNull(s.table("b"));
        assertEquals(List.of("extra", "id", "memo2", "flag"), c.columns.stream().map(x -> x.name).toList());
        assertEquals("40", c.column("memo2").args);
        assertTrue(c.column("flag").nullable);
        assertEquals(List.of("memo2(20)"), c.indexes.get("ux_memo").columns);
        assertTrue(s.routines.get("FUNCTION:f1").sql.endsWith("END"));
    }

    @Test
    void gzipAndZipFilesAreRead() throws Exception {
        byte[] raw = Files.readAllBytes(SAMPLES.resolve("target_new_release.sql"));
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) { g.write(raw); }
        SchemaModel fromGz = DryDockService.read(new DryDockService.Input("dump.sql.gz", () -> new ByteArrayInputStream(gz.toByteArray())));
        assertEquals(4, fromGz.tables.size());

        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(zip)) {
            z.putNextEntry(new java.util.zip.ZipEntry("01_base.sql"));
            z.write("CREATE TABLE t1 (id INT)".getBytes(StandardCharsets.UTF_8));      // no closing semicolon
            z.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
            z.write("CREATE TABLE ignored (id INT);".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new java.util.zip.ZipEntry("02_upgrade.sql"));
            z.write("ALTER TABLE t1 ADD COLUMN name VARCHAR(5);".getBytes(StandardCharsets.UTF_8));
        }
        SchemaModel fromZip = DryDockService.read(new DryDockService.Input("scripts.zip", () -> new ByteArrayInputStream(zip.toByteArray())));
        assertEquals(Set.of("t1"), fromZip.tables.keySet());
        assertEquals(2, fromZip.table("t1").columns.size());
    }

    // ------------------------------------------------------------------ comparing

    @Test
    void everyKindOfDifferenceIsFoundWithASafeDefault() throws Exception {
        Map<String, Difference> d = byId(sample());

        Difference firstName = d.get("C:customer:first_name");
        assertEquals("CHANGED", firstName.kind);
        assertEquals("APPLY", firstName.defaultAction, "a wider source type is carried over");
        assertEquals("WIDER", firstName.aspects.stream().filter(a -> a.key().equals("type")).findFirst().orElseThrow().verdict());

        assertEquals("APPLY", d.get("C:customer:phone").defaultAction, "source allows NULL: relax the target");
        assertEquals("KEEP", d.get("C:account:balance").defaultAction, "target already wider");
        assertEquals("APPLY", d.get("C:account:overdraft_limit").defaultAction);
        assertEquals("KEEP", d.get("C:account:status").defaultAction, "target enum has more values");
        assertEquals("REVIEW", d.get("C:account:currency").severity, "a different default needs a person");
        assertNull(d.get("C:customer:id"), "int(11) vs INT is display width only");

        Difference email = d.get("CR:customer:email>email_address");
        assertEquals("HIGH", email.confidence);
        assertEquals("MAP", email.defaultAction);

        Difference audit = d.get("TR:cust_audit_log>customer_audit_log");
        assertEquals("RENAMED", audit.kind);
        assertEquals("MAP", audit.defaultAction);

        assertEquals("ADD", d.get("C:customer:branch_code").defaultAction);
        assertEquals("REVIEW", d.get("C:customer:segment").severity, "NOT NULL without default");
        assertEquals("KEEP", d.get("C:customer:kyc_level").defaultAction);
        assertEquals("ONLY_IN_SOURCE", d.get("C:account:remarks").kind, "remarks/notes are too different to call a rename");
        assertEquals("ONLY_IN_TARGET", d.get("C:account:notes").kind);
        assertEquals("CREATE", d.get("T:branch_master").defaultAction);
        assertEquals("KEEP", d.get("T:consent_record").defaultAction);
        assertEquals("NAME_ONLY", d.get("I:account:idx_acc_customer").kind);
        assertEquals("ADD", d.get("I:customer:idx_customer_branch").defaultAction);
        assertEquals("ONLY_IN_TARGET", d.get("I:customer:idx_customer_status").kind);
        assertEquals("ADD", d.get("F:customer:fk_customer_branch").defaultAction);
        assertTrue(d.keySet().stream().noneMatch(k -> k.startsWith("F:account:")), "same rule under another name is not a difference");
        assertEquals("CREATE", d.get("R:procedure:sp_close_dormant").defaultAction);
        assertEquals("CREATE", d.get("R:trigger:trg_customer_audit").defaultAction);
        assertEquals("CHANGED", d.get("R:view:v_active_customer").kind);
        assertEquals("KEEP", d.get("O:customer").defaultAction);
        assertEquals("INFO", d.get("O:customer").severity, "a target already on utf8mb4 needs no decision");
        assertNull(d.get("C:customer:last_name"), "inherited character sets are reported once, per table");
    }

    // ------------------------------------------------------------------ scripts

    @Test
    void defaultScriptsAddAndWidenButNeverDrop() throws Exception {
        ScriptWriter.Result r = ScriptWriter.write(sample(), Map.of(),
                new ScriptWriter.Options("bank_live", "bank_next", "INSERT", false, List.of()));
        String refit = r.refit();
        assertTrue(refit.contains("USE `bank_next`;"));
        assertTrue(refit.contains("CREATE TABLE IF NOT EXISTS `branch_master`"));
        assertTrue(refit.contains("MODIFY COLUMN `first_name` varchar(100) NOT NULL\n") || refit.contains("MODIFY COLUMN `first_name` varchar(100) NOT NULL,"), refit);
        assertTrue(refit.contains("MODIFY COLUMN `phone` varchar(20) NULL"));
        assertTrue(refit.contains("ADD COLUMN `branch_code` varchar(10) NULL AFTER `status_id`"), refit);
        assertTrue(refit.contains("ADD CONSTRAINT `fk_customer_branch` FOREIGN KEY (`branch_code`) REFERENCES `branch_master` (`branch_code`)"));
        assertTrue(refit.contains("CREATE PROCEDURE `sp_close_dormant`"));
        assertFalse(refit.contains("DROP TABLE"), "nothing is dropped unless chosen");
        assertFalse(refit.contains("DROP COLUMN"));
        assertEquals(0, r.removals());

        String data = r.dataMove();
        assertTrue(data.contains("s.`email` AS `email_address`"), "a mapped rename moves the values");
        assertTrue(data.contains("0 AS `kyc_level`"), "a new NOT NULL column gets a placeholder");
        assertTrue(data.contains("FROM `bank_live`.`cust_audit_log` AS s"));
        assertTrue(data.contains("INSERT INTO `bank_next`.`customer_audit_log`"));
        assertTrue(data.indexOf("`bank_next`.`branch_master`") < data.indexOf("`bank_next`.`customer`"), "parents first");
        assertTrue(data.contains("s.`remarks` AS `remarks`"), "remarks is added by default, so its values move");
        assertTrue(refit.contains("-- CHECK: this trigger names table cust_audit_log, which is customer_audit_log in the target"),
                "a routine that names a mapped table is flagged");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("trg_customer_audit")));
    }

    @Test
    void choicesChangeTheScripts() throws Exception {
        Map<String, ScriptWriter.Decision> choices = Map.of(
                "CR:customer:email>email_address", new ScriptWriter.Decision("RENAME", null),
                "TR:cust_audit_log>customer_audit_log", new ScriptWriter.Decision("RENAME", null),
                "C:customer:kyc_level", new ScriptWriter.Decision("DROP", null),
                "C:customer:first_name", new ScriptWriter.Decision("KEEP", null),
                "C:account:currency", new ScriptWriter.Decision("APPLY", List.of("default")),
                "T:branch_master", new ScriptWriter.Decision("SKIP", null));
        ScriptWriter.Result r = ScriptWriter.write(sample(), choices, new ScriptWriter.Options("bank_live", "bank_next", "UPSERT", false, List.of()));
        String refit = r.refit();
        assertTrue(refit.contains("CHANGE COLUMN `email_address` `email` varchar(100) NULL"), refit);
        assertTrue(refit.contains("RENAME TABLE `customer_audit_log` TO `cust_audit_log`;"));
        assertTrue(refit.contains("DROP COLUMN `kyc_level`"));
        assertTrue(refit.indexOf("DROP COLUMN `kyc_level`") > refit.indexOf("7. Removals"), "removals come last");
        assertFalse(refit.contains("MODIFY COLUMN `first_name`"));
        assertTrue(refit.contains("MODIFY COLUMN `currency` char(3) NOT NULL DEFAULT 'USD'"));
        assertFalse(refit.contains("CREATE TABLE IF NOT EXISTS `branch_master`"));
        assertTrue(refit.contains("LEFT OUT: fk_customer_branch"), "a key to a table that is not created is left out, visibly");

        String data = r.dataMove();
        assertTrue(data.contains("s.`email` AS `email`"));
        assertTrue(data.contains("CHECK first_name"), "keeping the narrower type is flagged in the data move");
        assertFalse(data.contains("`bank_next`.`branch_master`"), "a table that is not created gets no rows");
        assertTrue(data.contains("ON DUPLICATE KEY UPDATE"));
        assertFalse(data.contains("kyc_level"));
    }

    @Test
    void unknownChoicesFallBackToTheDefaultAndBadSchemaNamesAreRefused() throws Exception {
        ScriptWriter.Result r = ScriptWriter.write(sample(), Map.of("T:consent_record", new ScriptWriter.Decision("EXPLODE", null)),
                new ScriptWriter.Options(null, null, null, false, null));
        assertEquals("KEEP", r.actions().get("T:consent_record"));
        assertTrue(r.dataMove().contains("`source_db`"));
        assertThrows(IllegalArgumentException.class, () -> new ScriptWriter.Options("x; DROP DATABASE y", "t", "INSERT", false, null));
        assertThrows(IllegalArgumentException.class, () -> new ScriptWriter.Options("s", "t", "MERGE", false, null));
    }
}
