package com.dataferry.boarding;

import java.util.*;

/**
 * A batch loaded into memory and indexed the way validation and planning need it: contracts in
 * source order, and each contract's accounts, users and signatory-group rows.
 */
public class BatchView {

    public final String batchId;
    public final Map<String, List<StagedRecord>> byType;
    public final LinkedHashMap<String, StagedRecord> contracts = new LinkedHashMap<>();
    public final Map<String, List<StagedRecord>> accountsByContract = new HashMap<>();
    public final Map<String, List<StagedRecord>> usersByContract = new HashMap<>();
    public final Map<String, StagedRecord> usersByRef = new HashMap<>();
    /** contractRef -> groupRef -> member rows, in source order */
    public final Map<String, LinkedHashMap<String, List<StagedRecord>>> groupsByContract = new HashMap<>();

    public BatchView(String batchId, Map<String, List<StagedRecord>> byType) {
        this.batchId = batchId;
        this.byType = byType;
        for (StagedRecord c : list("CONTRACT")) {
            String ref = c.get("contractRef");
            if (ref != null) contracts.putIfAbsent(ref, c);       // duplicates are reported by CONTRACT_UNIQUE
        }
        for (StagedRecord a : list("ACCOUNT"))
            accountsByContract.computeIfAbsent(String.valueOf(a.get("contractRef")), k -> new ArrayList<>()).add(a);
        for (StagedRecord u : list("USER")) {
            usersByContract.computeIfAbsent(String.valueOf(u.get("contractRef")), k -> new ArrayList<>()).add(u);
            if (u.get("userRef") != null) usersByRef.putIfAbsent(u.get("userRef"), u);
        }
        for (StagedRecord g : list("SIGNATORY_GROUP"))
            groupsByContract.computeIfAbsent(String.valueOf(g.get("contractRef")), k -> new LinkedHashMap<>())
                    .computeIfAbsent(String.valueOf(g.get("groupRef")), k -> new ArrayList<>()).add(g);
    }

    public List<StagedRecord> list(String type) { return byType.getOrDefault(type, List.of()); }

    public List<StagedRecord> all() {
        List<StagedRecord> out = new ArrayList<>();
        byType.values().forEach(out::addAll);
        return out;
    }

    public List<StagedRecord> accounts(String contractRef) { return accountsByContract.getOrDefault(contractRef, List.of()); }

    public List<StagedRecord> users(String contractRef) { return usersByContract.getOrDefault(contractRef, List.of()); }

    public LinkedHashMap<String, List<StagedRecord>> groups(String contractRef) {
        return groupsByContract.getOrDefault(contractRef, new LinkedHashMap<>());
    }

    /** Accounts that can go into the contract: not invalid, not excluded. */
    public List<StagedRecord> usableAccounts(String contractRef) {
        return accounts(contractRef).stream().filter(a -> !"INVALID".equals(a.status) && !"EXCLUDED".equals(a.status)).toList();
    }
}
