# Crossing, Survey and the mock target

Crossing sends a batch that was planned in Boarding to the target, using the vendor's DataMigration APIs.
It is built to work **without the vendor's answers** to the open questions: every guess is checked as the
run goes, and the run pauses on its own when a guess looks wrong. The reasoning behind this is in
[PROCEEDING_WITHOUT_ANSWERS.md](PROCEEDING_WITHOUT_ANSWERS.md).

This build covers contracts, linked users, virtual users and signatory groups: everything Boarding plans
today. Approval rules, limits and payees come in later build steps.

## How a run goes

1. **Survey** (read-only, recommended before every run). It checks the target database and gives
   **Go**, **Go with warnings** or **No go**. It also lists the contracts that would fail, and can leave
   them out of the run. It checks:
   - the approval codes the product uses
   - the bank-wide self-approval switch
   - the approval actions
   - that the plan's service definitions exist
   - accounts already on a contract (the product allows one contract per account)
   - CIFs already enrolled
   - login names already taken
2. **Start.** Choose the planned batch, the read-back source, the pilot size and parallelism. Nothing is
   sent to an environment whose `liveCalls` is off. The only exception is the built-in mock target. A real
   environment also needs the app key and secret in the environment variables it names, and a tick
   confirming you mean to write to it.
3. **Run.** Steps go in plan order within each contract: contract, then users, then groups. Contracts run in
   parallel (A-11). For every step:
   - **Already in the ID register?** It is skipped (`ALREADY_DONE`), so a re-run creates nothing twice.
   - **An earlier step failed?** It is skipped (`SKIPPED_DEPENDENCY`).
   - **IDs from earlier steps** are filled in from the register.
   - **The answer is judged** by the response rules (A-10): HTTP 200, `opstatus` 0, and no error code.
   - **The new ID** is taken from the answer, using the field names in `config/crossing.json` → `resultIds`.
     If the answer has none, read-back looks it up (`RECOVERED`).
   - **Read-back** checks the database for what the call should have created: the contract, its primary
     CIF, every account, the login and its link to the contract, the group and every member. A difference
     is a `MISMATCH`.
4. **Pauses:**
   - after the **pilot** (3 contracts by default)
   - on any **read-back mismatch**
   - on any **unknown outcome**
   - after **5 failures in a row**
   - when **more than 20% fail** (once at least 5 contracts are done)

   **Continue** resumes the run, and **Stop** sends nothing more.

## Retries, timeouts and tokens

| What happened | What Crossing does |
|---|---|
| Could not connect | Retried (the call never left) |
| HTTP 429 or 5xx | Retried with back-off; every worker waits |
| No answer in time | **Not** retried blindly. Read-back looks for what the call created: if found, `RECOVERED`. If not, `UNKNOWN`, and the run pauses. |
| Token expiring | Renewed before it expires, and once more if a call says it has expired |
| Error in the answer | `FAILED` with the vendor code (the inner `ErrorCode` when present) and the catalogue's explanation; not retried |

## Read-back source

| Choice | When |
|---|---|
| Built-in mock database | With the mock target |
| Target database | A read-only MySQL user on the target (ask the bank for it). The password is sent with the request, held in memory for the run, never stored. |
| Off | Not recommended: no ID recovery, no proof, and a timed-out call stops the run |

## The mock target

The mock is built into the server at `/mock`. It signs in like Fabric and answers like the vendor
(HTTP 200 with `opstatus` and `dbpErrCode`). It stores what it creates in a small database shaped like
the product's own, with **one contract per account** and **unique contract names**. The `local-mock`
environment points at it.

Its behaviour switches (Crossing page → Mock target, or `PUT /api/mock/behaviour`) play each way an open
question could go:

| Switch | Plays |
|---|---|
| Answers carry no ID | Contract and user answers without IDs (read-back must find them) |
| Contracts lose their accounts | A call that reports success but stores something else (read-back mismatch) |
| Core not supported for linked users | Question 1: LinkUserToContract answers 10001 |
| CIFs whose contract name clashes | Question 5: error 10401 |
| Fields a virtual user must have | Question 6 |
| Delay, throttle every Nth call, server error every Nth call, late answers, token lifetime | Question 16 and network trouble |

**Reset mock** empties its database.

## Configuration

`config/crossing.json` holds:

- `pilotContracts`, `stopRules`, `retry`, `requestTimeoutSeconds`
- token renewal settings
- `resultIds`: where each operation's answer carries the new ID
- `readBack`: a lookup and checks per operation, as SQL with `:id`, request fields, `:accountCount` and
  `:signatoryCount`

The file is read at the start of every run and parsed strictly.

## API

| Call | Purpose |
|---|---|
| `POST /api/crossing/survey` | `{ batchId, source: MOCK or DATABASE, db }` |
| `POST /api/crossing/runs` | `{ batchId, contractRefs?, pilot?, parallelism?, readBack: NONE, MOCK or DATABASE, db? }` |
| `GET /api/crossing/runs?batchId=` | recent runs |
| `GET /api/crossing/runs/{id}` | state, reason, counts and contracts |
| `GET /api/crossing/runs/{id}/contracts/{ref}` | each step's request, answer and read-back |
| `POST /api/crossing/runs/{id}/continue` | resume after a pause |
| `POST /api/crossing/runs/{id}/stop` | stop the run |
| `GET` and `PUT /api/mock/behaviour`, `POST /api/mock/reset`, `GET /api/mock/state` | mock target control |

## Code and tests

| Where | What |
|---|---|
| `ferry-server/…/crossing/CrossingService.java` | the run engine: pilot, stop rules, retries, register, read-back |
| `ferry-server/…/crossing/FabricClient.java` | sign-in, token renewal, calls, and the four kinds of result |
| `ferry-server/…/crossing/ReadBack.java`, `CrossingConfig.java` | read-back checks and lookups; config |
| `ferry-server/…/crossing/SurveyService.java` | the pre-flight survey |
| `ferry-server/…/mock/` | the mock target and its database |
| `ferry-console/src/modules/crossing/` | the console page |
| `ferry-server/src/test/java/com/dataferry/crossing/CrossingFlowTest.java` | 9 end-to-end tests over real HTTP |

The 9 tests cover:

- a pilot, then completion
- a re-run that creates nothing
- a name clash
- an answer without an ID, recovered
- a read-back mismatch
- a timed-out create, found rather than re-sent
- throttling
- the survey finding a taken account
- the live-calls gate

## Not yet

- Approval rules (build step 5), limits and payees (step 6), and Manifest's own reconciliation (step 4).
- Sea trials S0–S8: they use this engine against UAT once the bank provides access.
- A lookup for users that time out. It is left out on purpose: no reliable key is known yet, so such a
  step stops the run as `UNKNOWN` instead of guessing.
