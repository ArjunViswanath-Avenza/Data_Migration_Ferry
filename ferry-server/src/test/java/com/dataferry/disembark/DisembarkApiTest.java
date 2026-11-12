package com.dataferry.disembark;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"ferry.data-dir=target/test-data/disembark", "ferry.config-dir=../config"})
@AutoConfigureMockMvc
class DisembarkApiTest {

    @Autowired MockMvc mvc;

    @Test
    void theProfileScriptAndSafetyGatesAreServed() throws Exception {
        mvc.perform(get("/api/disembark/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value("dbxdb"))
                .andExpect(jsonPath("$.allowExecute").value(false));

        mvc.perform(post("/api/disembark/script").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cif\":\"C100\",\"performedBy\":\"tester\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value(containsString("disembark_C100_")))
                .andExpect(jsonPath("$.sql").value(containsString("START TRANSACTION;")))
                .andExpect(jsonPath("$.preview").doesNotExist());

        mvc.perform(post("/api/disembark/preview").contentType(MediaType.APPLICATION_JSON).content("{\"cif\":\"C100\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("connection")));

        mvc.perform(post("/api/disembark/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cif\":\"C100\",\"confirmCif\":\"C100\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("switched off")));

        mvc.perform(post("/api/disembark/script").contentType(MediaType.APPLICATION_JSON).content("{\"cif\":\"bad key!\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/disembark/history")).andExpect(status().isOk()).andExpect(jsonPath("$[0].area").value("disembark"))
                .andExpect(jsonPath("$[0].details").value(containsString("C100")));
    }
}
