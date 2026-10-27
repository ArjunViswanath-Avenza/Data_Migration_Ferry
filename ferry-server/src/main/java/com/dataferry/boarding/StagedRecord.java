package com.dataferry.boarding;

import java.util.*;

/**
 * One source row in the canonical model. Values are always text: identifiers such as CIFs and
 * account numbers look numeric but are not, and turning them into numbers loses leading zeros.
 * The original row is kept in {@code raw} so every value can be traced back to the source.
 */
public class StagedRecord {

    public Long id;
    public String type;
    public int rowNum;
    public String key;
    public String contractRef;
    /** STAGED · VALID · INVALID · EXCLUDED */
    public String status = "STAGED";
    /** Assumption IDs this record relied on, e.g. A-01, A-09. */
    public SortedSet<String> tags = new TreeSet<>();
    public LinkedHashMap<String, String> data = new LinkedHashMap<>();
    public LinkedHashMap<String, String> raw = new LinkedHashMap<>();

    public StagedRecord() {}

    public StagedRecord(String type, int rowNum) {
        this.type = type;
        this.rowNum = rowNum;
    }

    /** Trimmed value, or null when missing or blank. */
    public String get(String field) {
        String v = data.get(field);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    public boolean has(String field) { return get(field) != null; }

    public void set(String field, String value) {
        if (value == null || value.isBlank()) data.remove(field);
        else data.put(field, value.trim());
    }

    /** Comma-separated values as a list, e.g. accountIds. */
    public List<String> list(String field) {
        String v = get(field);
        if (v == null) return List.of();
        return Arrays.stream(v.split("[,;|]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
