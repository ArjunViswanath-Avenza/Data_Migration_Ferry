package com.dataferry.mock;

import com.dataferry.config.ConfigStore;
import com.dataferry.core.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A stand-in for the vendor's DataMigration APIs (build step 8, brought forward). It answers like
 * the real service (HTTP 200 with opstatus and dbpErrCode, Fabric login tokens) and stores what it
 * creates in a small database shaped like the product's own (contract, contractcorecustomers,
 * contractaccounts with one contract per account, customer, contractcustomers, signatorygroup ...),
 * so read-back, the Survey and Disembark can all be tried against it.
 *
 * Every open question can be answered either way by a behaviour switch: slow or throttled calls,
 * timeouts that still create, name clashes, an unsupported core, required fields, responses
 * without IDs, contracts that lose their accounts. That is how each branch of Crossing is tested
 * before a real environment.
 */
@Service
public class MockTarget {

    private static final Logger log = LoggerFactory.getLogger(MockTarget.class);

    /** Switches for how the mock behaves. All off by default: the mock then behaves like a healthy target. */
    public static class Behaviour {
        public int latencyMs = 0;
        public int throttleEvery = 0;          // every Nth call answers HTTP 429
        public int serverErrorEvery = 0;       // every Nth call answers HTTP 500 and creates nothing
        public int timeoutEvery = 0;           // every Nth create succeeds but answers only after slowMs (the caller times out)
        public int slowMs = 5000;
        public List<String> contractNameClashCifs = new ArrayList<>();   // CreateContract answers 21967 / 10401
        public boolean linkUserUnsupported = false;                      // LinkUserToContract answers 10001 (core not supported)
        public List<String> virtualUserRequired = new ArrayList<>(List.of("firstName", "lastName", "email", "phone"));
        public boolean successWithoutId = false;                         // successes carry no ID
        public boolean contractDropsAccounts = false;                    // contract created without its accounts
        public int tokenLifetimeSeconds = 1200;
        public boolean noApprovalRejected = false;                       // question 8: no-approval ranges answer 87115
        public boolean groupRuleFlagsOnly = false;                       // question 3: groupRule numbers above 1 answer 87114
        public boolean rangesPlusOne = false;                            // question 4: ranges must start one above the previous end
    }

    private final DataSource db;
    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;
    private final ConfigStore config;
    private volatile Behaviour behaviour = new Behaviour();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong creates = new AtomicLong();
    private final Map<String, Long> tokens = Collections.synchronizedMap(new HashMap<>());

    public MockTarget(Json json, ConfigStore config) {
        this.json = json;
        this.config = config;
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:ferry_mock_dbxdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        this.db = ds;
        this.jdbc = new NamedParameterJdbcTemplate(ds);
        reset();
    }

    /** The mock's database, for read-back and the Survey ("built-in mock database"). */
    public DataSource database() { return db; }

    public Behaviour behaviour() { return behaviour; }

    public void behaviour(Behaviour b) { this.behaviour = b == null ? new Behaviour() : b; }

