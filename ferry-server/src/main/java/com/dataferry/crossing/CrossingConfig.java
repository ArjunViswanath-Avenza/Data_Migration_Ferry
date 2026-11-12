package com.dataferry.crossing;

import com.dataferry.config.ConfigModel.Strict;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** config/crossing.json, parsed strictly and read at the start of every run. */
public class CrossingConfig extends Strict {

    public int pilotContracts = 3;
    public StopRules stopRules = new StopRules();
    public Retry retry = new Retry();
    public int requestTimeoutSeconds = 60;
    public int tokenRenewBeforeSeconds = 120;
    public int defaultTokenLifetimeSeconds = 1200;
    public ResultIds resultIds = new ResultIds();
    public ReadBackChecks readBack = new ReadBackChecks();

    public static class StopRules extends Strict {
        public int minVoyagesBeforeShare = 5;
        public double maxFailedShare = 0.2;
        public int maxConsecutiveFailures = 5;
        public boolean stopOnReadBackMismatch = true;
        public boolean stopOnUnknownOutcome = true;
    }

    public static class Retry extends Strict {
        public int attempts = 3;
        public List<Integer> backoffSeconds = new ArrayList<>(List.of(2, 5, 15));
    }

    /** operation -> response fields that may hold the new ID, in order. */
    public static class ResultIds extends Strict {
        public final Map<String, List<String>> byOperation = new LinkedHashMap<>();

        @Override
        public void unknown(String key, Object value) {
            if (key.startsWith("_")) { super.unknown(key, value); return; }
            if (!(value instanceof List<?> l)) throw new IllegalArgumentException("resultIds." + key + " must be a list of field names");
            byOperation.put(key, l.stream().map(String::valueOf).toList());
        }
    }

    public static class Check extends Strict {
        public String label;
        public String sql;
        public String expect;
    }

    public static class OperationChecks extends Strict {
        public String lookup;
        public List<Check> verify = new ArrayList<>();
    }

    /** operation -> lookup and verify queries. */
    public static class ReadBackChecks extends Strict {
        public final Map<String, OperationChecks> byOperation = new LinkedHashMap<>();
        private static final ObjectMapper OM = new ObjectMapper();

        @Override
        public void unknown(String key, Object value) {
            if (key.startsWith("_")) { super.unknown(key, value); return; }
            byOperation.put(key, OM.convertValue(value, OperationChecks.class));
        }
    }

    static CrossingConfig load(Path file, ObjectMapper om) {
        if (!Files.exists(file)) return new CrossingConfig();
        try {
            CrossingConfig c = om.readValue(file.toFile(), CrossingConfig.class);
            for (Map.Entry<String, OperationChecks> e : c.readBack.byOperation.entrySet())
                for (Check k : e.getValue().verify)
                    if (k.sql == null || k.expect == null || !k.expect.matches("(=|>=|<=)\\s*(:\\w+|\\d+)"))
                        throw new IllegalArgumentException("readBack." + e.getKey() + ": each check needs sql and an expect like '= 1', '>= 1' or '= :accountCount'");
            if (c.pilotContracts < 0) throw new IllegalArgumentException("pilotContracts cannot be negative");
            return c;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("config/crossing.json: " + e.getMessage());
        } catch (Exception e) {
            Throwable t = e;
            while (t.getCause() != null) t = t.getCause();
            throw new IllegalStateException("config/crossing.json: " + t.getMessage());
        }
    }
}
