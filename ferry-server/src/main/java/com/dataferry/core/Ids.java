package com.dataferry.core;

import java.util.UUID;
import java.util.regex.Pattern;

/** Identifier helpers and guards. Anything that reaches SQL as a name goes through here. */
public final class Ids {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_.:-]{1,80}$");

    private Ids() {}

    public static String newRunId() { return UUID.randomUUID().toString(); }

    public static String newBatchId() {
        return "B-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    /** Batch ids, assumption ids, record types: short, plain keys only. */
    public static String requireKey(String value, String what) {
        if (value == null || !KEY.matcher(value).matches())
            throw new IllegalArgumentException("Invalid " + what + ": " + value);
        return value;
    }
}