    public synchronized void reset() {
        String[] ddl = {
                "DROP ALL OBJECTS",
                "CREATE TABLE contract (id VARCHAR(50) PRIMARY KEY, name VARCHAR(100), serviceDefinitionId VARCHAR(50), legalEntityId VARCHAR(50), statusId VARCHAR(50) DEFAULT 'SID_CONTRACT_ACTIVE', CONSTRAINT Name_UNIQUE UNIQUE (name))",
                "CREATE TABLE contractcorecustomers (id VARCHAR(50) PRIMARY KEY, contractId VARCHAR(50), coreCustomerId VARCHAR(50), coreCustomerName VARCHAR(100), isPrimary VARCHAR(5))",
                "CREATE TABLE contractaccounts (id VARCHAR(50) PRIMARY KEY, contractId VARCHAR(50), accountId VARCHAR(50), coreCustomerId VARCHAR(50), typeId VARCHAR(50), CONSTRAINT accountId_UNIQUE UNIQUE (accountId))",
                "CREATE TABLE customer (id VARCHAR(50) PRIMARY KEY, UserName VARCHAR(100), FirstName VARCHAR(100), MiddleName VARCHAR(100), LastName VARCHAR(100), DateOfBirth VARCHAR(20), Ssn VARCHAR(50))",
                "CREATE TABLE contractcustomers (id VARCHAR(50) PRIMARY KEY, contractId VARCHAR(50), customerId VARCHAR(50), coreCustomerId VARCHAR(50), isPrimary VARCHAR(5))",
                "CREATE TABLE signatorygroup (signatoryGroupId VARCHAR(50) PRIMARY KEY, signatoryGroupName VARCHAR(100), signatoryGroupDescription VARCHAR(200), coreCustomerId VARCHAR(50), contractId VARCHAR(50))",
                "CREATE TABLE customersignatorygroup (customerSignatoryGroupId VARCHAR(50) PRIMARY KEY, signatoryGroupId VARCHAR(50), customerId VARCHAR(50))",
                "CREATE TABLE approvalrule (id VARCHAR(50) PRIMARY KEY, name VARCHAR(100), numberOfApprovals INT)",
                "INSERT INTO approvalrule VALUES ('ALL','All Approvals',-1),('ANY_ONE','Any one approval',1),('ANY_TWO','Any two approvals',2),('ANY_THREE','Any three approvals',3),('NO_APPROVAL','No approval',0)",
                "CREATE TABLE application (id VARCHAR(10) PRIMARY KEY, isSelfApprovalEnabled INT)",
                "INSERT INTO application VALUES ('1', 1)",
                "CREATE TABLE featureaction (id VARCHAR(100) PRIMARY KEY, name VARCHAR(100), isApprovalAction INT)",
                "INSERT INTO featureaction VALUES ('ACH_PAYMENT_CREATE','ACH payment',1),('INTRA_BANK_FUND_TRANSFER_CREATE','Transfer within the bank',1),('BULK_PAYMENT_SINGLE_SUBMIT','Bulk payment',1),('ACCOUNT_VIEW','View accounts',0)",
                "CREATE TABLE servicedefinition (id VARCHAR(50) PRIMARY KEY, name VARCHAR(100))",
                "CREATE TABLE approvalmode (id VARCHAR(50) PRIMARY KEY, coreCustomerId VARCHAR(50), contractId VARCHAR(50), isGroupLevel INT, CONSTRAINT IDX_coreCustomerID_ContractId UNIQUE (contractId, coreCustomerId))",
                "CREATE TABLE approvalmatrix (id BIGINT AUTO_INCREMENT PRIMARY KEY, contractId VARCHAR(50), coreCustomerId VARCHAR(50), actionId VARCHAR(255), accountId VARCHAR(50), approvalruleId VARCHAR(50), isGroupMatrix INT, limitTypeId VARCHAR(50), lowerlimit DECIMAL(20,2), upperlimit DECIMAL(20,2), softdeleteflag INT DEFAULT 0)",
                "CREATE TABLE signatorygroupmatrix (signatoryGroupMatrixId BIGINT AUTO_INCREMENT PRIMARY KEY, approvalMatrixId BIGINT, groupList VARCHAR(2000), groupRule VARCHAR(2000), softdeleteflag INT DEFAULT 0)",
        };
        try (var c = db.getConnection(); var s = c.createStatement()) {
            for (String d : ddl) s.execute(d);
        } catch (Exception e) {
            throw new IllegalStateException("Mock database could not be created: " + e.getMessage(), e);
        }
        for (var sd : config.get().referenceData().serviceDefinitions)
            jdbc.update("INSERT INTO servicedefinition (id, name) VALUES (:id, :n)", Map.of("id", sd.targetId, "n", sd.ref));
        tokens.clear();
        calls.set(0);
        creates.set(0);
    }

    // ------------------------------------------------------------------ login

    public record Reply(int httpStatus, Map<String, Object> body, long delayMs) {}

