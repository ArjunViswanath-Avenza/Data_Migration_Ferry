# Data Migration Ferry

A standalone, one-stop tool that carries customer data from **any source system** into
**Temenos Digital** through the product's `DataMigration` APIs, and proves what arrived.

| Area | What it does | Status |
|---|---|---|
| **Boarding** | Load source files, map them to the canonical model, validate, and build a dry-run plan per contract | Built |
| **Crossing** | Execute the plan against the target, with retries and live progress | Next |
| **Manifest** | Results per item and step, ID register, reconciliation, exceptions, Excel export | Planned |
| **Return Crossing** | Roll back selected contracts or users through the vendor delete API | Planned |

The full design is in [docs/DESIGN.md](docs/DESIGN.md). How the console is built, and how to add a
feature to it, is in [docs/CONSOLE.md](docs/CONSOLE.md).

## Run it

Needs JDK 17, Maven 3.9 and Node 18+.

```powershell
# Backend (http://localhost:8095)
cd ferry-server
mvn spring-boot:run

# Console (http://localhost:5196), in a second terminal
cd ferry-console
npm install
npm run dev
```

The backend reads its configuration from [`config/`](config/) and keeps its run store and ID register
in `ferry-server/data/` (git-ignored, because it can hold customer data).

| Variable | Default | Purpose |
|---|---|---|
| `FERRY_PORT` | `8095` | Backend port |
| `FERRY_CONFIG_DIR` | `../config` | Folder holding the JSON configuration |
| `FERRY_DATA_DIR` | `./data` | Folder for the embedded database |
| `FERRY_ENV` | value of `active` in `environments.json` | Target environment to use |

## Configuration

Everything specific to a bank or a target lives in `config/`, never in code:

| File | Holds |
|---|---|
| `environments.json` | Target URLs, legal entity, live-call switch per environment. No secrets. |
| `reference-data.json` | Service definitions and roles: source reference → target ID |
| `mapping-profiles/*.json` | Source columns → canonical fields, per source system and record type |
| `validation-rules.json` | Field-level checks per record type |
| `assumptions.json` | The assumptions register: open vendor questions and the choice in force |
| `target-temenos.json` | DataMigration API paths, success rules, vendor field rules |

## Safety

- Nothing is sent to a target until Crossing is built and the environment's `liveCalls` switch is on.
- Credentials are entered at run time and never written to disk.
- Source data stays on this machine, in the git-ignored `data/` folder.
