package com.dataferry.config;

import com.dataferry.config.ConfigModel.*;
import com.dataferry.core.FerryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads every JSON file in config/ into typed objects, checks them against each other, and
 * hands out the current snapshot. Reloadable without a restart. A broken file stops the load
 * with the file name and the problem, and the previous snapshot stays in force.
 */
@Component
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private final FerryProperties props;
    private final ObjectMapper om;
    private volatile Snapshot current;

    /** One consistent view of all configuration files. */
    public record Snapshot(Environments environments, TargetEnvironment environment, ReferenceData referenceData,
                           Assumptions assumptions, ValidationRules validationRules, TemenosTarget temenos,
                           Path configDir) {}

    public ConfigStore(FerryProperties props, ObjectMapper om) {
        this.props = props;
        this.om = om.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.current = load();
    }

    public Snapshot get() { return current; }

    public synchronized Snapshot reload() {
        this.current = load();
        return current;
    }

    public TargetEnvironment environment() { return current.environment(); }

    /** The option currently chosen for an assumption, e.g. "BLOCK" for A-08. */
    public String choice(String assumptionId) {
        return assumption(assumptionId).choice;
    }

    public Assumption assumption(String id) {
        return current.assumptions().assumptions.stream().filter(a -> a.id.equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Unknown assumption " + id + " in assumptions.json"));
    }

    /** Changes the choice for one assumption, writes assumptions.json back and reloads. */
    public synchronized Assumption updateAssumption(String id, String choice, String status, String note) {
        Assumption a = assumption(id);
        if (choice != null) {
            boolean known = a.options.stream().anyMatch(o -> o.value.equals(choice));
            if (!known) throw new IllegalArgumentException("'" + choice + "' is not an option for " + id
                    + ". Options: " + a.options.stream().map(o -> o.value).toList());
            a.choice = choice;
        }
        if (status != null) {
            if (!List.of("ASSUMED", "CONFIRMED", "OVERRIDDEN").contains(status))
                throw new IllegalArgumentException("Status must be ASSUMED, CONFIRMED or OVERRIDDEN");
            a.status = status;
        } else if (choice != null) {
            a.status = choice.equals(a.defaultChoice) ? "ASSUMED" : "OVERRIDDEN";
        }
        if (note != null) a.note = note;
        try {
            Path file = current.configDir().resolve("assumptions.json");
            Files.writeString(file, om.writeValueAsString(current.assumptions()) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write assumptions.json: " + e.getMessage());
        }
        reload();
        return assumption(id);
    }

    // ---------------- loading ----------------

    private Snapshot load() {
        Path dir = props.configPath();
        if (!Files.isDirectory(dir))
            throw new IllegalStateException("Config folder not found: " + dir + " (set FERRY_CONFIG_DIR)");

        Environments envs = read(dir, "environments.json", Environments.class);
        ReferenceData ref = read(dir, "reference-data.json", ReferenceData.class);
        Assumptions assumptions = read(dir, "assumptions.json", Assumptions.class);
        ValidationRules rules = read(dir, "validation-rules.json", ValidationRules.class);
        TemenosTarget temenos = read(dir, "target-temenos.json", TemenosTarget.class);

        String wanted = props.getEnvironment() == null || props.getEnvironment().isBlank()
                ? envs.active : props.getEnvironment();
        TargetEnvironment env = envs.environments.stream().filter(e -> e.id.equals(wanted)).findFirst()
                .orElseThrow(() -> new IllegalStateException("environments.json has no environment '" + wanted
                        + "'. Known: " + envs.environments.stream().map(e -> e.id).toList()));

        checkUnique("environments.json", envs.environments.stream().map(e -> e.id).toList());
        checkUnique("reference-data.json serviceDefinitions", ref.serviceDefinitions.stream().map(s -> s.ref).toList());
        checkUnique("reference-data.json roles", ref.roles.stream().map(r -> r.ref).toList());
        checkUnique("assumptions.json", assumptions.assumptions.stream().map(a -> a.id).toList());
        checkUnique("validation-rules.json", rules.rules.stream().map(r -> r.id).toList());
        for (Assumption a : assumptions.assumptions) {
            Set<String> values = new HashSet<>();
            a.options.forEach(o -> values.add(o.value));
            if (!values.contains(a.choice))
                throw new IllegalStateException("assumptions.json " + a.id + ": choice '" + a.choice + "' is not one of " + values);
            if (!values.contains(a.defaultChoice))
                throw new IllegalStateException("assumptions.json " + a.id + ": defaultChoice '" + a.defaultChoice + "' is not one of " + values);
        }

        log.info("Config loaded from {}: environment={} (liveCalls={}), {} service definitions, {} roles, {} assumptions, {} validation rules",
                dir, env.id, env.liveCalls, ref.serviceDefinitions.size(), ref.roles.size(),
                assumptions.assumptions.size(), rules.rules.size());
        return new Snapshot(envs, env, ref, assumptions, rules, temenos, dir);
    }

    private <T> T read(Path dir, String name, Class<T> type) {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) throw new IllegalStateException("Missing config file: " + file);
        try {
            return om.readValue(file.toFile(), type);
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            throw new IllegalStateException("Cannot read config/" + name + ": " + root.getMessage(), e);
        }
    }

    private static void checkUnique(String where, List<String> ids) {
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) throw new IllegalStateException(where + ": an entry has no id/ref");
            if (!seen.add(id)) throw new IllegalStateException(where + ": duplicate id/ref '" + id + "'");
        }
    }
}