    public Reply login(String appKey, String appSecret) {
        if (blank(appKey) || blank(appSecret)) return new Reply(401, Map.of("opstatus", 8009, "errmsg", "App key and secret are required"), 0);
        String token = UUID.randomUUID().toString();
        long exp = System.currentTimeMillis() / 1000 + behaviour.tokenLifetimeSeconds;
        tokens.put(token, exp);
        return new Reply(200, Map.of("claims_token", Map.of("value", token, "exp", exp * 1000)), behaviour.latencyMs);
    }

    // ------------------------------------------------------------------ operations

    public Reply call(String operation, String token, Map<String, Object> body) {
        Behaviour b = behaviour;
        long n = calls.incrementAndGet();
        Long exp = token == null ? null : tokens.get(token);
        if (exp == null || exp * 1000 < System.currentTimeMillis())
            return new Reply(401, Map.of("opstatus", 1015, "errmsg", "Session expired or not valid"), b.latencyMs);
        if (b.throttleEvery > 0 && n % b.throttleEvery == 0) return new Reply(429, Map.of("errmsg", "Too many requests"), b.latencyMs);
        if (b.serverErrorEvery > 0 && n % b.serverErrorEvery == 0) return new Reply(500, Map.of("errmsg", "Internal server error"), b.latencyMs);

        Map<String, Object> out;
        try {
            out = switch (operation) {
                case "CreateContract" -> createContract(body, b);
                case "LinkUserToContract" -> linkUser(body, b);
                case "CreateVirtualUser" -> virtualUser(body, b);
                case "CreateSignatoryGroup" -> signatoryGroup(body, b);
                case "UpdateApprovalMatrixMode" -> approvalMode(body);
                case "CreateApprovalMatrixRule" -> approvalRule(body, b);
                default -> fail(10351, "Operation " + operation + " is not part of the mock yet");
            };
        } catch (RuntimeException e) {
            log.warn("Mock {} failed: {}", operation, e.getMessage());
            out = fail(10351, "Backend failure: " + e.getMessage());
        }
        boolean created = !out.containsKey("dbpErrCode");
        long delay = b.latencyMs;
        if (created && operation.startsWith("Create") && b.timeoutEvery > 0 && creates.incrementAndGet() % b.timeoutEvery == 0) delay = b.slowMs;
        return new Reply(200, out, delay);
    }

    private Map<String, Object> createContract(Map<String, Object> p, Behaviour b) {
        String primary = str(p.get("primaryCif"));
        if (blank(str(p.get("legalEntityId")))) return fail(29040, "Legal entity ID cannot be empty");
        if (blank(primary) || blank(str(p.get("serviceDefinitionId")))) return fail(22232, "Legal entity, service definition or contract customers missing");
        if (b.contractNameClashCifs.contains(primary))
            return failReason(21967, "Error returned while creating or editing contract", 10401, "A contract is already existing with given name, please choose another");
        List<Map<String, Object>> cifs = json.mapper().convertValue(parse(p.get("cifList")), List.class);
        String id = digits(10);
        try {
            jdbc.update("INSERT INTO contract (id, name, serviceDefinitionId, legalEntityId) VALUES (:id, :n, :sd, :le)",
                    new MapSqlParameterSource("id", id).addValue("n", "Customer " + primary).addValue("sd", p.get("serviceDefinitionId")).addValue("le", p.get("legalEntityId")));
        } catch (DuplicateKeyException e) {
            return failReason(21967, "Error returned while creating or editing contract", 10401, "A contract is already existing with given name, please choose another");
        }
        for (Map<String, Object> c : cifs) {
            String cif = str(c.get("cif"));
            jdbc.update("INSERT INTO contractcorecustomers (id, contractId, coreCustomerId, coreCustomerName, isPrimary) VALUES (:i, :c, :cif, :n, :p)",
                    new MapSqlParameterSource("i", UUID.randomUUID().toString()).addValue("c", id).addValue("cif", cif).addValue("n", "Customer " + cif).addValue("p", cif.equals(primary) ? "true" : "false"));
            if (b.contractDropsAccounts) continue;
            for (Object a : (List<?>) c.getOrDefault("accounts", List.of())) {
                String acc = str(((Map<?, ?>) a).get("accountId"));
                try {
                    jdbc.update("INSERT INTO contractaccounts (id, contractId, accountId, coreCustomerId, typeId) VALUES (:i, :c, :a, :cif, 'SAVINGS')",
                            new MapSqlParameterSource("i", UUID.randomUUID().toString()).addValue("c", id).addValue("a", acc).addValue("cif", cif));
                } catch (DuplicateKeyException e) {
                    throw new IllegalStateException("Account " + acc + " already belongs to another contract");
                }
            }
        }
        return ok(b.successWithoutId ? null : "contractId", id);
    }

