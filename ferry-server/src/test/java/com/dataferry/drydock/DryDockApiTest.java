package com.dataferry.drydock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"ferry.data-dir=target/test-data/drydock", "ferry.config-dir=../config"})
@AutoConfigureMockMvc
class DryDockApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void uploadCompareScriptAndReport() throws Exception {
        Path dir = Path.of("../samples/drydock");
        MockMultipartFile source = new MockMultipartFile("source", "source_customised.sql", "application/sql", Files.readAllBytes(dir.resolve("source_customised.sql")));
        MockMultipartFile target = new MockMultipartFile("target", "target_new_release.sql", "application/sql", Files.readAllBytes(dir.resolve("target_new_release.sql")));

        String body = mvc.perform(multipart("/api/drydock/compare").file(source).file(target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.tables").value(4))
                .andExpect(jsonPath("$.target.tables").value(4))
                .andExpect(jsonPath("$.sourceSchema").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        JsonNode c = json.readTree(body);
        String id = c.get("id").asText();
        assertTrue(c.get("differences").size() > 20);

        mvc.perform(get("/api/drydock/comparisons/" + id + "/tables/customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.name").value("customer"))
                .andExpect(jsonPath("$.target.columns.length()").value(10));

        mvc.perform(post("/api/drydock/comparisons/" + id + "/scripts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSchema\":\"bank_live\",\"targetSchema\":\"bank_next\",\"dataMode\":\"INSERT_IGNORE\",\"decisions\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refit").value(org.hamcrest.Matchers.containsString("ALTER TABLE `customer`")))
                .andExpect(jsonPath("$.dataMove").value(org.hamcrest.Matchers.containsString("INSERT IGNORE INTO `bank_next`.`customer`")));

        mvc.perform(get("/api/drydock/comparisons/" + id + "/report.xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("DryDock_" + id)));

        mvc.perform(get("/api/drydock/comparisons")).andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void theBundledSamplesCanBeCompared() throws Exception {
        mvc.perform(post("/api/drydock/compare-samples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.fileName").value("source_customised.sql"));
    }

    @Test
    void anUnknownComparisonSaysWhatToDo() throws Exception {
        mvc.perform(get("/api/drydock/comparisons/DD-NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Compare the files again")));
    }
}
