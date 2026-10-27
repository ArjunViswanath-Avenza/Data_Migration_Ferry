import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { AssumptionTag, Badge, Banner, Button, Card, KeyValue, PageHeader, Stat, Stats, Table, Tabs, statusTone, when } from "../../kit/ui.jsx";

/** Settings: one tab per concern, each bound to the route (#/settings/<tab>) so it can be linked to. */
export default function SettingsPage({ module, sub, go, onConfigChanged }) {
  const { status, operator } = useFerry();
  const [assumptions, setAssumptions] = useState([]);
  const [ref, setRef] = useState(null);
  const [runs, setRuns] = useState([]);
  const [audit, setAudit] = useState([]);
  const [message, setMessage] = useState(null);
  const tab = module.tabs.some((t) => t.id === sub) ? sub : module.tabs[0].id;

  const load = () => {
    api.get("/api/assumptions").then(setAssumptions).catch((e) => setMessage({ tone: "danger", text: e.message }));
    api.get("/api/reference-data").then(setRef).catch(() => {});
    api.get("/api/platform/runs?limit=20").then(setRuns).catch(() => {});
    api.get("/api/platform/audit?limit=20").then(setAudit).catch(() => {});
  };
  useEffect(load, []);

  const reload = async () => {
    try {
      await api.post("/api/platform/config/reload", { performedBy: operator });
      setMessage({ tone: "ok", text: "Configuration reloaded from the config folder." });
      onConfigChanged?.();
      load();
    } catch (e) { setMessage({ tone: "danger", text: e.message }); }
  };

  const counts = { ASSUMED: 0, CONFIRMED: 0, OVERRIDDEN: 0 };
  assumptions.forEach((a) => { counts[a.status] = (counts[a.status] ?? 0) + 1; });
  const tabs = module.tabs.map((t) => ({
    ...t,
    count: t.id === "assumptions" ? assumptions.length
      : t.id === "reference" && ref ? ref.serviceDefinitions.length + ref.roles.length
      : undefined,
  }));

  return (
    <>
      <PageHeader eyebrow="Setup" icon={module.icon} title="Settings" description={module.summary}
        actions={<Button variant="secondary" icon="refresh" onClick={reload}>Reload config</Button>} />
      {message && <Banner tone={message.tone}>{message.text}</Banner>}
      <Tabs items={tabs} value={tab} onChange={go} label="Settings sections" />

      {tab === "environment" && <Environment status={status} />}

      {tab === "assumptions" && (
        <>
          <Stats>
            <Stat value={assumptions.length} label="Open questions tracked" tone="brand" />
            <Stat value={counts.ASSUMED} label="Working on an assumption" tone={counts.ASSUMED ? "warn" : undefined} />
            <Stat value={counts.CONFIRMED} label="Confirmed by the vendor" tone="ok" />
            <Stat value={counts.OVERRIDDEN} label="Changed from the default" />
          </Stats>
          <Card icon="book" title="Assumptions register"
            sub="Questions the vendor material does not settle, and the choice the console makes today. A change is recorded with your name.">
            <Table
              columns={[
                { key: "id", label: "ID", render: (a) => <AssumptionTag id={a.id} /> },
                { key: "title", label: "Question", render: (a) => (
                  <div style={{ minWidth: 240 }}>
                    <div style={{ fontWeight: 600 }}>{a.title}</div>
                    <div className="muted small">{a.question}</div>
                    <details className="small" style={{ marginTop: 4 }}>
                      <summary>Evidence</summary>
                      <div className="muted">{a.evidence}</div>
                    </details>
                  </div>
                ) },
                { key: "choice", label: "Choice in force", render: (a) => <AssumptionChoice a={a} onSaved={(next) => {
                  setAssumptions((list) => list.map((x) => (x.id === next.id ? next : x)));
                  setMessage({ tone: "ok", text: `${next.id} set to ${next.choice}.` });
                  onConfigChanged?.();
                  load();
                }} onError={(text) => setMessage({ tone: "danger", text })} /> },
                { key: "status", label: "Status", render: (a) => <Badge tone={statusTone(a.status)} dot>{a.status.toLowerCase()}</Badge> },
                { key: "affects", label: "Affects", render: (a) => <span className="small">{a.affects.join(" · ")}</span> },
              ]}
              rows={assumptions.map((a) => ({ ...a, key: a.id }))}
            />
          </Card>
        </>
      )}

      {tab === "reference" && (
        <Card icon="database" title="Reference data" sub="Source reference → target ID. Edit reference-data.json and reload to change.">
          <Table
            columns={[
              { key: "kind", label: "Type" }, { key: "ref", label: "Source ref", render: (r) => <code>{r.ref}</code> },
              { key: "targetId", label: "Target ID", render: (r) => <code>{r.targetId}</code> },
              { key: "label", label: "Label" },
            ]}
            rows={ref ? [
              ...ref.serviceDefinitions.map((s) => ({ ...s, kind: `Service definition · ${s.contractType}`, key: "sd" + s.ref })),
              ...ref.roles.map((r) => ({ ...r, kind: "Role", key: "role" + r.ref })),
            ] : []}
          />
        </Card>
      )}

      {tab === "history" && (
        <div className="grid-2">
          <Card icon="clock" title="Recent runs">
            <Table
              columns={[
                { key: "started_at", label: "When", render: (r) => <span className="small">{when(r.started_at)}</span> },
                { key: "area", label: "Area" }, { key: "action", label: "Action" },
                { key: "status", label: "Status", render: (r) => <Badge tone={statusTone(r.status)} dot>{r.status.toLowerCase()}</Badge> },
                { key: "performed_by", label: "By" },
              ]}
              rows={runs.map((r) => ({ ...r, key: r.run_id }))}
              empty="No runs yet."
            />
          </Card>
          <Card icon="history" title="Audit trail">
            <Table
              columns={[
                { key: "occurred_at", label: "When", render: (r) => <span className="small">{when(r.occurred_at)}</span> },
                { key: "actor", label: "By" }, { key: "action", label: "Action" }, { key: "subject", label: "Subject" },
              ]}
              rows={audit.map((r, i) => ({ ...r, key: i }))}
              empty="No changes recorded yet."
            />
          </Card>
        </div>
      )}
    </>
  );
}