    private Map<String, Object> linkUser(Map<String, Object> p, Behaviour b) {
        if (b.linkUserUnsupported) return fail(10001, "Invalid parameters: the customer is not found in the core");
        String contractId = str(p.get("contractId")), cif = str(p.get("cif"));
        if (blank(contractId) || blank(cif)) return fail(10348, "Mandatory input fields are empty.");
        if (count("SELECT COUNT(*) FROM contract WHERE id = :id", contractId) == 0) return fail(10790, "Contract not found for the given ID");
        String id = digits(10);
        jdbc.update("INSERT INTO customer (id, UserName) VALUES (:id, :u)", new MapSqlParameterSource("id", id).addValue("u", p.get("legacyUserName")));
        jdbc.update("INSERT INTO contractcustomers (id, contractId, customerId, coreCustomerId) VALUES (:i, :c, :u, :cif)",
                new MapSqlParameterSource("i", UUID.randomUUID().toString()).addValue("c", contractId).addValue("u", id).addValue("cif", cif));
        return ok(b.successWithoutId ? null : "id", id);
    }

    private Map<String, Object> virtualUser(Map<String, Object> p, Behaviour b) {
        String contractId = str(p.get("contractId"));
        for (String f : b.virtualUserRequired)
            if (blank(str(p.get(f.equals("dateOfBirth") ? "dob" : f)))) return fail(10050, "Invalid input: " + f + " failed validation");
        if (count("SELECT COUNT(*) FROM contract WHERE id = :id", contractId) == 0) return fail(10790, "Contract not found for the given ID");
        String id = digits(10);
        jdbc.update("INSERT INTO customer (id, UserName, FirstName, MiddleName, LastName, DateOfBirth, Ssn) VALUES (:id, :u, :f, :m, :l, :d, :t)",
                new MapSqlParameterSource("id", id).addValue("u", p.get("legacyUserName")).addValue("f", p.get("firstName")).addValue("m", p.get("middleName"))
                        .addValue("l", p.get("lastName")).addValue("d", p.get("dob")).addValue("t", p.get("taxId")));
        jdbc.update("INSERT INTO contractcustomers (id, contractId, customerId, coreCustomerId) VALUES (:i, :c, :u, :cif)",
                new MapSqlParameterSource("i", UUID.randomUUID().toString()).addValue("c", contractId).addValue("u", id).addValue("cif", p.get("contractCif")));
        return ok(b.successWithoutId ? null : "id", id);
    }

