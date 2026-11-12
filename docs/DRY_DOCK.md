# Dry Dock: compare and refit schemas

Dry Dock is the console's **Tools → Dry Dock** area. You give it two database schemas as SQL files. It shows
every difference between them, lets you choose what travels, and writes two scripts:

1. **Refit**: DDL that brings the *target* schema in line with the *source* differences you chose.
2. **Data move**: one `INSERT … SELECT` per table, copying rows from the source schema into the refitted target.

The typical use is a bank that customised an older release. The **source** is the bank's database: wider
fields, extra columns, its own tables and procedures. The **target** is the new release. Dry Dock tells
you what the bank changed, how to put those changes onto the new release, and then how to move the data.

Dry Dock only reads files and writes text. It never connects to a database or runs anything.

## Input

| Accepted | Notes |
|---|---|
| `.sql` | A mysqldump (with or without data), `SHOW CREATE TABLE` output, or hand-written DDL. |
| `.sql.gz` | Read without unpacking to disk. |
| `.zip` | Every `.sql` entry, in name order, as one script. This suits a base script plus upgrade scripts. |

- **Data rows are skipped.** INSERT, LOCK, SET and other non-schema statements are recognised from their
  first word and are never held in memory. A 2 GB dump with data reads in about 8 seconds.
- **Statements are replayed in order.** CREATE, ALTER (ADD / MODIFY / CHANGE / DROP / RENAME COLUMN, indexes,
  keys, `CONVERT TO`), DROP, RENAME TABLE and CREATE INDEX are applied in sequence. A base script followed by
  upgrade scripts therefore gives the end state, and mysqldump's temporary view stand-in tables disappear.
- **Executable comments** (`/*!50001 … */`) are unwrapped, and `DELIMITER` blocks are understood. Views,
  procedures, functions and triggers are therefore read whole.
- The dialect is MySQL / MariaDB. Names are matched case-insensitively.

The quickest export is `mysqldump --no-data --routines --triggers your_db > schema.sql`.

## What counts as a difference

| Area | Found |
|---|---|
| Tables | only in source, only in target, likely renamed (≥ 60% of columns shared, paired by name similarity) |
| Columns | only in source, only in target, likely renamed (same definition and similar name), changed |
| Column changes | type (with a wider / narrower verdict), NULL allowed, default, ON UPDATE, auto increment, character set, collation, comment, generated expression, position (optional) |
| Keys | primary key, indexes (same definition under another name is shown as *name only*), foreign keys (same rule under another name is not a difference) |
| Table options | engine, default character set and collation, comment, row format (optional) |
| Views, procedures, functions, triggers | only in source, only in target, written differently (ignoring definer, quoting, spacing and schema prefixes) |

**Wider and narrower.** `varchar(50)` → `varchar(100)`, `int` → `bigint`, `decimal(18,2)` → `decimal(20,2)`,
`text` → `mediumtext` and an `enum` with extra values are all *wider*. Carrying a wider type over can never
cut a value. A source type that is narrower than the target's is left alone. Types that neither contain the
other (for example `int` against `varchar`) are marked **Risk**.

**Noise settings** (on the load card):

- Integer display width is ignored: `int(11)` and `int` are the same.
- Column order is ignored.
- Rename detection is on.
- Comments and collations are compared.
- If a column only inherits its table's character set, the difference is reported once, on the table.

**Kind of difference** (a filter, default "Every difference") narrows the list to one kind, for example
**Changed only**. When one kind is chosen, the side-by-side table view shows only the columns of that kind.
Each table view also has an **Only columns that differ** box to hide identical columns.

**Ignore character sets** (next to the filters, unchecked by default) hides every difference that is only
about character sets or collations. It is for reading only: nothing is re-compared, and the hidden differences
stay in the scripts with their current choice (Keep, unless you changed it).

## Choices and defaults

Every difference starts on a choice that cannot lose data:

| Difference | Choices (default first) |
|---|---|
| Table only in source | Create in target · Skip |
| Table only in target | Keep · Drop |
| Table renamed? | **Map** (keep the target name, move the source rows into it) · Rename to the source name · Treat as separate. High confidence starts on Map, medium on Separate. |
| Column only in source | Add to target · Skip |
| Column only in target | Keep · Drop |
| Column renamed? | Same as tables. |
| Column changed | **Apply source** for the parts that are safe (wider type, NULL now allowed, utf8mb4), with a tick box for each part. Otherwise Keep. |
| Index / foreign key only in source | Add · Skip |
| Index / foreign key / primary key changed | Keep · Replace with source |
| View / routine only in source | Create · Skip |
| View / routine changed | Keep · Replace with source |

