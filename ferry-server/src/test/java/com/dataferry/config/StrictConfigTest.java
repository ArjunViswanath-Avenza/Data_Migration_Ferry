package com.dataferry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A config key the code does not know must stop loading, not be silently ignored. */
class StrictConfigTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void unknownKeyIsRejected() {
        Exception e = assertThrows(Exception.class, () -> om.readValue(
                "{\"id\":\"dev\",\"liveCall\":true}", ConfigModel.TargetEnvironment.class));
        assertTrue(rootMessage(e).contains("Unknown key 'liveCall'"), rootMessage(e));
    }

    @Test
    void notesAreAllowedAndKept() throws Exception {
        ConfigModel.TargetEnvironment env = om.readValue(
                "{\"_comment\":\"keep me\",\"id\":\"dev\",\"liveCalls\":false}", ConfigModel.TargetEnvironment.class);
        assertEquals("dev", env.id);
        assertTrue(om.writeValueAsString(env).contains("\"_comment\":\"keep me\""));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}