    private Map<String, Object> signatoryGroup(Map<String, Object> p, Behaviour b) {
        String contractId = str(p.get("contractId")), name = str(p.get("signatoryGroupName"));
        if (blank(name) || !name.matches("[A-Za-z0-9 ]{1,100}")) return fail(21023, "Signatory group name or description contains special characters");
        if (count("SELECT COUNT(*) FROM signatorygroup WHERE contractId = :id AND signatoryGroupName = :n", contractId, name) > 0)
            return fail(21020, "Another signatory group exists with the same name");
        List<Map<String, Object>> members = json.mapper().convertValue(parse(p.get("signatories")), List.class);
        for (Map<String, Object> m : members) {
            String user = str(m.get("userId"));
            if (count("SELECT COUNT(*) FROM contractcustomers WHERE contractId = :id AND customerId = :n", contractId, user) == 0)
                return fail(21018, "Signatory user not found in contract");
            if (count("SELECT COUNT(*) FROM customersignatorygroup s JOIN signatorygroup g ON g.signatoryGroupId = s.signatoryGroupId WHERE g.contractId = :id AND s.customerId = :n", contractId, user) > 0)
                return fail(21021, "Signatory is already in another group");
        }
        String id = digits(8);
        jdbc.update("INSERT INTO signatorygroup (signatoryGroupId, signatoryGroupName, signatoryGroupDescription, coreCustomerId, contractId) VALUES (:id, :n, :d, :cif, :c)",
                new MapSqlParameterSource("id", id).addValue("n", name).addValue("d", p.get("signatoryGroupDescription")).addValue("cif", p.get("cif")).addValue("c", contractId));
        for (Map<String, Object> m : members)
            jdbc.update("INSERT INTO customersignatorygroup (customerSignatoryGroupId, signatoryGroupId, customerId) VALUES (:i, :g, :u)",
                    new MapSqlParameterSource("i", UUID.randomUUID().toString()).addValue("g", id).addValue("u", str(m.get("userId"))));
        return ok(b.successWithoutId ? null : "id", id);
    }

