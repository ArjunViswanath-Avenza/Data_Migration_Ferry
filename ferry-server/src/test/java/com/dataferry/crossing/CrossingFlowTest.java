package com.dataferry.crossing;

import com.dataferry.mock.MockTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crossing end to end over real HTTP against the built-in mock target, with a copy of config/ whose
 * mock environment points at this test server. Each test loads the bank template's worked example
 * (R-2001 retail, B-1001 business with three users) and plays one way the target could behave.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class CrossingFlowTest {

    static final int PORT = 18195;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Path src = Path.of("../config"), dst = Path.of("target/test-config/crossing");
        if (Files.exists(dst)) try (Stream<Path> w = Files.walk(dst)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        try (Stream<Path> w = Files.walk(src)) {
            for (Path p : w.toList()) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t);
            }
        }
        Path env = dst.resolve("environments.json");
        Files.writeString(env, Files.readString(env).replace("localhost:8095", "localhost:" + PORT));
        Path cr = dst.resolve("crossing.json");
        Files.writeString(cr, Files.readString(cr).replace("\"requestTimeoutSeconds\": 60", "\"requestTimeoutSeconds\": 2")
                .replace("\"backoffSeconds\": [2, 5, 15]", "\"backoffSeconds\": [0, 0, 0]"));
        r.add("server.port", () -> PORT);
        r.add("ferry.config-dir", dst::toString);
        r.add("ferry.data-dir", () -> "target/test-data/crossing-" + System.nanoTime());
    }

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper om;
    @Autowired MockTarget mock;
    @Autowired NamedParameterJdbcTemplate store;

    @BeforeEach
    void clean() {
        store.update("DELETE FROM id_register", Map.of());
        mock.reset();
        mock.behaviour(new MockTarget.Behaviour());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode post(String path, Object body) throws Exception {
        ResponseEntity<String> r = http.postForEntity(path, body, String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), path + " -> " + r.getStatusCode() + " " + r.getBody());
        return om.readTree(r.getBody());
    }

    private JsonNode get(String path) throws Exception { return om.readTree(http.getForObject(path, String.class)); }

    /** A new batch with the template's worked example, validated and planned. */
    private String plannedBatch() throws Exception {
        String id = post("/api/boarding/batches", Map.of("name", "crossing test", "performedBy", "tester")).get("batch_id").asText();
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new FileSystemResource(Path.of("../templates/Data_Migration_Template_EXAMPLE.xlsx")));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> up = http.postForEntity("/api/boarding/batches/" + id + "/files?performedBy=tester", new HttpEntity<>(form, h), String.class);
        assertTrue(up.getStatusCode().is2xxSuccessful(), up.getBody());
        post("/api/boarding/batches/" + id + "/validate", Map.of("performedBy", "tester"));
        post("/api/boarding/batches/" + id + "/plan", Map.of("performedBy", "tester"));
        return id;
    }

    private JsonNode start(String batch, int pilot, int parallel) throws Exception {
        return post("/api/crossing/runs", Map.of("batchId", batch, "pilot", pilot, "parallelism", parallel, "readBack", "MOCK", "performedBy", "tester"));
    }

    private JsonNode waitFor(String run, String... states) throws Exception {
        Set<String> want = Set.of(states);
        long until = System.currentTimeMillis() + 30_000;
        JsonNode s;
        do {
            s = get("/api/crossing/runs/" + run);
            if (want.contains(s.get("state").asText())) return s;
            Thread.sleep(150);
        } while (System.currentTimeMillis() < until);
        fail("Run " + run + " did not reach " + want + ": " + s);
        return s;
    }

    private Map<String, JsonNode> steps(String run, String contract) throws Exception {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (JsonNode n : get("/api/crossing/runs/" + run + "/contracts/" + contract)) m.put(n.get("step_id").asText(), n);
        return m;
    }

    private String outcome(JsonNode status, String contract) {
        for (JsonNode v : status.get("voyages")) if (v.get("contract_ref").asText().equals(contract)) return v.get("outcome").asText();
        return null;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void thePilotPausesThenTheRunCompletesWithEveryCallCheckedInTheDatabase() throws Exception {
        String batch = plannedBatch();
        String run = start(batch, 1, 1).get("run_id").asText();
        JsonNode paused = waitFor(run, "PAUSED");
        assertTrue(paused.get("reason").asText().contains("pilot"), paused.toString());
        assertEquals(1, paused.get("voyages").findValues("outcome").stream().filter(o -> o.asText().equals("SUCCESS")).count());

        post("/api/crossing/runs/" + run + "/continue", Map.of("performedBy", "tester"));
        JsonNode done = waitFor(run, "COMPLETED");
        assertEquals("SUCCESS", outcome(done, "B-1001"));
        assertEquals("SUCCESS", outcome(done, "R-2001"));

        Map<String, JsonNode> b = steps(run, "B-1001");
        assertEquals("SUCCESS", b.get("contract").get("outcome").asText());
        assertTrue(b.get("contract").get("read_back").asText().contains("\"verdict\":\"MATCH\""), b.get("contract").toString());
        assertEquals("LinkUserToContract", b.get("user:acme.admin").get("operation").asText());
        assertEquals("CreateVirtualUser", b.get("user:acme.maker").get("operation").asText());
        assertEquals(b.get("contract").get("target_id").asText(), om.readTree(b.get("user:acme.maker").get("request_json").asText()).get("contractId").asText(),
                "the user's contractId placeholder was filled from the register");

        JsonNode state = get("/api/mock/state");
        assertEquals(2, state.get("contract").asInt());
        assertEquals(3, state.get("contractaccounts").asInt());
        assertEquals(4, state.get("customer").asInt());
    }

    @Test
    void aSecondRunCreatesNothingTwice() throws Exception {
        String batch = plannedBatch();
        waitFor(start(batch, 0, 2).get("run_id").asText(), "COMPLETED");
        String again = start(batch, 0, 2).get("run_id").asText();
        JsonNode done = waitFor(again, "COMPLETED");
        assertEquals("SUCCESS", outcome(done, "B-1001"));
        assertTrue(steps(again, "B-1001").values().stream().allMatch(s -> s.get("outcome").asText().equals("ALREADY_DONE")));
        assertEquals(2, get("/api/mock/state").get("contract").asInt());
    }

    @Test
    void aNameClashFailsThatContractAndSkipsItsUsers() throws Exception {
        mock.behaviour().contractNameClashCifs = new ArrayList<>(List.of("045000100"));
        String batch = plannedBatch();
        JsonNode done = waitFor(start(batch, 0, 1).get("run_id").asText(), "COMPLETED");
        assertEquals("FAILED", outcome(done, "B-1001"));
        assertEquals("SUCCESS", outcome(done, "R-2001"));
        Map<String, JsonNode> b = steps(done.get("run_id").asText(), "B-1001");
        assertEquals("10401", b.get("contract").get("error_code").asText());
        assertTrue(b.get("contract").get("message").asText().contains("already exists with this name"), b.get("contract").toString());
        assertEquals("SKIPPED_DEPENDENCY", b.get("user:acme.maker").get("outcome").asText());
    }

    @Test
    void anAnswerWithoutAnIdIsRecoveredByReadBackAndAnUnknownOutcomePausesTheRun() throws Exception {
        mock.behaviour().successWithoutId = true;
        String batch = plannedBatch();
        JsonNode paused = waitFor(start(batch, 0, 1).get("run_id").asText(), "PAUSED");
        assertTrue(paused.get("reason").asText().contains("unknown"), paused.toString());
        Map<String, JsonNode> b = steps(paused.get("run_id").asText(), "B-1001");
        assertEquals("RECOVERED", b.get("contract").get("outcome").asText(), "the contract ID was found in the database");
        assertEquals("UNKNOWN", b.get("user:acme.admin").get("outcome").asText(), "no lookup is configured for users, so it stops instead of guessing");
    }

    @Test
    void aReadBackMismatchStopsTheRun() throws Exception {
        mock.behaviour().contractDropsAccounts = true;
        String batch = plannedBatch();
        JsonNode paused = waitFor(start(batch, 0, 1).get("run_id").asText(), "PAUSED");
        assertTrue(paused.get("reason").asText().contains("does not hold"), paused.toString());
        JsonNode contract = steps(paused.get("run_id").asText(), "B-1001").get("contract");
        assertEquals("MISMATCH", contract.get("outcome").asText());
        assertTrue(contract.get("message").asText().contains("Every account is on the contract: expected = 2, found 0"), contract.toString());
        assertEquals(1, paused.get("voyages").findValues("outcome").stream().filter(o -> o.asText().equals("QUEUED")).count(), "R-2001 was not sent");
    }

    @Test
    void aTimedOutCreateIsFoundInTheDatabaseInsteadOfBeingSentAgain() throws Exception {
        mock.behaviour().timeoutEvery = 1;
        mock.behaviour().slowMs = 3500;
        String batch = plannedBatch();
        JsonNode paused = waitFor(start(batch, 0, 1).get("run_id").asText(), "PAUSED");
        Map<String, JsonNode> b = steps(paused.get("run_id").asText(), "B-1001");
        assertEquals("RECOVERED", b.get("contract").get("outcome").asText(), b.get("contract").toString());
        assertEquals(1, b.get("contract").get("attempts").asInt(), "never re-sent");
        assertEquals(1, get("/api/mock/state").get("contract").asInt(), "exactly one contract exists");
    }

    @Test
    void throttlingIsRetriedAndTheRunStillCompletes() throws Exception {
        mock.behaviour().throttleEvery = 2;
        String batch = plannedBatch();
        JsonNode done = waitFor(start(batch, 0, 1).get("run_id").asText(), "COMPLETED");
        assertEquals("SUCCESS", outcome(done, "B-1001"));
        assertEquals("SUCCESS", outcome(done, "R-2001"));
        assertTrue(steps(done.get("run_id").asText(), "B-1001").values().stream().anyMatch(s -> s.get("attempts").asInt() > 1));
    }

    @Test
    void theSurveyFindsAnAccountThatAnotherContractAlreadyHolds() throws Exception {
        String batch = plannedBatch();
        JsonNode clean = post("/api/crossing/survey", Map.of("batchId", batch, "source", "MOCK", "performedBy", "tester"));
        assertEquals("GO", clean.get("verdict").asText(), clean.toString());

        new NamedParameterJdbcTemplate(mock.database()).update(
                "INSERT INTO contractaccounts (id, contractId, accountId, coreCustomerId, typeId) VALUES ('x', '999', '04510000010001', '9', 'C')", Map.of());
        JsonNode blocked = post("/api/crossing/survey", Map.of("batchId", batch, "source", "MOCK", "performedBy", "tester"));
        assertEquals("NO_GO", blocked.get("verdict").asText());
        assertEquals(List.of("B-1001"), om.convertValue(blocked.get("blockedContracts"), List.class));
    }

    @Test
    void onlyTheBuiltInMockCanBeUsedWhileLiveCallsAreOff() {
        var env = new com.dataferry.config.ConfigModel.TargetEnvironment();
        env.hostUrl = "https://digital-uat.example-bank.test";
        assertFalse(CrossingService.isBuiltInMock(env));
        env.hostUrl = "http://localhost:8095/mock";
        assertTrue(CrossingService.isBuiltInMock(env));
    }
}
