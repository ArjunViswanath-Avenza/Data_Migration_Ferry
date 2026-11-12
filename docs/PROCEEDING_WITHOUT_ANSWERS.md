# Proceeding without Temenos' answers

**Short answer:** yes, Ferry can go ahead. Most of the 21 questions can be settled without Temenos, the rest
can be tested safely, and every remaining guess is checked on the first contract instead of being discovered
after thousands. This page lists every question, what we now know, what goes wrong if a guess is wrong, and
how Ferry protects against it.

Written 12 November 2026.

## Where the answers come from instead

| Source | What it gives | Status |
|---|---|---|
| **1. The product's own database** | How Temenos itself stores what its screens create: unique keys, reference codes, approval rules, ranges, modes. Read from the R21 out-of-the-box schema and the local copy of a bank's UAT database (structure and counts only; no customer details were read out). | Done: settles 10 questions, partly settles 6 more |
| **2. Read-back during the run** | After every call, Ferry reads the rows the call created (read-only) and compares them with the shape the product uses (source 1). A wrong guess is caught on the first contract, not after the batch. | To build |
| **3. Sea trials** | A fixed set of test customers sent through a non-production environment, each designed to answer one open question, checked by read-back, then removed with Disembark. | To build |

Around those sources, the design itself is defensive:

- **Settings, not code.** Every guess is a setting in the assumptions register, which can be switched without
  code changes.
- **A pilot first.** The first contracts in a batch are a pilot, followed by a pause for review.
- **Stop rules.** A batch stops on any read-back mismatch, or when failures pass a threshold.
- **Safe re-runs.** A step is never sent twice, using the ID register plus a database check.
- **Rollback does not depend on the vendor's delete API.** Disembark, already verified end to end, is the
  fallback.

## Evidence found in the product's own data

These come from the R21 schema (product, not bank customisation) and the bank's UAT copy (one bank's
real use of the product):