    private Map<String, Object> approvalMode(Map<String, Object> p) {
        String contractId = str(p.get("contractId")), cif = str(p.get("cif"));
        if (count("SELECT COUNT(*) FROM contract WHERE id = :id", contractId) == 0) return fail(10790, "Contract not found for the given ID");
        int level = "1".equals(str(p.get("isGroupLevel"))) ? 1 : 0;
        List<Integer> now = jdbc.queryForList("SELECT isGroupLevel FROM approvalmode WHERE contractId = :c AND coreCustomerId = :cif",
                Map.of("c", contractId, "cif", cif), Integer.class);
        int current = now.isEmpty() ? 0 : now.get(0);                    // user level is the default
        if (current == level) return fail(10205, "isGroupMatrix flag is same as the input");
        if (now.isEmpty())
            jdbc.update("INSERT INTO approvalmode (id, coreCustomerId, contractId, isGroupLevel) VALUES (:i, :cif, :c, :l)",
                    new MapSqlParameterSource("i", UUID.randomUUID().toString()).addValue("cif", cif).addValue("c", contractId).addValue("l", level));
        else jdbc.update("UPDATE approvalmode SET isGroupLevel = :l WHERE contractId = :c AND coreCustomerId = :cif", Map.of("l", level, "c", contractId, "cif", cif));
        Map<String, Object> ok = ok(null, null);
        ok.put("status", "Created Successfully");
        return ok;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> approvalRule(Map<String, Object> p, Behaviour b) {
        String contractId = str(p.get("contractId")), cif = str(p.get("cif")), action = str(p.get("actionId")), limitType = str(p.get("limitTypeId"));
        String account = p.get("accountId") == null ? "" : str(p.get("accountId"));
        if (count("SELECT COUNT(*) FROM contract WHERE id = :id", contractId) == 0) return fail(10790, "Contract not found for the given ID");
        if (blank(action) || blank(limitType) || !(p.get("limits") instanceof List<?> raw) || raw.isEmpty()) return fail(10348, "Mandatory input fields are empty.");
        List<Map<String, Object>> limits = (List<Map<String, Object>>) raw;
        boolean group = "1".equals(str(p.get("isGroupMatrix")));
        List<Integer> mode = jdbc.queryForList("SELECT isGroupLevel FROM approvalmode WHERE contractId = :c AND coreCustomerId = :cif", Map.of("c", contractId, "cif", cif), Integer.class);
        boolean groupMode = !mode.isEmpty() && mode.get(0) == 1;
        if (group != groupMode) return failReason(10205, "Error while trying to create Approval Rule", 87115, "Given Limit values are not applicable for the Approval mode!");

        // ranges: above the lower value, up to and including the upper; first starts at -1, last ends at -1, no gaps
        java.math.BigDecimal prevUpper = null;
        for (int i = 0; i < limits.size(); i++) {
            java.math.BigDecimal lo = new java.math.BigDecimal(str(limits.get(i).get("lowerlimit"))), hi = new java.math.BigDecimal(str(limits.get(i).get("upperlimit")));
            boolean first = i == 0, last = i == limits.size() - 1;
            if (first && lo.compareTo(java.math.BigDecimal.ONE.negate()) != 0) return failReason(10205, "Error while trying to create Approval Rule", 87114, "Invalid values for limit ranges");
            if (last != (hi.compareTo(java.math.BigDecimal.ONE.negate()) == 0)) return failReason(10205, "Error while trying to create Approval Rule", 87114, "Invalid values for limit ranges");
            if (!first) {
                java.math.BigDecimal want = b.rangesPlusOne ? prevUpper.add(java.math.BigDecimal.ONE) : prevUpper;
                if (lo.compareTo(want) != 0) return failReason(10205, "Error while trying to create Approval Rule", 87114, "Invalid values for limit ranges");
            }
            prevUpper = hi;
        }
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> l : limits) {
            String ruleId = str(l.get("approvalruleId"));
            boolean none = "NO_APPROVAL".equals(ruleId);
            if (none && b.noApprovalRejected) return failReason(10205, "Error while trying to create Approval Rule", 87115, "Given Limit values are not applicable for the Approval mode!");
            String stored, groupList = null, groupRule = null;
            if (!group) {
                int n = l.get("numberOfApprovals") == null ? 0 : Integer.parseInt(str(l.get("numberOfApprovals")));
                List<Map<String, Object>> approvers = (List<Map<String, Object>>) l.getOrDefault("approvers", List.of());
                if (!none && n == 0) none = true;
                if (!none) {
                    if (n < -1 || n > 3 || (n > 0 && approvers.size() < n) || approvers.isEmpty())
                        return failReason(10205, "Error while trying to create Approval Rule", 87114, "Invalid values for limit ranges");
                    for (Map<String, Object> a : approvers)
                        if (count("SELECT COUNT(*) FROM contractcustomers WHERE contractId = :id AND customerId = :n", contractId, str(a.get("approverId"))) == 0)
                            return fail(21018, "Signatory user not found in contract");
                }
                stored = none ? "NO_APPROVAL" : switch (n) { case -1 -> "ALL"; case 1 -> "ANY_ONE"; case 2 -> "ANY_TWO"; default -> "ANY_THREE"; };
            } else {
                groupList = str(l.getOrDefault("groupList", "[]"));
                groupRule = str(l.getOrDefault("groupRule", "[]"));
                List<String> ids = idList(groupList);
                List<List<Number>> paths;
                try { paths = json.mapper().readValue(groupRule, List.class); }
                catch (Exception e) { return failReason(10205, "Error while trying to create Approval Rule", 87114, "Invalid values for limit ranges"); }
                for (String gid : ids)
                    if (count("SELECT COUNT(*) FROM signatorygroup WHERE signatoryGroupId = :id AND contractId = :n", gid, contractId) == 0)
                        return fail(21018, "Signatory group not found in contract");
                for (List<Number> path : paths) {
                    if (path.size() != ids.size()) return failReason(10205, "Error while trying to create Approval Rule", 87114, "Invalid values for limit ranges");
                    for (int k = 0; k < path.size(); k++) {
                        int v = path.get(k).intValue();
                        if (b.groupRuleFlagsOnly && v > 1) return failReason(10205, "Error while trying to create Approval Rule", 87114, "Invalid values for limit ranges");
                        long members = count("SELECT COUNT(*) FROM customersignatorygroup WHERE signatoryGroupId = :id", ids.get(k));
                        if (v > members) return failReason(10205, "Error while trying to create Approval Rule", 87114, "Invalid values for limit ranges");
                    }
                }
                stored = none ? "NO_APPROVAL" : null;
            }
            rows.add(new Object[]{l.get("lowerlimit"), l.get("upperlimit"), stored, groupList, groupRule});
        }
        // a second rule for the same case replaces the first, as the product's own edits do (soft delete)
        jdbc.update("UPDATE approvalmatrix SET softdeleteflag = 1 WHERE contractId = :c AND coreCustomerId = :cif AND actionId = :a AND limitTypeId = :l AND accountId = :acc",
                Map.of("c", contractId, "cif", cif, "a", action, "l", limitType, "acc", account));
        for (Object[] r : rows) {
            var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update("INSERT INTO approvalmatrix (contractId, coreCustomerId, actionId, accountId, approvalruleId, isGroupMatrix, limitTypeId, lowerlimit, upperlimit) " +
                            "VALUES (:c, :cif, :a, :acc, :r, :g, :l, :lo, :hi)",
                    new MapSqlParameterSource("c", contractId).addValue("cif", cif).addValue("a", action).addValue("acc", account).addValue("r", r[2])
                            .addValue("g", group ? 1 : 0).addValue("l", limitType).addValue("lo", new java.math.BigDecimal(str(r[0]))).addValue("hi", new java.math.BigDecimal(str(r[1]))),
                    key, new String[]{"id"});
            if (group) jdbc.update("INSERT INTO signatorygroupmatrix (approvalMatrixId, groupList, groupRule) VALUES (:m, :gl, :gr)",
                    new MapSqlParameterSource("m", key.getKey().longValue()).addValue("gl", r[3]).addValue("gr", r[4]));
        }
        return ok(null, null);
    }

