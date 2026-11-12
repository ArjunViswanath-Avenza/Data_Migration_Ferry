# Approval rules — design (v0.2, draft for review)

How Data Migration Ferry turns a source system's approval rules (maker/checker, self-approval,
checker groups) into Temenos Digital approval-matrix setup: signatory groups, approval mode and
approval rules. This is **build step 5, Corporate setup** in [DESIGN.md](DESIGN.md).

It reuses the logic of an earlier, client-specific approval-matrix tool (a four-stage offline
pipeline and its migration guidance), made source-agnostic, fixed where that tool was wrong, and
connected to the DataMigration APIs instead of stopping at payload fragments.

v0.2 adds the vendor's **Manage Approvals** product page (updated 3 Sep 2024). It describes the app
and the Experience APIs, not the DataMigration APIs, so what it settles is treated as a strong
assumption to confirm once in a test environment.

---

## 1. What has to be produced in the target

For each business contract, Temenos needs three kinds of call, in this order
(vendor guide, SME use case steps 5–7):

| Order | API | One call per | Carries |
|---|---|---|---|
| 1 | `CreateSignatoryGroup` | target group | name, description, `cif`, `contractId`, `signatories` (user IDs) → returns `signatoryGroupId` |
| 2 | `UpdateApprovalMatrixMode` | contract + CIF, only for group-level customers (A-14) | `isGroupLevel`: `0` user level, `1` signatory-group level |
| 3 | `CreateApprovalMatrixRule` | contract + CIF (+ account) + action + limit type | `limits[]`: one entry per amount bracket |

A `limits[]` entry takes one of two shapes, matching the mode:

```json
{ "lowerlimit": "-1.00", "upperlimit": "20",
  "approvers": [ { "approverId": "{{USER:U-7.userId}}" } ], "numberOfApprovals": -1 }
```
```json
{ "lowerlimit": "5000", "upperlimit": "-1.00",
  "groupList": "[{{GROUP:G-A.signatoryGroupId}},{{GROUP:G-B.signatoryGroupId}}]",
  "groupRule": "[[1,1]]" }
```

Facts from the vendor material that shape everything below:

- Mode is exclusive per contract and customer number, and **user level is the default**. User level
  and group level cannot be mixed.
- A range is **above the lower value, up to and including the upper value**. Each range starts where
  the previous one ends, there are no gaps, and the set ends with an open "Above" range.
- Rules are per feature action and limit type (per transaction, daily, weekly). Non-monetary actions
  have rules but no ranges.
- A group can't be reused within one AND condition.
- User level supports one approver list per bracket, with `numberOfApprovals` limited to
  `-1` (all), `0` (no approval), `1`, `2`, `3`.
- Group level: `groupList` and `groupRule` are **strings** holding array notation. Rows of
  `groupRule` are OR; positions inside a row line up with `groupList` and are AND.
- A user can be in only one signatory group per contract (`ERR_21021`); signatories must already
  be users on the contract (`ERR_21018`).
- `accountId` is optional; when sent, the rule is account-level.
- `actionId` and `limitTypeId` are mandatory on every rule.

---

## 2. Two ways in

```
                    ┌──────────────────────────────┐
 Route A            │ Target-shaped approval rules │  bank already designed groups, mode, rules
 (no conversion) ──►│  APPROVAL_MODE, APPROVAL_RULE│──┐
                    └──────────────────────────────┘  │
                                                      ├──► Boarding: validate ─► plan ─► Crossing
                    ┌──────────────────────────────┐  │
 Route B            │ Approval-rule converter      │  │
 (legacy rules) ───►│  C1 … C5 (section 4)         │──┘   writes the same target-shaped records,
                    └──────────────────────────────┘      plus an exception list for the bank
```

- **Route A** — the bank (or a consultant) supplies the target design directly. Ferry validates and
  plans it. Useful for small migrations and for re-loading a bank-corrected design.
- **Route B** — the bank supplies its legacy rules as they are. The converter derives the target
  design, explains every decision, and lists what it cannot convert. Its output **is** a Route A
  input, so a reviewer can download it, correct it in Excel, and load it back.

The converter never calls Temenos. Everything it decides is visible, reviewable and repeatable
before Crossing sends anything.

---

