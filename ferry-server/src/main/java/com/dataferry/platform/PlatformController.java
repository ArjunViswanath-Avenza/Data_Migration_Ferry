package com.dataferry.platform;

import com.dataferry.config.ConfigModel.Assumption;
import com.dataferry.config.ConfigModel.TargetEnvironment;
import com.dataferry.config.ConfigStore;
import com.dataferry.core.FerryProperties;
import com.dataferry.core.Ids;
import com.dataferry.register.IdRegister;
import com.dataferry.store.AuditLog;
import com.dataferry.store.RunStore;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Environment, configuration, assumptions, run history, audit and ID register. */
@RestController
@RequestMapping("/api")
public class PlatformController {

    private final ConfigStore config;
    private final FerryProperties props;
    private final RunStore runs;
    private final AuditLog audit;
    private final IdRegister register;

    public PlatformController(ConfigStore config, FerryProperties props, RunStore runs, AuditLog audit, IdRegister register) {
        this.config = config;
        this.props = props;
        this.runs = runs;
        this.audit = audit;
        this.register = register;
    }

    /** What the console header shows on every screen: which target, and whether it can be written to. */
    @GetMapping("/platform/status")
    public Map<String, Object> status() {
        ConfigStore.Snapshot s = config.get();
        TargetEnvironment env = s.environment();
        Map<String, Long> byStatus = new TreeMap<>();
        s.assumptions().assumptions.forEach(a -> byStatus.merge(a.status, 1L, Long::sum));

        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("id", env.id);
        environment.put("label", env.label);
        environment.put("target", env.target);
        environment.put("hostUrl", env.hostUrl);
        environment.put("defaultLegalEntityId", env.defaultLegalEntityId);
        environment.put("liveCalls", env.liveCalls);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("application", "Data Migration Ferry");
        out.put("version", props.getVersion());
        out.put("environment", environment);
        out.put("configDir", s.configDir().toString());
        out.put("dataDir", props.dataPath().toString());
        out.put("assumptions", Map.of("total", s.assumptions().assumptions.size(), "byStatus", byStatus));
        return out;
    }

    @GetMapping("/platform/environments")
    public List<TargetEnvironment> environments() { return config.get().environments().environments; }

    @PostMapping("/platform/config/reload")
    public Map<String, Object> reload(@RequestBody(required = false) Map<String, String> body) {
        config.reload();
        audit.record(body == null ? null : body.get("performedBy"), "CONFIG_RELOAD", config.get().configDir().toString(), Map.of());
        return status();
    }

    @GetMapping("/reference-data")
    public Object referenceData() { return config.get().referenceData(); }

    @GetMapping("/assumptions")
    public List<Assumption> assumptions() { return config.get().assumptions().assumptions; }

    public record AssumptionChange(String choice, String status, String note, String performedBy) {}

    @PutMapping("/assumptions/{id}")
    public Assumption changeAssumption(@PathVariable String id, @RequestBody AssumptionChange change) {
        Ids.requireKey(id, "assumption id");
        Assumption before = config.assumption(id);
        String oldChoice = before.choice, oldStatus = before.status;
        Assumption after = config.updateAssumption(id, change.choice(), change.status(), change.note());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("choice", Map.of("from", oldChoice, "to", after.choice));
        details.put("status", Map.of("from", oldStatus, "to", after.status));
        if (change.note() != null) details.put("note", change.note());
        audit.record(change.performedBy(), "ASSUMPTION_CHANGED", id, details);
        return after;
    }

    @GetMapping("/platform/runs")
    public List<Map<String, Object>> runs(@RequestParam(required = false) String area,
                                          @RequestParam(defaultValue = "50") int limit) {
        return runs.recent(area, limit);
    }

    @GetMapping("/platform/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "50") int limit) { return audit.recent(limit); }

    @GetMapping("/register")
    public List<Map<String, Object>> register(@RequestParam(required = false) String sourceType,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "200") int limit) {
        return register.list(sourceType, q, limit);
    }

    @GetMapping("/register/counts")
    public Map<String, Object> registerCounts() { return register.counts(); }
}
