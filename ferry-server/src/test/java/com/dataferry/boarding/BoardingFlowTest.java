package com.dataferry.boarding;

import com.dataferry.register.IdRegister;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole Boarding flow on samples/synthetic: upload, validate, plan. Every expectation below is
 * the one samples/README.md promises.
 */
@SpringBootTest(properties = {"ferry.data-dir=target/test-data/boarding", "ferry.config-dir=../config"})
@AutoConfigureMockMvc
class BoardingFlowTest {

    private static final Path SAMPLES = Path.of("..", "samples");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired IdRegister register;

    private JsonNode call(org.springframework.test.web.servlet.RequestBuilder req) throws Exception {
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(body);
    }

    private String newBatch(String sourceSystem) throws Exception {
        Map<String, String> body = new HashMap<>(Map.of("name", "test " + System.nanoTime(), "performedBy", "test"));
        if (sourceSystem != null) body.put("sourceSystem", sourceSystem);
        return call(post("/api/boarding/batches").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body))).get("batch_id").asText();
    }

    private JsonNode upload(String batchId, String file, String type) throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", file, "text/csv", Files.readAllBytes(SAMPLES.resolve(file)));
        return call(multipart("/api/boarding/batches/" + batchId + "/files").file(f)
                .param("recordType", type).param("performedBy", "test"));
    }

    private Map<String, JsonNode> stagedSampleBatch(String batchId) throws Exception {
        upload(batchId, "synthetic/contracts.csv", "CONTRACT");
        upload(batchId, "synthetic/accounts.csv", "ACCOUNT");
        upload(batchId, "synthetic/users.csv", "USER");
        upload(batchId, "synthetic/signatory_groups.csv", "SIGNATORY_GROUP");
        call(post("/api/boarding/batches/" + batchId + "/validate").contentType(MediaType.APPLICATION_JSON).content("{\"performedBy\":\"test\"}"));
        JsonNode plan = call(post("/api/boarding/batches/" + batchId + "/plan").contentType(MediaType.APPLICATION_JSON).content("{\"performedBy\":\"test\"}"));
        Map<String, JsonNode> byContract = new LinkedHashMap<>();
        plan.get("voyages").forEach(v -> byContract.put(v.get("contractRef").asText(), v));
        return byContract;
    }

    private static JsonNode step(JsonNode voyage, String stepId) {
        for (JsonNode s : voyage.get("steps")) if (s.get("stepId").asText().equals(stepId)) return s;
        throw new AssertionError("No step " + stepId + " in " + voyage.get("contractRef"));
    }

    @Test
    void sampleBatchProducesTheDocumentedVoyages() throws Exception {
        String batch = newBatch(null);
        Map<String, JsonNode> v = stagedSampleBatch(batch);

        assertEquals(Map.of("R-001", "PARTIAL", "R-002", "READY", "B-100", "PARTIAL", "B-200", "PARTIAL",
                        "R-003", "BLOCKED", "R-004", "BLOCKED"),
                Map.of("R-001", v.get("R-001").get("status").asText(), "R-002", v.get("R-002").get("status").asText(),
                        "B-100", v.get("B-100").get("status").asText(), "B-200", v.get("B-200").get("status").asText(),
                        "R-003", v.get("R-003").get("status").asText(), "R-004", v.get("R-004").get("status").asText()));

        // B-100: two CIFs with leading zeros, the joint account left out, sent as a JSON string
        JsonNode create = step(v.get("B-100"), "contract");
        assertEquals("CreateContract", create.get("operation").asText());
        String cifList = create.get("payload").get("cifList").asText();
        assertEquals("[{\"cif\":\"045000100\",\"accounts\":[{\"accountId\":\"04510000010001\"}]},"
                + "{\"cif\":\"060000200\",\"accounts\":[{\"accountId\":\"06010000020001\"}]}]", cifList);
        assertEquals("sd-business-example", create.get("payload").get("serviceDefinitionId").asText());
        assertEquals("LE0001", create.get("payload").get("legalEntityId").asText());
        assertTrue(create.get("payload").get("communicationDetails").asText().contains("finance@acme.example.test"));
        assertEquals("[\"A-09\"]", create.get("assumptions").toString());

        // Users: linked by CIF, complete virtual user planned, incomplete virtual user blocked (A-08)
        JsonNode admin = step(v.get("B-100"), "user:U-B100-A");
        assertEquals("LinkUserToContract", admin.get("operation").asText());
        assertEquals("045000100", admin.get("payload").get("cif").asText());
        assertEquals("{{CONTRACT:B-100.contractId}}", admin.get("payload").get("contractId").asText());
        assertEquals("GROUP_ADMINISTRATOR", admin.get("payload").get("roleId").asText());
        JsonNode maker = step(v.get("B-100"), "user:U-B100-M");
        assertEquals("CreateVirtualUser", maker.get("operation").asText());
        assertEquals("PLANNED", maker.get("status").asText());
        assertEquals("[{\"accountId\":\"04510000010001\"}]", maker.get("payload").get("accounts").asText());
        assertEquals("1990-04-12", maker.get("payload").get("dob").asText());
        JsonNode checker = step(v.get("B-100"), "user:U-B100-C");
        assertEquals("BLOCKED", checker.get("status").asText());
        assertTrue(checker.get("assumptions").toString().contains("A-08"));

        // Groups: members become {{USER:…}} placeholders; a group whose member is blocked is blocked
        JsonNode groupA = step(v.get("B-100"), "group:G-ACME-A");
        assertEquals("PLANNED", groupA.get("status").asText());
        assertEquals("[{\"userId\":\"{{USER:U-B100-A.userId}}\"},{\"userId\":\"{{USER:U-B100-M.userId}}\"}]",
                groupA.get("payload").get("signatories").asText());
        assertEquals("BLOCKED", step(v.get("B-100"), "group:G-ACME-B").get("status").asText());

        // R-002: joint account excluded, contract still ready, no communication details sent
        assertEquals("[\"09990000099901\"]", v.get("R-002").get("excludedAccounts").toString());
        assertFalse(step(v.get("R-002"), "contract").get("payload").has("communicationDetails"));

        // Findings carry the rule that fired
        JsonNode findings = call(get("/api/boarding/batches/" + batch + "/findings"));
        Set<String> rules = new TreeSet<>();
        findings.get("findings").forEach(f -> rules.add(f.get("rule_id").asText()));
        assertTrue(rules.containsAll(List.of("XR_ROLE", "XR_GROUP_RETAIL", "XR_JOINT_ACCOUNT", "TM_VIRTUAL_USER_FIELDS",
                "XR_LINKED_NEEDS_CIF", "TM_GROUP_NAME_DUPLICATE", "TM_ONE_GROUP_PER_USER", "XR_PRIMARY_CIF_ACCOUNT",
                "XR_SERVICE_DEFINITION", "XR_USER_CONTRACT")), rules.toString());

        // The assumptions register can list every record that relied on A-08
        JsonNode tagged = call(get("/api/boarding/assumptions/A-08/records"));
        assertTrue(tagged.toString().contains("U-B100-C"));
    }

    @Test
    void aContractAlreadyInTheRegisterIsNotPlannedAgain() throws Exception {
        String batch = newBatch(null);
        register.register("CONTRACT", "R-002", "contractId", "7700000002", batch, "earlier-run");
        Map<String, JsonNode> v = stagedSampleBatch(batch);
        JsonNode create = step(v.get("R-002"), "contract");
        assertEquals("ALREADY_DONE", create.get("status").asText());
        assertEquals("7700000002", create.get("registeredTargetId").asText());
    }

    @Test
    void reuploadingSendsTheBatchBackToStagedAndPlanningRefusesUntilValidated() throws Exception {
        String batch = newBatch(null);
        stagedSampleBatch(batch);
        upload(batch, "synthetic/users.csv", "USER");
        mvc.perform(post("/api/boarding/batches/" + batch + "/plan").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void theTemplateWorkbookUploadsInOneGo() throws Exception {
        byte[] template = mvc.perform(get("/api/boarding/templates/workbook.xlsx")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        byte[] filled;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(template)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            assertEquals("Contract Ref *", wb.getSheet("Contracts").getRow(0).getCell(0).getStringCellValue());
            fill(wb.getSheet("Contracts"), "R-9", "RETAIL", "009000009");
            fill(wb.getSheet("Accounts"), "R-9", "009000009", "00900000000901");
            fill(wb.getSheet("Users"), "U-9", "R-9", "USER", "", "009000009");
            wb.write(out);
            filled = out.toByteArray();
        }
        String batch = newBatch(null);
        MockMultipartFile f = new MockMultipartFile("file", "filled.xlsx", "application/octet-stream", filled);
        JsonNode res = call(multipart("/api/boarding/batches/" + batch + "/files").file(f).param("performedBy", "test"));
        Set<String> types = new TreeSet<>();
        res.get("staged").forEach(s -> types.add(s.get("recordType").asText()));
        assertEquals(Set.of("ACCOUNT", "CONTRACT", "USER"), types, "empty Signatory Groups sheet is not staged");
        call(post("/api/boarding/batches/" + batch + "/validate").contentType(MediaType.APPLICATION_JSON).content("{}"));
        JsonNode plan = call(post("/api/boarding/batches/" + batch + "/plan").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertEquals("READY", plan.get("voyages").get(0).get("status").asText());
        assertEquals("[{\"cif\":\"009000009\",\"accounts\":[{\"accountId\":\"00900000000901\"}]}]",
                plan.get("voyages").get(0).get("steps").get(0).get("payload").get("cifList").asText());
    }

    @Test
    void aLegacyExportMapsThroughItsProfile() throws Exception {
        String batch = newBatch("LEGACYCORE");
        JsonNode res = upload(batch, "legacy-core/users_export.csv", "USER");
        JsonNode staged = res.get("staged").get(0);
        assertEquals("example-legacycore-user", staged.get("profileId").asText());
        assertEquals("[\"BRANCH_CODE\"]", staged.get("unmappedColumns").toString());
        JsonNode anna = staged.get("sample").get(0), mya = staged.get("sample").get(1);
        assertEquals("anna.lee@example.test", anna.get("email").asText());
        assertEquals("9123456701", anna.get("phone").asText());
        assertEquals("1985-03-02", anna.get("dateOfBirth").asText());
        assertEquals("LINKED", anna.get("userType").asText());
        assertEquals("USER", anna.get("roleRef").asText());
        assertEquals("VIRTUAL", mya.get("userType").asText());
        assertEquals("ADMIN", mya.get("roleRef").asText());
        assertEquals("95", mya.get("phoneCountryCode").asText());
    }

    private static void fill(Sheet s, String... values) {
        var row = s.createRow(1);
        for (int i = 0; i < values.length; i++) if (!values[i].isEmpty()) row.createCell(i).setCellValue(values[i]);
    }
}