function Environment({ status }) {
  const env = status?.environment;
  if (!env) return <Card><p className="muted">Waiting for the server…</p></Card>;
  return (
    <div className="grid-2">
      <Card icon="globe" title={env.label} sub={<>Environment <code>{env.id}</code> · target {env.target}</>}
        actions={env.liveCalls ? <Badge tone="live" dot>Live calls on</Badge> : <Badge tone="ok" dot>Safe mode</Badge>}>
        <KeyValue pairs={[
          ["API host", <code>{env.hostUrl}</code>],
          ["Default legal entity", env.defaultLegalEntityId],
          ["Live calls", env.liveCalls ? "On. Crossing will send real requests." : "Off. Nothing is sent; every step stops at the dry run."],
        ]} />
      </Card>
      <Card icon="file" title="Where things live" sub="Edit the files, then reload.">
        <KeyValue pairs={[
          ["Config folder", <code>{status.configDir}</code>],
          ["Data folder", <code>{status.dataDir}</code>],
          ["Version", status.version],
        ]} />
      </Card>
    </div>
  );
}

function AssumptionChoice({ a, onSaved, onError }) {
  const { operator } = useFerry();
  const [value, setValue] = useState(a.choice);
  const [busy, setBusy] = useState(false);
  useEffect(() => setValue(a.choice), [a.choice]);

  const save = async () => {
    if (!operator.trim()) { onError("Enter your name in the top bar first; assumption changes are recorded with it."); return; }
    setBusy(true);
    try { onSaved(await api.put(`/api/assumptions/${a.id}`, { choice: value, performedBy: operator })); }
    catch (e) { onError(e.message); }
    finally { setBusy(false); }
  };

  if (a.options.length === 1) return <span className="small">{a.options[0].label}</span>;
  return (
    <div className="row" style={{ flexWrap: "nowrap", alignItems: "flex-start" }}>
      <select className="inline" aria-label={`Choice for ${a.id}`} value={value} onChange={(e) => setValue(e.target.value)} style={{ maxWidth: 300 }}>
        {a.options.map((o) => <option key={o.value} value={o.value}>{o.label}{o.value === a.defaultChoice ? " (default)" : ""}</option>)}
      </select>
      {value !== a.choice && <Button size="sm" onClick={save} disabled={busy}>Save</Button>}
    </div>
  );
}
