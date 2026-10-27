package com.dataferry.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/** Small never-throwing JSON helper, so persistence code stays readable. */
@Component
public class Json {

    private final ObjectMapper om;

    public Json(ObjectMapper om) { this.om = om; }

    public ObjectMapper mapper() { return om; }

    public String write(Object o) {
        if (o == null) return null;
        try { return om.writeValueAsString(o); }
        catch (Exception e) { return String.valueOf(o); }
    }

    public Map<String, Object> readMap(String s) {
        if (s == null || s.isBlank()) return new LinkedHashMap<>();
        try { return om.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    public List<String> readStringList(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        try { return om.readValue(s, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    public <T> T read(String s, Class<T> type) {
        try { return om.readValue(s, type); }
        catch (Exception e) { throw new IllegalArgumentException("Could not read JSON as " + type.getSimpleName() + ": " + e.getMessage()); }
    }

    /** Compact JSON for values the target API expects as a JSON *string* (cifList, accounts, signatories …). */
    public String compact(Object o) {
        try { return om.writeValueAsString(o); }
        catch (Exception e) { throw new IllegalStateException("Could not serialise payload fragment: " + e.getMessage()); }
    }
}