    /** groupList "[id,id]": IDs may be quoted or not. */
    private static List<String> idList(String s) {
        String t = s.trim();
        if (t.startsWith("[")) t = t.substring(1);
        if (t.endsWith("]")) t = t.substring(0, t.length() - 1);
        List<String> out = new ArrayList<>();
        for (String x : t.split(",")) { x = x.trim().replace("\"", ""); if (!x.isEmpty()) out.add(x); }
        return out;
    }

    public Map<String, Object> state() {
        Map<String, Object> s = new LinkedHashMap<>();
        for (String t : List.of("contract", "contractcorecustomers", "contractaccounts", "customer", "contractcustomers", "signatorygroup", "customersignatorygroup",
                "approvalmode", "approvalmatrix", "signatorygroupmatrix"))
            s.put(t, jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Map.of(), Long.class));
        s.put("calls", calls.get());
        return s;
    }

    // ------------------------------------------------------------------ helpers

    private long count(String sql, String a) { return jdbc.queryForObject(sql, Map.of("id", a == null ? "" : a), Long.class); }

    private long count(String sql, String a, String b) {
        return jdbc.queryForObject(sql, Map.of("id", a == null ? "" : a, "n", b == null ? "" : b), Long.class);
    }

    private Object parse(Object v) {
        if (v instanceof String s) {
            try { return json.mapper().readValue(s, Object.class); }
            catch (Exception e) { throw new IllegalArgumentException("not valid JSON: " + s); }
        }
        return v == null ? List.of() : v;
    }

    private static Map<String, Object> ok(String idField, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opstatus", 0);
        if (idField != null) m.put(idField, id);
        m.put("httpStatusCode", 0);
        return m;
    }

    private static Map<String, Object> fail(int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dbpErrCode", code);
        m.put("opstatus", 0);
        m.put("dbpErrMsg", message);
        m.put("httpStatusCode", 0);
        return m;
    }

    private static Map<String, Object> failReason(int code, String message, int inner, String innerMessage) {
        Map<String, Object> m = fail(code, message);
        m.put("FailureReason", " [ErrorCode=" + inner + ",  Message=" + innerMessage + "]");
        return m;
    }

    private static String digits(int n) {
        StringBuilder sb = new StringBuilder();
        sb.append(1 + ThreadLocalRandom.current().nextInt(9));
        for (int i = 1; i < n; i++) sb.append(ThreadLocalRandom.current().nextInt(10));
        return sb.toString();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
