# Data Migration Ferry — Design (v0.1, draft for review)

A standalone, one-stop tool that carries customer data from **any source system** into
**Temenos Digital** through the product's own `DataMigration` APIs, and proves what arrived.
Nothing in the tool is specific to one bank: every bank-specific value lives in configuration.

## 1. Principles

1. **Source-agnostic, target-pluggable.** Any core banking export (Excel, CSV, database extract)
   maps onto one canonical model. The target is an adapter; Temenos `DataMigration` is the first one.
2. **Config over code.** Field mappings, service definitions, roles, action lists, payload shapes,
   success rules and assumptions are configuration, never Java.
3. **Nothing destructive by default.** Dry run first. Live calls need a per-environment switch
   *and* an explicit confirmation. The screen always shows which environment is targeted.
4. **Every item is evidence.** Each record, each API step and each generated ID is stored, so a
   run can be explained line by line at sign-off.
5. **Unknowns are explicit.** Where the vendor material is unclear, the tool uses a named, switchable
   assumption (section 6) and tags every record that relied on it.

## 2. The four areas (screens)

| Area | What it does |
|---|---|
| **Boarding** | Load source files, map them to the canonical model, validate, check prerequisites in the target, and build a dry-run plan per contract. |
| **Crossing** | Execute the plan: ordered API calls per contract, in parallel across contracts, with retries and live progress. |
| **Manifest** | Results per item and step, the ID register, the vendor reconciliation report, the exception log, and export to Excel. |
| **Return Crossing** | Roll back selected contracts or users through the vendor delete API, with a preview first. |

Settings (environments, mappings, assumptions) sit alongside these four.

## 3. Canonical model

The unit of work is a **contract** (one "voyage"), because every later call depends on its ID.

| Entity | Key fields | Target API |
|---|---|---|
| Contract | sourceContractRef, legalEntityId, serviceDefinitionRef, primaryCif, CIFs (each with accounts), communication | CreateContract |
| User | sourceUserRef, contract, role, accounts; **linked** (has own CIF) or **virtual** (personal details) | LinkUserToContract / CreateVirtualUser |
| Limits | contract-level per CIF / user-level per account; feature, action, limit type, value | UpdateContractLimits / UpdateUserLimits |
| Signatory group | sourceGroupRef, name, description, member users | CreateSignatoryGroup |
| Approval setup | mode (user or group) per contract; rules per action and limit type, with amount brackets | UpdateApprovalMatrixMode / CreateApprovalMatrixRule |
| Payee | same-bank, domestic, international, P2P, bill pay | CreatePayee / CreateP2PPayee / CreateBillPayPayee |

Legacy maker/checker rules can be loaded as-is and converted into approval rules by a
**rule converter** module (bracket normalisation, user vs group mode, safe consolidation).

## 4. How a run works

1. **Plan.** For each contract, build the step graph: contract → users → limits → signatory groups →
   approval mode → approval rules → payees. Each step lists the IDs it needs from earlier steps.
2. **ID register.** Every source ID is recorded against the target ID it produced (source CIF → contractId,
   source login → userId, source group → signatoryGroupId). Later steps read IDs from here, and a rerun
   skips anything already created, so nothing is created twice.
3. **Execute.** Contracts run in parallel (default 4, configurable); steps inside a contract run in order.
   The login token is refreshed before it expires. Only timeouts and expired tokens are retried; data
   errors are never retried automatically.
4. **Judge.** A step succeeds only when the response says so (default: `opstatus` 0 and no `dbpErrCode`).
   HTTP 200 alone is not success. The vendor error code and message are kept with the step.
5. **Prove.** After a run: per-item results, the vendor reconciliation report against expected counts,
   and the vendor exception log, all exportable.

## 5. Safety gates

- Live calls refuse unless the environment's `liveCalls` switch is on **and** the run is confirmed.
- Credentials are entered at run time and never written to disk or logs.
- Rollback always previews first and is limited to IDs the tool itself created (from the ID register).
- Every run, rollback and setting change is recorded with who did it and when.

## 6. Assumptions register

Each open question becomes an assumption with a default, alternatives and the steps it affects.
Changing one lists every record that relied on it, so those can be re-checked or re-run.

| ID | Question | Default | Switch to |
|---|---|---|---|
| A-01 | Can the target read customers from the source core? | Per record: linked user if the source gives the user a CIF, else virtual user | Always virtual |
| A-02 | `groupRule` format | 1/0 flag per group (vendor worked example); "N from the same group" parked | Count per group |
| A-03 | Amount bracket boundaries | Neighbouring brackets share the boundary value (vendor examples) | Next lower = previous upper + 1 |
| A-04 | Ranges that need no approval | Not sent | Send `NO_APPROVAL` |
| A-05 | "All transactions" rules | Expanded to the action list configured per service definition | — |
| A-06 | Rules for actions that move no money | Parked for review | Send with configured limit type |
| A-07 | Contract-name clash (vendor error 10401) | Own outcome, no automatic retry | — |
| A-08 | Virtual user missing a "mandatory" field | Record blocked | Send blank |
| A-09 | Account under more than one contract (joint) | Excluded and listed | Include |
| A-10 | What counts as success | `opstatus` 0 and no `dbpErrCode` | Configurable fields |
| A-11 | Safe parallelism | 4 contracts at a time | Any number |
| A-12 | Rollback | Vendor `DeleteMigratedData` | — |

Status per assumption: **Assumed** (our default), **Confirmed** (vendor or bank answered), **Overridden**.

## 7. Configuration files

| File | Holds |
|---|---|
| `environments.json` | Target URLs, legal entity IDs, live switch per environment (no secrets) |
| `mapping-profiles/*.json` | Source columns → canonical fields, per source system and record type |
| `reference-data.json` | Service definitions, roles, action lists, limit types, source-code → target-code maps |
| `target-temenos.json` | API paths, payload templates, response success rules, error-code catalogue |
| `validation-rules.json` | Pre-run checks per entity |
| `assumptions.json` | The register in section 6 and current choices |

## 8. Technology and layout

Java 17 + Spring Boot 3 backend, React + Vite console, embedded H2 for the run store and ID register
(switchable to MySQL or PostgreSQL). Single-user local first; authentication added before shared use.

```
Data_Migration_Ferry/
├── docs/                 design, user guide
├── ferry-server/         Spring Boot: boarding, crossing, manifest, return-crossing, target adapters
├── ferry-console/        React console: one folder per module (see CONSOLE.md)
├── config/               the JSON files in section 7, with neutral examples
└── samples/              neutral source templates and synthetic test data
```

## 9. Build order

1. **Skeleton.** Repo layout, config loading, environment banner, run store, ID register.
2. **Boarding.** Templates, file reading (identifiers kept as text), mapping, validation, dry-run plan.
3. **Crossing for contracts and users.** Token handling, step graph, execution, success judging, retries.
4. **Manifest.** Results, Excel export, vendor reconciliation and exception log.
5. **Corporate setup.** Signatory groups, approval mode, approval rules, rule converter.
6. **Limits and payees.**
7. **Return Crossing.** Preview and vendor delete, limited to registered IDs.
8. **Mock target.** A local stand-in for the vendor APIs so every flow can be tested without a real environment.

## 10. Not decided yet

- Hosting beyond a single laptop, and user authentication.
- Whether a second target system is needed (the adapter boundary allows it).
- Real values for any bank: supplied per project through configuration.