| Finding | Evidence | Answers |
|---|---|---|
| Contract names must be unique | `contract` has `UNIQUE KEY Name_UNIQUE (name)` in R21 and in the bank's copy; 862 contracts, 862 distinct names | Q5: the cause of error 10401 |
| An account belongs to one contract only | `contractaccounts` has `UNIQUE KEY accountId_UNIQUE (accountId)` | Q17: joint accounts cannot sit in two contracts |
| Approval mode is set per contract and CIF | `approvalmode (contractId, coreCustomerId, isGroupLevel)`, unique on contract + CIF; 1,453 group level, 75 user level | Q11 |
| Self-approval is one bank-wide switch | `application.isSelfApprovalEnabled`, one row, currently on | Q10 |
| Approval codes are fixed | `approvalrule`: `ALL` (-1), `ANY_ONE`, `ANY_TWO`, `ANY_THREE`, `NO_APPROVAL` (0), the same in R21 | Q3, Q8: user mode can only say any 1, 2, 3, all, or none |
| groupRule numbers are counts | Of 21,529 group rules, 2,211 contain 2, 3, 4 or 6; a flag could only be 0 or 1 | Q3 |
| Ranges share their boundaries; open ends are -1 | 94% of rules with several ranges reuse the upper value as the next lower value (98 use +1, the old tool's error); 98% start at -1 and end at -1 | Q4 |
| "No approval" is stored explicitly | User mode: 32,025 of 37,738 live rows are `NO_APPROVAL`. Group mode: `NO_APPROVAL` with an all-zero groupRule | Q8, Q21 |
| Non-monetary rules have a format | `limitTypeId = NON_MONETARY_LIMIT`, lower and upper both -1 (all 582 live rows) | Q9 |
| Limit types per rule vary | 10,715 action-account sets have all three (per transaction, daily, weekly); 6,663 have one | Q9: all three are not required |
| Rules are kept per account | 46,707 of 46,997 live rules name an account; 290 are contract-wide | New point: a "*" rule must be repeated per account |
| Approval actions are listed in the database | `featureaction.isApprovalAction` (31 of 372 actions at this bank) | Q9: no need to ask the bank for the list |
| Edits soft-delete | 21,523 rules carry `softdeleteflag = 1` | Q12: changes replace rows rather than editing them |

Storage is not the same as the API payload. The evidence shows what the result must look like in the
database. Read-back (source 2) proves that the API calls produce it.

## Every question, its risk, and the guard

**Status** in the table below:

- **Settled:** settled by evidence.
- **Shape known:** the stored result is known; the exact API payload is to be proved by a sea trial.
- **Open:** still open.

| # | Question | Status | If our guess is wrong | How Ferry guards |
|---|---|---|---|---|
| 1 | Which cores the APIs support | Open | LinkUserToContract fails for some users | Per-user choice (A-01) with a switch to all-virtual; failures are per user and re-runnable; sea trial S1 |
| 2 | Release; legal entity | Shape known | An operation is missing in the target release (404) | Pre-flight survey probes every operation and reads `legalentity` from the database before anything is sent |
| 3 | groupRule counts | Settled | (was: rules needing 2 from one group parked) | A-02 moves to COUNTS; read-back checks the stored groupRule |
| 4 | Range boundaries | Settled | Amounts on a boundary go to the wrong range | Shared boundary (A-03) as the product stores it; read-back compares stored lower and upper values |
| 5 | Contract name clash (10401) | Settled (cause) | A contract fails because another has the same name | Survey lists name clashes before the run; a timed-out create is checked in the database before any retry; sea trial S2 shows how the name is made |
| 6 | Virtual-user fields | Open | A user is refused for a blank middle name or tax ID | Held back by default (A-08); sea trial S3 sends blanks to find out |
| 7 | First login | Open (outside the APIs) | Users cannot log in after migration | Not Ferry's to solve: the bank's login set-up is tested by pilot users in UAT before go-live (runbook item) |
| 8 | No-approval format | Shape known | A rule is refused (as Postman's 87115) or stored wrongly | Sent explicitly (A-04); read-back expects `NO_APPROVAL` (user) or `NO_APPROVAL` plus a zero groupRule (group); sea trial S4 finds the payload |
| 9 | Actions and limit types | Settled | Missing or extra rules | Approval actions read from `featureaction`; limit types as the source gives them; non-monetary as `NON_MONETARY_LIMIT` -1/-1 (A-06 changes); rules repeated per account (new A-15) |
| 10 | Self-approval | Settled | — | Survey shows the bank-wide value; self-approvers are listed as approvers (A-13) |
| 11 | Mode per contract and CIF | Settled | — | Mode call per contract + CIF, group level only (A-14) |
| 12 | Duplicates and edits | Shape known | A rule is created twice | Never sent twice: ID register plus a database check before every create; corrections as replace, never edit |
| 13–15 | DeleteMigratedData coverage, scope, atomicity | Open | A rollback leaves data behind or fails part-way | Not relied on: Disembark (verified end to end on a real copy) is the rollback; the vendor call is optional (A-12 gains a Disembark option) |
| 16 | Throughput, tokens | Open | Calls are throttled or time out | Start with 1–2 contracts in parallel, measure, raise; back off on 429, 5xx and timeouts; renew the token early; stop rule on repeated failures |
| 17 | Joint accounts | Settled | (INCLUDE would fail on the unique key) | Excluded and listed (A-09); INCLUDE is marked as impossible |
| 18 | What success is | Settled by design | A failure looks like success | A step succeeds only when the response is clean **and** read-back finds the rows |
| 19 | Reconciliation counts | Replaced | The vendor report needs counts the bank does not have | Ferry reconciles itself: planned items against rows read back from the database; the vendor report becomes optional |
| 20 | Approver permissions | Shape known | A rule names an approver who cannot approve | Survey checks approvers' roles against the action's approve permission before rules are sent; sea trial S5 |
| 21 | Customers with no rules | Shape known | Their payments stop, or go through unchecked | The product writes explicit `NO_APPROVAL` rows when nothing is required; offer the same (a bank decision); sea trial S6 shows behaviour with no rows |

## Sea trials

Each trial uses throwaway test customers in a non-production environment. It is checked by reading back,
and removed with Disembark afterwards. A trial that passes moves its assumption to CONFIRMED, with the
evidence attached.

| Trial | Sends | Answers |
|---|---|---|
| S0 Survey | Read-only checks and a harmless probe per operation | Q2 and Q10, the approval list, name and account clashes |
| S1 Linked user | One user with their own CIF via LinkUserToContract | Q1 |
| S2 Contract name | Two contracts whose customers share a name | Q5: how the name is made, what clashes |
| S3 Virtual user blanks | Users with blank middle name, tax ID; phone and date formats | Q6 |
| S4 No-approval ranges | User-mode and group-mode rules with a no-approval range | Q8 |
| S5 Approvals | 2-of-a-group, AND/OR paths, all / any-2 user rules | Q3 and Q20 in practice |
| S6 No rules | A customer with approvals on and no rules attempts a payment in UAT | Q21 |
| S7 Re-run | The same contract sent twice; a timed-out create | Q12 and Q5 recovery |
| S8 Vendor delete | DeleteMigratedData on a trial contract, then read-back | Q13–15, for the record |

## Risks that remain whatever we do

| Risk | Why it remains | What reduces it |
|---|---|---|
| Vendor support | Temenos may decline issues it traces to undocumented use | Writes only through the documented APIs; database access is read-only; Disembark deletes run by the bank's DBA from a reviewed script |
| Target release differs | Evidence comes from R21 and a bank's R21-era data; the target may be R26 | The survey re-checks every finding against the target database before trials |
| Production differs from UAT | Limits, security and data volume differ | A pilot batch in production with stop rules, then ramp up |
| First login (Q7) | Depends on the bank's identity set-up, not the APIs | A runbook item: pilot users log in before go-live |
| Deleting payment history | A legal and record-keeping question | Off by default in Disembark; the bank decides |
| No read access to the target database | Read-back and the survey need a read-only database user | Ask for it early. Without it, Ferry falls back to the vendor's report and exception APIs, which is weaker |

## What this changes in the build

1. **Survey** (new): read-only pre-flight against the target database. It confirms the product rules above,
   lists name clashes, accounts already in a contract and the approval action list, then gives a go or
   no-go for the batch.
2. **Read-back verifier** (new): the expected database shape for every step, used after each call in
   Crossing and again in Manifest.
3. **Mock target** (build step 8, brought forward): it can behave each way an answer could go: flags or
   counts, error 10401, 87115, slow responses, 429. Every branch of Crossing is then tested before UAT.
4. **Crossing** (build step 3) with:
   - a pilot gate
   - stop rules
   - adaptive parallelism
   - early token renewal
   - timeout recovery by database lookup
   - safe re-runs
5. **Sea trials** (new): the scenario pack above, run through Crossing in UAT, cleaned with Disembark, and
   recorded as evidence.
6. **Approval rules** (step 5) on the settled formats; limits and payees (step 6); **Manifest** with Ferry's
   own reconciliation (step 4).

**One request for the bank, needed early:** a read-only database user on the target UAT, and later on
production. It is the only new dependency, and it replaces most of what we were waiting on Temenos for.
