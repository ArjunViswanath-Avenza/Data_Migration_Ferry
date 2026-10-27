package com.dataferry.target;

import com.dataferry.boarding.BatchStore.Finding;
import com.dataferry.boarding.BatchView;
import com.dataferry.boarding.StagedRecord;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The boundary between the source-agnostic tool and one target system. Boarding asks the adapter
 * for the target's own checks and for the request each planned step would send; Crossing (build
 * step 3) will ask it to execute. Temenos DataMigration is the only adapter today.
 */
public interface TargetAdapter {

    String id();

    /**
     * Target-specific checks on a validated batch (after field and cross-record checks). Report each
     * problem through {@code findings}; mark a record INVALID by reporting an ERROR on it.
     */
    void check(BatchView batch, Consumer<FindingOnRecord> findings);

    /** Builds the ordered steps for one contract. */
    List<PlannedStep> plan(BatchView batch, StagedRecord contract);

    record FindingOnRecord(StagedRecord record, Finding finding) {}

    /**
     * One API call in a contract's voyage.
     *
     * @param status        PLANNED · BLOCKED · ALREADY_DONE (found in the ID register for this environment)
     * @param payload       the exact request body, with placeholders like {{CONTRACT:C-1001.contractId}}
     *                      for IDs that only exist once an earlier step has run
     */
    record PlannedStep(String stepId, String operation, String label, String subjectType, String subjectRef,
                       List<String> dependsOn, String status, List<String> reasons, List<String> assumptions,
                       Map<String, Object> payload, String registeredTargetId) {}
}
