# Bank data template

The standard format a bank uses to hand over its data for a migration, from any core banking system.
The bank's teams extract their data into this format, and Ferry loads it without any code change.

## The files to share

| File (in [`templates/`](../templates/)) | Use |
|---|---|
| `Data_Migration_Template.xlsx` | The blank template to send to the bank |
| `Data_Migration_Template_EXAMPLE.xlsx` | The same workbook filled with invented data, to show what's expected |
| `csv/*.csv` | One header-only CSV per sheet, for extracts too large for Excel |

The workbook is also available from the console (Boarding → Load files → Template workbook). It's
generated from the server's data model, `ferry-server/src/main/resources/canonical-model.json`, so
the template and the loader can't disagree. After changing the model, start the server and run
`python templates/build_templates.py` to regenerate the files above.

## What it collects

| Sheet | Holds | Usually comes from | Fill when | What Ferry does with it today |
|---|---|---|---|---|
| Contracts | Customer agreements | Core banking and online banking | Always | Checks it and plans `CreateContract` |
| Accounts | Accounts on each contract | Core banking | Always | Checks it and includes it in `CreateContract` |
| Users | People who sign in | Online banking; core for customer numbers | Always | Checks it and plans `LinkUserToContract` or `CreateVirtualUser` |
| Signatory Groups | Groups the bank has already designed | Bank's approval design | Optional | Checks it and plans `CreateSignatoryGroup` |
| Contract Limits | Limits per customer number, action and limit type | Online banking | If limits are migrated | Field checks and storage; used from build step 6 |
| User Limits | Limits per user, account, action and limit type | Online banking | If limits are migrated | Field checks and storage; used from build step 6 |
| Approval Rules | Current approval rules, as they are | Online banking | If customers have approval rules | Field checks and storage; used from build step 5 |
| Approval Checkers | Who can approve under each rule | Online banking | If customers have approval rules | Field checks and storage; used from build step 5 |
| Approval Group Members | Members of each group in those rules | Online banking | If rules use groups | Field checks and storage; used from build step 5 |
| Payees | Saved beneficiaries of each type | Online banking | If payees are migrated | Field checks and storage; used from build step 6 |

The core banking team alone can't fill this. Users, approval rules, limits and payees normally sit in
the bank's current online banking system, so that team has to be involved too.

## How the data reaches Temenos Digital

```
Bank extract ─► template (Excel or CSV) ─► Ferry Boarding: load, check, plan ─► Crossing ─► DataMigration APIs ─► DBXDB
```

Ferry never writes to DBXDB directly. It calls the Temenos DataMigration APIs, and those write to
the database. Crossing isn't built yet, so nothing is sent today.

## Rules for the bank's teams

These are on the template's Readme sheet:

- One row per item, starting on row 2. Don't rename sheets or columns.
- Every column is formatted as Text, so leading zeros survive. Use Paste Values when pasting.
- Dates as `yyyy-MM-dd`, amounts as plain numbers, phone numbers as digits only.
- Send the customer data only through the agreed secure channel.

Columns marked `*` are always required. The Instructions sheet also shows conditional columns, such
as the personal details a virtual user needs, along with allowed values, an example and notes for
every column.

## Before the bank starts

1. **Agree the code lists.** Service definition and role values must exist in
   `config/reference-data.json` or the checks will refuse them. Ask the bank for its lists first.
2. **Ask for a small sample first,** such as 20 customers, and load it. Findings point to the exact
   sheet row, so problems surface before a full extraction.

## If a bank's extract can't match the columns

Add a mapping profile in `config/mapping-profiles/`. It renames the bank's columns and converts its
codes and date formats, with no code change and no change to the bank's extract. See the LEGACYCORE
example in [`samples/README.md`](../samples/README.md).

## How to load it

In Boarding, create a batch and load the workbook with **Whole workbook** selected. For CSV files,
choose the matching record type for each file. Unfilled sheets are skipped, and extra columns are
listed as ignored.

To check the template itself, load `Data_Migration_Template_EXAMPLE.xlsx`. All 20 example rows should
be valid with no findings, and both contracts should plan as READY.
