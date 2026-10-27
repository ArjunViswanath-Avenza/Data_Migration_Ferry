package com.dataferry.platform;

import com.dataferry.register.IdRegister;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Starts the whole application against the repository's real config/ folder. */
@SpringBootTest(properties = {"ferry.data-dir=target/test-data/platform", "ferry.config-dir=../config"})
@AutoConfigureMockMvc
class PlatformSmokeTest {

    @Autowired MockMvc mvc;
    @Autowired IdRegister register;

    @Test
    void statusShowsTheTargetAndThatLiveCallsAreOff() throws Exception {
        mvc.perform(get("/api/platform/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("Data Migration Ferry"))
                .andExpect(jsonPath("$.environment.id").value("local-mock"))
                .andExpect(jsonPath("$.environment.liveCalls").value(false))
                .andExpect(jsonPath("$.assumptions.total").value(12));
    }

    @Test
    void anUnknownAssumptionOptionIsRefused() throws Exception {
        mvc.perform(put("/api/assumptions/A-08").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"MAYBE\",\"performedBy\":\"test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("not an option")));
    }

    @Test
    void registerSkipsDuplicatesAndRefusesASecondTargetId() {
        String ref = "C-" + System.nanoTime();
        register.register("CONTRACT", ref, "contractId", "9000000001", "B-TEST", "run-1");
        register.register("CONTRACT", ref, "contractId", "9000000001", "B-TEST", "run-2");   // same pair: harmless
        assertEquals("9000000001", register.find("CONTRACT", ref).orElseThrow());
        assertThrows(IllegalStateException.class,
                () -> register.register("CONTRACT", ref, "contractId", "9000000002", "B-TEST", "run-3"));
    }
}
