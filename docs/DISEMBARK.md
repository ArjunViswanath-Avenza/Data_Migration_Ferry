# Disembark: un-enrol one customer

Disembark is the console's **Tools → Disembark** area. You give it one core customer ID (CIF). It removes
everything that customer's enrolment created in the Temenos Digital database (DBXDB, MySQL):

- logins
- contract
- limits
- approval rules
- signatory groups
- custom roles
- addresses

It is a separate utility for single customers. **Return Crossing** (build step 7) remains the planned bulk
rollback through the vendor's delete API.

The logic is the DM Studio un-enrolment module (`rollback-service`, validated against a real UAT copy)
carried over unchanged, with these changes:

- **Kept as it was:**
  - every lookup runs first
  - delete order is children first, anchors last
  - everything runs in one transaction
  - a row guard per table
  - a shared-login check
- **Bank specifics removed:** the profile lives in `config/unenroll.json`.
- **Added:**
  - a shared-contract check
  - the extra tables from the product team's customer delete list
  - strict config parsing
  - handling of tables a release does not have

## Three ways, safest first

| Way | Needs | What happens |
|---|---|---|
| **Write script** | Nothing | A SQL file that a DBA runs. It does six things in order: freezes the IDs in temporary tables, previews the counts, runs the checks, deletes inside one transaction, verifies that every count is 0, and **never commits**. The DBA types `COMMIT;` or `ROLLBACK;`. |
| **Preview (read-only)** | A database connection | Counts what each step would remove and runs the checks. The connection is set read-only. With a connection, the script also carries these counts and any warnings as comments. |
| **Un-enrol now** | A connection, and `allowExecute: true` in `config/unenroll.json` (**off by default**) | Deletes in one transaction and commits. |

**Un-enrol now** has several safeguards:

- You must type the CIF again.
- If a check found a shared login or contract, you must tick an acknowledgement.
- It stops before deleting anything if any table is over the guard (5,000 rows by default).
- It rolls back on any error.

**The password** is sent with each request and used once. It is never stored, written to disk or logged.
Host, port, schema and user are remembered in your browser only.

Every script, preview and un-enrolment is recorded in Ferry's run history (area `disembark`): who ran it,
which CIF, which database, how many rows, and the error if it failed.

## How it finds the rows

1. **Resolve.** From the CIF, look up and hold:
   - contracts (`contractcorecustomers`)
   - logins (`contractcustomers`)
   - signatory groups
   - approval matrices
   - custom roles
   - addresses
   - accounts
   - user names
   - customer requests

   Holding them first matters: once child rows are gone, the parents can no longer be found.
2. **Check.**
   - *Shared login*: a login that is also linked to other CIFs, such as one person with access to
     several legal entities of the same company.
   - *Shared contract*: a contract that also holds other CIFs. Contract-side deletes remove the whole
     contract.
3. **Delete**, in this order:
   1. approval matrix
   2. signatory groups
   3. custom roles
   4. customer side
   5. payees and payment history
   6. contract side
   7. address and accounts
   8. anchors (`customer`, `contract`)

## Off by default

These steps are listed, and appear commented out in the script, but never run until someone switches
them on in `config/unenroll.json` (`"confirmed": true`):

| Step | Why it is off |
|---|---|
| `customrole` | A custom role may be shared across an organisation. |
| `accounts` | Accounts can be joint or shared, and re-enrolment syncs them again anyway. |
| Saved payees, transfers, business banking requests and templates, ACH history (15 tables) | They are on the product team's list, but they are payment history. The bank should agree before they go. |

## A table the database does not have

Releases differ. A step whose table is missing is shown as *Table not in this database*, and the script
leaves that line out with a comment. A lookup that reads a missing table finds nothing, and the preview
says so.

## Changing what is removed

Everything is in `config/unenroll.json`. Nothing in the code names a table.

- `resolveSteps`: name and SQL using `:cif`.
- `checks`: SQL returning `item` and `others`. It may use `:cif` and any resolve name as a list.
- `deleteSteps`: `group`, `table`, `column`, `using` (`cif` or a resolve name), `confirmed`, `note`.
- `schema`, `keyLabel`, `keyPattern`, `maxRowsPerTableGuard`, `allowExecute`.

The file is read on every request, so edits apply without a restart. An unknown key or a step that uses an
undefined lookup is refused with a message.

## API

| Call | Purpose |
|---|---|
| `GET /api/disembark/profile` | the steps, guard and switches |
| `POST /api/disembark/script` | `{ cif, schema?, performedBy, db? }` returns `{ fileName, sql, preview? }` |
| `POST /api/disembark/preview` | `{ cif, performedBy, db: { host, port, schema, user, password } }` returns counts and checks |
| `POST /api/disembark/execute` | as for preview, plus `confirmCif` and `acknowledgeWarnings`; refused while `allowExecute` is false |
| `GET /api/disembark/history` | recent runs |

## Code

| Where | What |
|---|---|
| `config/unenroll.json` | the profile |
| `ferry-server/…/disembark/UnenrollProfile.java` | strict config model and validation |
| `ferry-server/…/disembark/UnenrollEngine.java` | resolve, preview, execute and script |
| `ferry-server/…/disembark/DisembarkService.java`, `DisembarkController.java` | connections, safety gates, run history, API |
| `ferry-console/src/modules/disembark/` | the console area |
| `ferry-server/src/test/java/com/dataferry/disembark/` | tests on an in-memory MySQL-mode database with three customers |
