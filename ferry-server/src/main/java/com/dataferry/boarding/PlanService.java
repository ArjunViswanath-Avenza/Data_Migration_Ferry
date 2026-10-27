package com.dataferry.boarding;

import com.dataferry.store.RunStore;
import com.dataferry.target.TargetAdapter;
import com.dataferry.target.TargetAdapter.PlannedStep;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Boarding, part three: the dry run. For every contract, the ordered steps Crossing will take and the
 * exact request each one would send. Nothing is sent. The plan is stored so Crossing reads the same
 * steps that were reviewed here.
 *
 * Voyage status: READY (every step can run or is already done) · PARTIAL (the contract can be created,
 * some users or groups cannot) · BLOCKED (the contract itself cannot be created).
 */
@Service
public class PlanService {

    private final BatchStore store;
    private final CanonicalModel model;
    private final TargetAdapter target;
    private final RunStore runs;

    public PlanService(BatchStore store, CanonicalModel model, TargetAdapter target, RunStore runs) {
        this.store = store;
        this.model = model;
        this.target = target;
        this.runs = runs;
    }

    public record Summary(String batchId, String runId, String target, int contracts, Map<String, Integer> byStatus,
                          Map<String, Integer> stepsByOperation, Map<String, Integer> stepsByStatus,
                          List<Map<String, Object>> voyages) {}

    public Summary plan(String batchId, String by) {
        Map<String, Object> batch = store.batch(batchId);
        String status = String.valueOf(batch.get("status"));
        if (!"VALIDATED".equals(status) && !"PLANNED".equals(status))
            throw new IllegalStateException("Validate batch " + batchId + " before planning (it is " + status + ").");

        String runId = runs.start("boarding", "PLAN", batchId, by);
        try {
            BatchView view = new BatchView(batchId, store.allRecords(batchId, model.types()));
            List<Map<String, Object>> voyages = new ArrayList<>();
            Map<String, Integer> byStatus = new TreeMap<>(), byOp = new LinkedHashMap<>(), byStepStatus = new TreeMap<>();

            for (StagedRecord contract : view.contracts.values()) {
                List<PlannedStep> steps = target.plan(view, contract);
                boolean contractBlocked = "BLOCKED".equals(steps.get(0).status());
                boolean anyBlocked = steps.stream().anyMatch(s -> "BLOCKED".equals(s.status()));
                String vs = contractBlocked ? "BLOCKED" : anyBlocked ? "PARTIAL" : "READY";

                Set<String> assumptions = new TreeSet<>();
                steps.forEach(s -> assumptions.addAll(s.assumptions()));
                List<String> excluded = view.accounts(contract.get("contractRef")).stream()
                        .filter(a -> "EXCLUDED".equals(a.status)).map(a -> a.get("accountId")).toList();

                Map<String, Object> v = new LinkedHashMap<>();
                v.put("contractRef", contract.get("contractRef"));
                v.put("primaryCif", contract.get("primaryCif"));
                v.put("serviceDefinitionRef", contract.get("serviceDefinitionRef"));
                v.put("status", vs);
                v.put("assumptions", assumptions);
                v.put("excludedAccounts", excluded);
                v.put("steps", steps);
                voyages.add(v);

                byStatus.merge(vs, 1, Integer::sum);
                steps.forEach(s -> { byOp.merge(s.operation(), 1, Integer::sum); byStepStatus.merge(s.status(), 1, Integer::sum); });
            }

            store.savePlans(batchId, voyages);
            runs.finish(runId, "SUCCESS", voyages.size(), byStatus.getOrDefault("READY", 0),
                    byStatus.getOrDefault("BLOCKED", 0), Map.of("byStatus", byStatus, "stepsByOperation", byOp));
            return new Summary(batchId, runId, target.id(), voyages.size(), byStatus, byOp, byStepStatus, store.plans(batchId, null));
        } catch (RuntimeException e) {
            runs.fail(runId, e.getMessage());
            throw e;
        }
    }
}