## 3. Data model additions

### 3.1 Source records (Route B input, mapped by mapping profiles like any other source)

These three types are in the canonical model and on the bank data template
([DATA_TEMPLATE.md](DATA_TEMPLATE.md)) as the sheets Approval Rules, Approval Checkers and Approval
Group Members. Today Boarding loads them and runs field checks; the converter is what's left to build.

| Record type | One row per | Key fields |
|---|---|---|
| `SRC_RULE` | source rule header | `ruleRef`, `contractRef` (company), `cif` (transaction CIF, optional), `accountId` (optional), `txnType` (`*` = all), `currency`, `fromAmount`, `toAmount`, `makerRef` or `makerGroupRef`, `requiredApprovals`, `selfApprove` (Y/N), `sequential` (Y/N) |
| `SRC_RULE_CHECKER` | checker slot on a rule | `contractRef`, `ruleRef`, `position`, `checkerRef`, `isGroup` (Y/N) |
| `SRC_GROUP_MEMBER` | membership of a source group | `contractRef`, `groupRef`, `userRef`, `groupKind` (MAKER/CHECKER) |

Different source systems shape these differently (columns `Checker1..Checker5`, a separate detail
table, a list flag). A mapping profile flattens them into these three types; the converter only
ever sees this shape.

### 3.2 Target records (Route A input and converter output)

| Record type | One row per | Key fields |
|---|---|---|
| `SIGNATORY_GROUP` | group member (exists today) | `groupRef`, `contractRef`, `groupName`, `description`, `userRef` — plus `origin` (SOURCE, GENERATED) |
| `APPROVAL_MODE` | contract + CIF | `contractRef`, `cif`, `mode` (USER, GROUP), `decisionNote` |
| `APPROVAL_RULE` | bracket of a rule | `contractRef`, `cif`, `accountId`, `actionRef`, `limitTypeRef`, `lower`, `upper` (above lower, up to and including upper; blank = open), `scope` (MONETARY, NON_MONETARY), `kind` (USER, GROUP, NO_APPROVAL), `approvers` (user refs), `numberOfApprovals` (`ALL`, 1, 2, 3), `paths` (group expression, below), `sourceRuleRefs`, `note` |

`paths` is written so a person can read and edit it in Excel:

```
G-A:1 + G-B:1 | G-C:2        means  (1 from G-A AND 1 from G-B) OR (2 from G-C)
```

### 3.3 Reference data additions (`reference-data.json`)

```json
"actions":    [ { "ref": "INTRA_TRANSFER", "targetId": "INTRA_BANK_FUND_TRANSFER_CREATE", "monetary": true } ],
"limitTypes": [ { "ref": "PER_TXN", "targetId": "MAX_TRANSACTION_LIMIT" } ],
"approvalActions": { "BUSINESS": [ "INTRA_TRANSFER", "DOMESTIC_WIRE", "…" ] },
"codeMaps":   { "txnTypeToAction": { "OAT": "INTRA_TRANSFER" } }
```

- `approvalActions` is the per-service-definition list that `*` ("all transaction types")
  expands to (assumption A-05). The vendor's reconciliation sample suggests about 78 actions for an
  SME service definition, so this list must come from the bank's Spotlight configuration.
- `codeMaps.txnTypeToAction` translates source transaction codes. An unmapped code is a finding,
  not a guess.

---

## 4. The converter (Route B)

Five stages, each a pure function of the previous one, each keeping a full trail back to source
rule IDs. Population is counted at every stage; **a customer that enters a stage must leave it**,
either converted or as an exception.

### C1 · Reconstruct source rules

Join `SRC_RULE`, `SRC_RULE_CHECKER` and `SRC_GROUP_MEMBER` into one logical expression per source
rule: *N approvals from {users / groups}*, plus maker scope and self-approval.

- A checker group with no supplied membership → exception `MISSING_GROUP` (never infer members).
- Required approvals greater than the available members → `INSUFFICIENT_GROUP_MEMBERS`.
- `selfApprove = N` with zero required approvals → `ZERO_AUTHORIZERS_ANOMALY`.
- Maker groups have no target equivalent: expand to individual makers when every member inherits
  the same rule; otherwise `MAKER_GROUP_REVIEW`.

