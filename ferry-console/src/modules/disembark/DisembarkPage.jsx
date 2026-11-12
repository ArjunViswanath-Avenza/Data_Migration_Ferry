import { useEffect, useMemo, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { Badge, Banner, Button, Card, Field, PageHeader, Stat, Stats, Table, plural, when } from "../../kit/ui.jsx";

const read = (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
const write = (k, v) => { try { localStorage.setItem(k, v); } catch { /* storage blocked */ } };

const STATE = {
  COUNTED: ["Will be removed", "warn"], DELETED: ["Removed", "ok"], NO_IDS: ["Nothing linked", "neutral"],
  OFF: ["Off in config", "neutral"], MISSING_TABLE: ["Table not in this database", "info"],
};

/**
 * Disembark: un-enrol one customer. Script first (no connection needed), preview read-only with a
 * connection, delete only when config/unenroll.json allows it. The password is never kept.
 */
export default function DisembarkPage({ module }) {
  const { operator } = useFerry();
  const [profile, setProfile] = useState(null);
  const [cif, setCif] = useState("");
  const [db, setDb] = useState(() => ({ host: read("ferry:disembark:host", ""), port: read("ferry:disembark:port", "3306"),
    schema: read("ferry:disembark:schema", ""), user: read("ferry:disembark:user", ""), password: "" }));
  const [plan, setPlan] = useState(null);
  const [script, setScript] = useState(null);
  const [done, setDone] = useState(null);
  const [confirm, setConfirm] = useState("");
  const [ack, setAck] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [history, setHistory] = useState([]);
  const [copied, setCopied] = useState(false);

  const loadHistory = () => api.get("/api/disembark/history?limit=10").then(setHistory).catch(() => {});
  useEffect(() => {
    api.get("/api/disembark/profile").then(setProfile).catch((e) => setError(e.message));
    loadHistory();
  }, []);
  useEffect(() => { ["host", "port", "schema", "user"].forEach((k) => write(`ferry:disembark:${k}`, db[k])); }, [db.host, db.port, db.schema, db.user]);
  useEffect(() => { setPlan(null); setScript(null); setDone(null); setConfirm(""); setAck(false); }, [cif]);

  const connected = db.host.trim() && db.user.trim();
  const keyOk = profile && new RegExp(profile.keyPattern).test(cif.trim());
  const body = (extra = {}) => ({
    cif: cif.trim(), performedBy: operator, schema: db.schema.trim() || undefined,
    db: connected ? { host: db.host.trim(), port: Number(db.port) || 3306, schema: db.schema.trim() || undefined, user: db.user.trim(), password: db.password } : null,
    ...extra,
  });

  const run = async (kind) => {
    setBusy(kind); setError("");
    try {
      if (kind === "script") {
        const r = await api.post("/api/disembark/script", body());
        setScript(r);
        if (r.preview) setPlan(r.preview);
      } else if (kind === "preview") {
        setPlan(await api.post("/api/disembark/preview", body()));
      } else {
        setDone(await api.post("/api/disembark/execute", body({ confirmCif: confirm.trim(), acknowledgeWarnings: ack })));
      }
      loadHistory();
    } catch (e) { setError(e.message); }
    finally { setBusy(""); }
  };

  const findings = plan ? plan.checks.filter((c) => c.findings.length > 0) : [];
  const steps = useMemo(() => {
    const result = done?.steps ?? plan?.steps;
    if (result) return result;
    return (profile?.deleteSteps ?? []).map((d) => ({ ...d, rows: null, state: d.confirmed === false ? "OFF" : null }));
  }, [profile, plan, done]);
  const tablesWithRows = plan ? plan.steps.filter((s) => s.rows > 0).length : 0;

  const copy = async () => {
    try { await navigator.clipboard.writeText(script.sql); setCopied(true); setTimeout(() => setCopied(false), 1500); }
    catch { setError("Copying is blocked in this browser; use Download instead."); }
  };
  const download = () => {
    const url = URL.createObjectURL(new Blob([script.sql], { type: "application/sql;charset=utf-8" }));
    const a = Object.assign(document.createElement("a"), { href: url, download: script.fileName });
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  return (
    <>
      <PageHeader eyebrow="Tools" icon={module.icon} title="Disembark" description={module.summary} />
      {error && <Banner tone="danger" title="That did not work.">{error}</Banner>}
      {profile && !profile.allowExecute && (
        <Banner tone="info" title="Deleting directly is switched off.">
          Disembark writes a script for a DBA to review and run. To let the console delete, set <code>allowExecute</code> to true
          in <code>config/unenroll.json</code>.
        </Banner>
      )}

      <div className="grid-2">
        <Card icon="userMinus" title="Customer" sub={`One ${profile?.keyLabel ?? "core customer ID"}. Everything linked to it is found first, then removed children first.`}>
          <Field label={profile?.keyLabel ?? "Core customer ID"} htmlFor="de-cif"
            hint={cif && !keyOk ? "Letters, digits, - and _ only." : "Logins, contracts, groups and approval rules are looked up from it."}>
            <input id="de-cif" value={cif} onChange={(e) => setCif(e.target.value)} placeholder="e.g. 219000113" autoComplete="off" spellCheck={false} />
          </Field>
          <div className="row">
            <Button icon="code" onClick={() => run("script")} disabled={!keyOk || !!busy}>{busy === "script" ? "Writing…" : "Write script"}</Button>
            <Button variant="secondary" icon="search" onClick={() => run("preview")} disabled={!keyOk || !connected || !!busy}
              title={connected ? "Count what would be removed (read-only)" : "Enter the database connection to preview"}>
              {busy === "preview" ? "Counting…" : "Preview (read-only)"}
            </Button>
          </div>
          <p className="xs muted" style={{ margin: 0 }}>
            The script needs no connection: it finds the IDs, counts, checks and deletes when a DBA runs it, and never commits by itself.
            {connected ? " With the connection below it also carries today's counts." : ""}
          </p>
        </Card>

        <Card icon="database" title="Database connection" sub="Optional. Needed for a preview with real counts. The password is sent with each request and never stored.">
          <div className="de-db">
            <Field label="Host" htmlFor="de-host"><input id="de-host" value={db.host} onChange={(e) => setDb({ ...db, host: e.target.value })} placeholder="localhost" autoComplete="off" /></Field>
            <Field label="Port" htmlFor="de-port"><input id="de-port" value={db.port} onChange={(e) => setDb({ ...db, port: e.target.value })} inputMode="numeric" /></Field>
            <Field label="Schema" htmlFor="de-schema"><input id="de-schema" value={db.schema} onChange={(e) => setDb({ ...db, schema: e.target.value })} placeholder={profile?.schema ?? "dbxdb"} autoComplete="off" /></Field>
            <Field label="User" htmlFor="de-user" hint="Read access is enough for a preview."><input id="de-user" value={db.user} onChange={(e) => setDb({ ...db, user: e.target.value })} autoComplete="off" /></Field>
            <Field label="Password" htmlFor="de-pass"><input id="de-pass" type="password" value={db.password} onChange={(e) => setDb({ ...db, password: e.target.value })} autoComplete="new-password" /></Field>
          </div>
        </Card>
      </div>

      {plan && (
        <>
          <Stats>
            <Stat value={plan.total.toLocaleString()} label="Rows to remove" tone="brand" />
            <Stat value={plan.resolved.contractId ?? 0} label="Contracts" />
            <Stat value={plan.resolved.customerId ?? 0} label="Logins" />
            <Stat value={tablesWithRows} label="Tables with rows" />
            <Stat value={findings.length} label="Shared with other CIFs" tone={findings.length ? "danger" : "ok"} />
          </Stats>
          {plan.total === 0 && <Banner tone="info" title={`Nothing found for ${plan.cif}.`}>Check the ID and the schema; the customer may already be off.</Banner>}
          {findings.map((c) => (
            <Banner key={c.id} tone="danger" title={`${c.label}.`}>
              {c.message}
              <ul className="de-list">{c.findings.map((f) => <li key={f.item}><code>{f.item}</code> is also linked to <code>{f.others}</code></li>)}</ul>
            </Banner>
          ))}
          {plan.overGuard.length > 0 && (
            <Banner tone="warn" title="Over the guard.">
              {plan.overGuard.join(", ")} would lose more than {profile?.maxRowsPerTableGuard} rows. Deleting stops; the script leaves these commented out.
            </Banner>
          )}
          {plan.notes.length > 0 && <Banner tone="info"><ul className="de-list">{plan.notes.map((n) => <li key={n}>{n}</li>)}</ul></Banner>}
        </>
      )}

      {done && (
        <Banner tone="ok" title={`${done.cif} is off: ${done.deleted.toLocaleString()} rows removed and committed.`}>
          Run {done.runId}. The table below shows what each step removed.
        </Banner>
      )}

      {script && (
        <Card icon="code" title="Script" sub="Review it, run it on a backed-up database, check the verify section shows zeros, then COMMIT yourself."
          actions={<>
            <Button size="sm" variant="secondary" icon="copy" onClick={copy}>{copied ? "Copied" : "Copy"}</Button>
            <Button size="sm" variant="secondary" icon="download" onClick={download}>Download {script.fileName}</Button>
          </>}>
          <pre className="de-script" tabIndex={0} aria-label="Un-enrolment script">{script.sql}</pre>
        </Card>
      )}

      {profile?.allowExecute && plan && !done && (
        <Card icon="alert" title="Un-enrol now" sub="Deletes in one transaction and commits. Take a backup first.">
          <div className="row" style={{ alignItems: "flex-end" }}>
            <div style={{ flex: "1 1 240px" }}>
              <Field label={`Type ${plan.cif} to confirm`} htmlFor="de-confirm">
                <input id="de-confirm" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="off" />
              </Field>
            </div>
            {findings.length > 0 && (
              <label className="de-check">
                <input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} />
                <span>I have read the shared-CIF warnings and still want to go ahead</span>
              </label>
            )}
            <Button variant="danger" icon="userMinus" onClick={() => run("execute")}
              disabled={confirm.trim() !== plan.cif || (findings.length > 0 && !ack) || plan.overGuard.length > 0 || !!busy}>
              {busy === "execute" ? "Removing…" : "Un-enrol now"}
            </Button>
          </div>
        </Card>
      )}

      <Card icon="layers" title={plan || done ? "What is removed" : "What Disembark removes"}
        sub={plan || done ? "In this order, inside one transaction." : `From config/unenroll.json: ${plural(steps.length, "step")}, children first, anchors last. Steps marked off are listed but never run.`}>
        <Table
          columns={[
            { key: "group", label: "Group" },
            { key: "table", label: "Table", render: (s) => <code>{s.table}</code> },
            { key: "column", label: "Matched on", render: (s) => <span className="xs"><code>{s.column}</code> = {s.using === "cif" ? "the CIF" : s.using}</span> },
            { key: "rows", label: "Rows", num: true, render: (s) => s.rows == null ? <span className="faint">—</span> : s.rows.toLocaleString() },
            { key: "state", label: "", render: (s) => s.state ? <Badge tone={STATE[s.state]?.[1] ?? "neutral"}>{s.state === "COUNTED" && s.rows === 0 ? "Nothing to remove" : STATE[s.state]?.[0] ?? s.state}</Badge> : null },
            { key: "note", label: "Note", render: (s) => s.note ? <span className="xs muted">{s.note}</span> : null },
          ]}
          rows={steps.map((s, i) => ({ ...s, key: `${s.table}#${s.column}#${i}` }))}
        />
      </Card>

      {history.length > 0 && (
        <Card icon="history" title="Recent runs" sub="Every script, preview and un-enrolment is recorded here, with who ran it.">
          <Table
            columns={[
              { key: "started_at", label: "When", render: (r) => when(r.started_at) },
              { key: "action", label: "What" },
              { key: "status", label: "Result", render: (r) => <Badge tone={r.status === "SUCCESS" ? "ok" : r.status === "FAILED" ? "danger" : "neutral"}>{r.status}</Badge> },
              { key: "performed_by", label: "By" },
              { key: "details", label: "Customer", render: (r) => detail(r.details) },
            ]}
            rows={history.map((r) => ({ ...r, key: r.run_id }))}
          />
        </Card>
      )}
    </>
  );
}

function detail(raw) {
  try {
    const d = typeof raw === "string" ? JSON.parse(raw) : raw;
    if (!d) return null;
    return <span className="xs"><code>{d.cif}</code>{d.rows >= 0 ? ` · ${d.rows} rows` : ""}{d.database ? ` · ${d.database}` : ""}</span>;
  } catch { return null; }
}
