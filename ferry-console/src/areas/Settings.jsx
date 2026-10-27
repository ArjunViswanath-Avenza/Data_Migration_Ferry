import { useEffect, useState } from "react";
import { api } from "../api.js";
import { useFerry } from "../state.jsx";
import { Badge, Banner, Button, Card, Table, statusTone, when } from "../ui.jsx";

export default function Settings({ onConfigChanged }) {
  const { status, operator } = useFerry();
  const [assumptions, setAssumptions] = useState([]);
  const [ref, setRef] = useState(null);
  const [runs, setRuns] = useState([]);
  const [audit, setAudit] = useState([]);
  const [message, setMessage] = useState(null);

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
      setMessage({ tone: "info", text: "Configuration reloaded from the config folder." });
      onConfigChanged?.();
      load();
    } catch (e) { setMessage({ tone: "danger", text: e.message }); }
  };

  const env = status?.environment;

  return (
    <>
      <div className="page-head">
        <h1>Settings</h1>
        <p>Where the tool points, the open questions it is working around, and what has been done so far.
          Values come from the <code>config</code> folder; edit the files and reload.</p>
      </div>

      {message && <Banner tone={message.tone}>{message.text}</Banner>}

      <div className="grid-2">
        <Card title="Target environment" actions={<Button kind="ghost" small onClick={reload}>Reload config</Button>}>
          {env ? (
            <Table
              columns={[{ key: "k", label: "Setting" }, { key: "v", label: "Value" }]}
              rows={[
                { k: "Environment", v: `${env.label} (${env.id})` },
                { k: "Target", v: env.target },
                { k: "API host", v: <code>{env.hostUrl}</code> },
                { k: "Default legal entity", v: env.defaultLegalEntityId },
                { k: "Live calls", v: env.liveCalls ? <Badge tone="live">ON</Badge> : <Badge tone="sea">Off · safe mode</Badge> },
                { k: "Config folder", v: <code>{status.configDir}</code> },
                { k: "Data folder", v: <code>{status.dataDir}</code> },
              ]}
            />
          ) : <p className="muted">Waiting for the server…</p>}
        </Card>

        <Card title="Reference data" sub="Source reference → target ID">
          <Table
            columns={[
              { key: "kind", label: "Type" }, { key: "ref", label: "Source ref" },
              { key: "targetId", label: "Target ID", render: (r) => <code>{r.targetId}</code> },
              { key: "label", label: "Label" },
            ]}
            rows={ref ? [
              ...ref.serviceDefinitions.map((s) => ({ ...s, kind: `Service definition · ${s.contractType}`, key: "sd" + s.ref })),
              ...ref.roles.map((r) => ({ ...r, kind: "Role", key: "role" + r.ref })),
            ] : []}
          />
        </Card>
      </div>

      <Card title="Assumptions register"
        sub="Open questions the vendor material does not settle, and the choice the tool makes today. Changing a choice is recorded with your name.">
        <Table
          columns={[
            { key: "id", label: "ID", render: (a) => <b>{a.id}</b> },
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
              setMessage({ tone: "info", text: `${next.id} set to ${next.choice}.` });
              onConfigChanged?.();
              load();
            }} onError={(text) => setMessage({ tone: "danger", text })} /> },
            { key: "status", label: "Status", render: (a) => <Badge tone={statusTone(a.status)}>{a.status}</Badge> },
            { key: "affects", label: "Affects", render: (a) => <span className="small">{a.affects.join(" · ")}</span> },
          ]}
          rows={assumptions.map((a) => ({ ...a, key: a.id }))}
        />
      </Card>

      <div className="grid-2">
        <Card title="Recent runs">
          <Table
            columns={[
              { key: "started_at", label: "When", render: (r) => when(r.started_at) },
              { key: "area", label: "Area" }, { key: "action", label: "Action" },
              { key: "status", label: "Status", render: (r) => <Badge tone={statusTone(r.status)}>{r.status}</Badge> },
              { key: "performed_by", label: "By" },
            ]}
            rows={runs.map((r) => ({ ...r, key: r.run_id }))}
            empty="No runs yet."
          />
        </Card>
        <Card title="Audit trail">
          <Table
            columns={[
              { key: "occurred_at", label: "When", render: (r) => when(r.occurred_at) },
              { key: "actor", label: "By" }, { key: "action", label: "Action" }, { key: "subject", label: "Subject" },
            ]}
            rows={audit}
            empty="No changes recorded yet."
          />
        </Card>
      </div>
    </>
  );
}

function AssumptionChoice({ a, onSaved, onError }) {
  const { operator } = useFerry();
  const [value, setValue] = useState(a.choice);
  const [busy, setBusy] = useState(false);
  useEffect(() => setValue(a.choice), [a.choice]);

  const save = async () => {
    if (!operator.trim()) { onError("Enter your name in the header first; assumption changes are recorded with it."); return; }
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
      {value !== a.choice && <Button small onClick={save} disabled={busy}>Save</Button>}
    </div>
  );
}