Severity says how much attention a difference needs:

- **Risk**: values could change.
- **Review**: a person must decide (a different default, a changed index, a medium-confidence rename,
  a NOT NULL column with no default).
- **Safe**: something is carried over and cannot hurt.
- **Info**: the target already covers it.

**Carry all shown** sets every difference in view to the choice that takes the source's version. It never
drops anything.

## The scripts

**Refit**, in this order:

1. Foreign keys being replaced are dropped.
2. Tables are renamed.
3. New tables are created.
4. Each table gets one `ALTER TABLE` covering all its column, key, index and option changes. New columns are
   placed after the column that precedes them in the source.
5. Foreign keys are added. A key that points to a table the target will not have is commented out and
   marked `LEFT OUT`.
6. Views and routines are created without their definer. A routine that names a table which has another
   name in the target is marked `CHECK`.
7. **Removals**, only those you chose. This section is always last.

Foreign key checks are off while the script runs and are restored at the end.

**Data move**:

- Rows are copied from the source schema into the target with one `INSERT … SELECT` per table. Parent
  tables come first.
- Renamed columns are mapped.
- A new NOT NULL column with no source value gets a placeholder of the right type, marked `REVIEW`.
- A column whose values could be cut or converted is marked `CHECK`.
- A source column with no place in the target is listed as `not moved`.

The modes are INSERT, INSERT IGNORE, upsert (`ON DUPLICATE KEY UPDATE`) and REPLACE. An option can empty
each target table first. Both schemas must be on the same MySQL server, so restore the source dump into a
staging schema there if needed.

**Differences report (.xlsx)**: every difference with its default and chosen action. It uses the choices
of the last script you wrote, in black and white, ready to share.

## Try it

**Tools → Dry Dock → Try the sample files** compares
`samples/drydock/source_customised.sql` (a customised older release written as a mysqldump) with
`samples/drydock/target_new_release.sql` (a new release written as a base script plus an upgrade).
Between them they contain every kind of difference above.

## API

| Call | Purpose |
|---|---|
| `POST /api/drydock/compare` | multipart `source`, `target`; optional `ignoreIntDisplayWidth`, `ignoreColumnOrder`, `ignoreComments`, `ignoreCollations`, `ignoreRowFormat`, `detectRenames` |
| `POST /api/drydock/compare-samples` | compares the two sample files |
| `GET /api/drydock/comparisons` | the comparisons in memory (the last 8; they are forgotten on restart) |
| `GET /api/drydock/comparisons/{id}` | one comparison |
| `GET /api/drydock/comparisons/{id}/tables/{key}` | both sides of one table, with its differences |
| `POST /api/drydock/comparisons/{id}/scripts` | `{ decisions: { id: { action, aspects } }, sourceSchema, targetSchema, dataMode, emptyTargetFirst, excludeTables }` |
| `GET /api/drydock/comparisons/{id}/report.xlsx` | the differences report |

The server accepts uploads up to 4 GB for Dry Dock. Boarding keeps its own 200 MB cap.

## Code

| Where | What |
|---|---|
| `ferry-server/…/drydock/SqlStatementReader.java` | streaming statement splitter |
| `ferry-server/…/drydock/DdlParser.java` | replays DDL into `SchemaModel` |
| `ferry-server/…/drydock/Types.java` | type synonyms and the wider / narrower verdict |
| `ferry-server/…/drydock/SchemaComparer.java` | differences, renames and defaults |
| `ferry-server/…/drydock/ScriptWriter.java` | refit and data move scripts |
| `ferry-server/…/drydock/DryDockService.java`, `DryDockController.java` | files, memory store, report, API |
| `ferry-console/src/modules/drydock/` | the console area |
| `ferry-server/src/test/java/com/dataferry/drydock/` | tests on the samples and awkward SQL |

## Limits

- **MySQL / MariaDB only.** Oracle or SQL Server DDL is not read.
- **Two-way comparison.** There is no common ancestor, so Dry Dock cannot tell which side made a change.
  That is why anything ambiguous starts on Keep and is marked Review.
- **Renames are suggestions** based on definition and name similarity. Check them before choosing Map or Rename.
- **Not compared:** partitioning, check constraints, events' schedules, grants and users.
- **Comparisons live in server memory** and are not saved.
