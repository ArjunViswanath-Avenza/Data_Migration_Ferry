# Samples

Invented data only. No real customer, bank or account appears here.

## `excel/`: ready-made workbooks

Each workbook holds a whole batch, so it loads in one upload ("What the file holds: Whole workbook").
Its first sheet, Readme, explains it and is skipped on upload.

| Workbook | Batch source system | Expected result |
|---|---|---|
| `synthetic-batch.xlsx` | blank | The six contracts below, one per check: R-001, B-100 and B-200 PARTIAL; R-002 READY; R-003 and R-004 BLOCKED |
| `clean-batch.xlsx` | blank | 40 contracts (10 business, 30 retail), no findings, every contract READY: the happy path |
| `legacycore-batch.xlsx` | `LEGACYCORE` | Users in the fictional core's own columns, mapped by the example profile; `BRANCH_CODE` shown as ignored; 3 contracts READY |

`build_examples.py` rebuilds them (needs `pip install openpyxl`). For volume testing:
`python build_examples.py --contracts 400`.

## `synthetic/`: one batch that exercises every Boarding check

Upload the four CSVs into one batch (choose the record type for each), then validate and plan.
Headers are the template labels, so no mapping profile is needed.

| Contract | What it shows | Expected plan |
|---|---|---|
| R-001 | Retail, leading-zero CIF `001000111`. User `U-R001-2` has a role that is not configured; a signatory group was given to a retail contract. | PARTIAL |
| R-002 | Retail with no communication details. Account `09990000099901` is also listed under B-100 (joint, A-09) and is left out. | READY |
| B-100 | Business, two CIFs (`045000100`, `060000200`). Linked admin, complete virtual maker, and a virtual checker missing middle name and tax ID (A-08). Group B's only member is that checker. | PARTIAL |
| B-200 | Business. `U-B200-B` is LINKED but has no CIF. Two groups share the name "Beta Signers" and the same user is in both. | PARTIAL |
| R-003 | The primary CIF `003000333` owns no account on the contract. | BLOCKED |
| R-004 | Service definition `PREMIUM` is not in reference-data.json. | BLOCKED |

User `U-X` points at contract `R-009`, which is not in the batch.

## `legacy-core/`: a source system with its own column names

`users_export.csv` comes from a fictional core ("LEGACYCORE"). Create a batch with source system
`LEGACYCORE` and upload it as USER: the profile `config/mapping-profiles/example-legacycore-user.json`
renames the columns, turns `C`/`N` into LINKED/VIRTUAL, maps role codes, keeps only the digits of phone
numbers and converts `dd/MM/yyyy` dates.