### C2 · Normalize contexts and brackets

The analysis unit is a **context**: `contractRef + cif + accountId + txnType + currency`.
Never normalize one source rule on its own.

1. Collect every `fromAmount` / `toAmount` in the context.
2. Build non-overlapping brackets, each **above its lower value and up to and including its upper
   value**. A source `from 1000 to 3000` stays 1000–3000, and the first bracket starts at 0.
3. Record which source rules are active in each bracket.
4. Turn ranges with no active rule into **explicit no-approval ranges**, so every amount is covered,
   and end each context with an open range.
5. `txnType ≠ *` with `0–0` is a **non-monetary** context: one record, no brackets, logic kept.
   `txnType = *` with `0–0` is not automatically non-monetary; carry it forward for review.

How brackets are written to the API stays a separate encoding choice (A-03, section 5). The earlier
tool's "next = previous + 1" is not carried over: with an exclusive lower limit, a range starting at
1,001 leaves everything above 1,000 up to and including 1,001 uncovered.

### C3 · Reconstruct approval logic per bracket

For each bracket (or non-monetary context), combine the active rules:

- Several active rules are **OR alternatives** unless the source proves otherwise.
- **Safe user consolidation:** alternatives that are all "1 of {single user}" collapse to
  `ANY 1 OF {users}`; larger merges only when every allowed combination is preserved
  (proved by enumerating combinations, capped for large sets).
- **Self-approval rows are real requirements:** the user must be a valid approver under the bracket's
  rule in the customer's mode. `isSelfApprovalEnabled` appears to be one bank-wide setting (A-13), so
  self-approving users are sent as approvers and the bank decides the setting.
- **Sequential rules** ("A then B") have no target equivalent: convert to `A AND B` only with bank
  acceptance; until then `SEQUENTIAL_PRODUCT_GAP`.
- Account-level rules stay separate from customer-level rules.

Output per bracket: a set of paths, each path an AND of terms `N from {set}`, where a set is a user
list or a source group.

### C4 · Choose the mode and design the groups

Per contract/CIF, over **all** its normalized contexts (including self-approval coverage):

1. A context is **user-compatible** when it is exactly one path `ANY 1|2|3 / ALL of {users}`.
2. **80/20 decision** (threshold configurable, default 80%, counted on normalized contexts, not
   raw rule rows):
   - ≥ threshold user-compatible → mode `USER`; the incompatible contexts become
     `USER_MODE_EXCEPTION` for the bank to decide.
   - otherwise → mode `GROUP`; every context is converted to group paths.
3. **Group design** (mode `GROUP`): derive non-overlapping target groups across the whole
   contract, because a user can be in only one group.
   - Reuse a source group only if it is non-overlapping and reproduces the logic.
   - A lone approver becomes a one-member group.
   - If no non-overlapping design reproduces the logic → `ONE_GROUP_CONFLICT` (bank design needed).

**Correction of the earlier tool's main defect.** The earlier tool converted a user rule such as
`ANY 2 OF {A,B,C}` into three one-member groups joined by AND — `A AND B AND C` — which allows
fewer approval combinations than the source and blocks payments that should pass. Ferry instead:

| Case | Conversion in group mode |
|---|---|
| `ANY N OF {users}`, the users can share one group | one group `{A,B,C}`, path `G:N` |
| … and A-02 = COUNTS (count per group supported) | encoded as `N` in that group's position |
| … and A-02 = FLAGS (1/0 only) and N > 1 | **not encoded** → `GROUP_COUNT_UNSUPPORTED`, never rewritten as AND of singletons |
| `ALL OF {A,B}` | `A:1 + B:1` with one-member groups, or one group with count = size |

A regression test pins this case.

### C5 · Write the target design and the decision report

- Target records (`SIGNATORY_GROUP` with `origin = GENERATED`, `APPROVAL_MODE`, `APPROVAL_RULE`).
- **Per-customer summary:** contexts, user-compatible %, chosen mode, complexity, exceptions.
- **Complexity** (kept from the earlier tool's final rules):
  - *Easy* — direct conversion.
  - *Medium* — a real deterministic transformation: group expansion, maker-group expansion, safe
    consolidation, overlapping-range reconstruction, new groups, self-approval coverage design.
    Not merely "several brackets", "a gap exists" or "group mode".
  - *Hard* — needs a bank decision: 80/20 leftovers, sequential rules, one-group conflicts, data
    anomalies.
- **Customers with self-approving users**, listed for the bank's decision on the bank-wide setting
  (A-13, call sheet Q10).
- **Baseline comparison:** if the bank has an accepted earlier analysis (mode and complexity per
  customer), load it as a baseline; any difference is listed as a regression exception, never
  silently changed.

The whole output downloads as one Excel workbook (target design + report) and loads back through
Route A after review.

---

## 5. Encoding, governed by the assumptions register

The converter decides *what* the rule is. Encoding decides *how it is written* to the API, and
every unclear point is an existing, switchable assumption:

| Assumption | Question | Default | Alternative | Status of the evidence |
|---|---|---|---|---|
| **A-02** groupRule | 1/0 flag or approvals count per group? | FLAGS: N > 1 from one group is parked | COUNTS: write N | **Counts look likely:** SME step 7 explains `[[1,2,1,3],[1,3,2,1]]` as counts, Manage Approvals defines each condition as a number of approvals from a group, and the 1/0 samples fit too. Confirm the API format, then switch. |
| **A-03** brackets | Do brackets share a boundary? | SHARED_BOUNDARY (vendor samples: 5→5) | UPPER_PLUS_ONE (earlier tool) | **Supported by Manage Approvals:** "> from_value AND <= to_value". Confirm once on the API with 1,000 / 1,000.50 / 1,001. |
| **A-04** gaps | How to send "no approval needed"? | NO_APPROVAL: explicit range, `numberOfApprovals: 0`, no approvers (**changed from OMIT**) | OMIT | Manage Approvals requires full coverage with no gaps. The guide lists `0 = NO_APPROVAL`; the Postman sample using it failed with 87115. The group-mode format is unknown. |
| **A-05** `*` rules | One call per action? | expand to `approvalActions` of the service definition, for the limit types the source rule defines (per transaction if it doesn't say) | — | Rules are per action and limit type. Whether all three limit types are required is open. Needs the bank's action list. |
| **A-06** non-monetary | Which limit type for non-money actions? | PARK | send with configured limit type, `-1.00`→`-1.00` | Non-monetary rules exist and have no ranges, but `limitTypeId` is mandatory on the API. Stays parked until that is known. |
| **A-13** self-approval | Bank-wide, or per customer or user? | BANK_WIDE: set outside the migration; self-approving users sent as approvers | — | `isSelfApprovalEnabled` is on the `application` table, which has no customer or user column. |
| **A-14** default mode | Per contract and customer, user level by default? | GROUP_ONLY: mode call only for group-level customers | — | Manage Approvals states both. `UpdateApprovalMatrixMode` refuses a mode already set. |

User-mode limits also apply: `ANY N` with N > 3 that is not "all" → `USER_N_UNSUPPORTED`
(group mode or bank decision).

---

## 6. Planning and execution (Boarding → Crossing)

New steps in a contract's voyage, after users and groups:

```
contract ─► users ─► group:* ─► mode:<cif> (group-level only) ─► rule:<cif>:<account>:<action>:<limitType>
```

| Step | Depends on | Payload placeholders |
|---|---|---|
| `group:<ref>` (exists) | contract, member users | `{{USER:<ref>.userId}}` |
| `mode:<cif>` (group-level customers only) | contract, every group of the contract | — |
| `rule:…` | its mode step if it has one, every user and group it references | `{{USER:<ref>.userId}}`, `{{GROUP:<ref>.signatoryGroupId}}` |

- A blocked group blocks every rule that references it; the contract becomes PARTIAL, as today.
- Rules for a group-level CIF are only planned when its mode step can run. User-level customers need
  no mode call, because user level is the default (A-14).
- `UpdateApprovalMatrixMode` refuses a mode the contract already has (`ERR_10205`, "isGroupMatrix
  flag is same as the input"). Crossing treats that specific error as "already in this mode", not a
  failure.
- The approvals on/off switch (`updateApprovalMatrixStatus`) isn't a DataMigration API. Ferry makes no
  call and leaves it at its default, pending call sheet Q21.
- The ID register gains `SIGNATORY_GROUP → signatoryGroupId`; rules and mode are recorded as done
  per (contract, CIF, account, action, limit type) so reruns skip them.
- Rollback (Return Crossing) includes these items once DeleteMigratedData coverage is confirmed
  (call sheet Q10–Q12).

---

## 7. Checks added to Boarding

| Check | Catches |
|---|---|
| `XR_APPROVER_NOT_ON_CONTRACT` | an approver or group member who is not a user of that contract |
| `XR_UNKNOWN_ACTION`, `XR_UNKNOWN_LIMIT_TYPE` | an action or limit type missing from reference data |
| `XR_MODE_MISSING`, `XR_MODE_MISMATCH` | rules without a mode, or a user rule under group mode (and vice versa) |
| `XR_BRACKET_OVERLAP` | overlapping brackets for the same contract/CIF/account/action/limit type |
| `XR_BRACKET_GAP` | ranges that leave an amount uncovered, or don't end with an open range |
| `XR_GROUP_REUSED_IN_PATH` | the same group used twice within one AND condition |
| `XR_GROUP_COUNT_EXCEEDS_MEMBERS` | a path asking for more approvals than the group has members |
| `TM_USER_N_UNSUPPORTED` | user-mode `numberOfApprovals` outside −1, 0, 1, 2, 3 |
| `TM_GROUP_COUNT_UNSUPPORTED` | a count above 1 while A-02 = FLAGS |
| `TM_ONE_GROUP_PER_USER` (exists) | a user in two groups on one contract |

---

## 8. Console

A new module, **Approvals**, in the Migration group between Boarding and Crossing (a folder under
`ferry-console/src/modules`, per [CONSOLE.md](CONSOLE.md)). Tabs:

1. **Load** — source rule files (Route B) or target design (Route A).
2. **Brackets** — contexts and normalized brackets per customer, with no-approval ranges and non-monetary records.
3. **Rules** — reconstructed logic per bracket, with the source rules behind it.
4. **Customers** — mode decision, user-compatible %, complexity; filter by Easy/Medium/Hard.
5. **Exceptions** — everything that needs a bank decision, by type, exportable.
6. **Target design** — groups, modes and rules ready for Boarding; download / re-upload.

---

## 9. Build plan

| Step | Delivers | Proven by |
|---|---|---|
| 5a | Data model, reference-data additions, Route A load and checks, plan steps for groups, mode and rules | sample target design planned with correct dependencies and placeholders |
| 5b | C1–C2: reconstruction and bracket normalization | population kept; overlapping-range and non-monetary cases |
| 5c | C3–C4: logic, safe consolidation, 80/20, group design | consolidation, self-approval, one-group-conflict and **N-of-M** regression tests |
| 5d | C5: report, Excel export/import, baseline comparison | round trip: export → edit → re-import gives the same plan |
| 5e | Approvals console module | live check on synthetic samples |

Synthetic samples (`samples/approvals/`) will cover each documented case: single checker, any-N,
alternatives, AND across groups, self-approval, maker group, sequential, account-level,
non-monetary, missing group, insufficient members.

---

## 10. Open questions for Temenos

All are on the call sheet ([temenos-call-sheet.html](temenos-call-sheet.html), Excel copy alongside):

| Call sheet | Question | Decides |
|---|---|---|
| Q3 | Does `groupRule` take approval **counts** (as the SME step 7 narration says) or 1/0 flags? | A-02, and whether "ANY N from one group" can migrate |
| Q4 | Confirm that ranges work as in Manage Approvals, and whether they must stay within the company limit | A-03 |
| Q8 | What is the exact format for "no approval needed", and what do `isGroupMatrix` and `approvalruleId` do? | A-04 |
| Q9 | Are all three limit types required; how do we get the action list; which limit type for non-money actions? | A-05, A-06 |
| Q10 | Is **self-approval** bank-wide, and how is it set for a migration? | A-13 |
| Q11 | Confirm mode and rules are per contract and customer number, user level by default | A-14 |
| Q12 | Can the APIs replace or remove rules and groups once created? | re-runs and corrections |
| Q20 | Which permissions must approvers have before rules are created? | a possible pre-check on roles |
| Q21 | What happens to customers with no approval rules? | whether a migration must turn approvals off |
