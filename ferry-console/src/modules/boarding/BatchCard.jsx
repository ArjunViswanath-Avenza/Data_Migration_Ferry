import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { Badge, Banner, Button, Card, Field, Table, statusTone, when } from "../../kit/ui.jsx";

export default function BatchCard({ batch, onSelect, version }) {
  const { operator } = useFerry();
  const [batches, setBatches] = useState([]);
  const [name, setName] = useState("");
  const [source, setSource] = useState("");
  const [message, setMessage] = useState(null);
  const [showList, setShowList] = useState(false);

  useEffect(() => { api.get("/api/boarding/batches?limit=50").then(setBatches).catch(() => {}); }, [version]);

  const create = async () => {
    try {
      const b = await api.post("/api/boarding/batches", { name, sourceSystem: source, performedBy: operator });
      setName(""); setSource(""); setMessage(null); setShowList(false);
      onSelect(b.batch_id);
    } catch (e) { setMessage(e.message); }
  };

  const types = {};
  (batch?.counts ?? []).forEach((c) => { types[c.record_type] = (types[c.record_type] ?? 0) + c.records; });
  const open = showList || !batch;

  return (
    <Card icon="layers"
      title={batch ? batch.name : "Batch"}
      sub={batch
        ? <><code>{batch.batch_id}</code>{batch.source_system ? ` · source ${batch.source_system}` : " · template headers"} · created {when(batch.created_at)} by {batch.created_by || "—"}</>
        : "A batch is one set of source files that travels together."}
      actions={batch && (
        <>
          <Badge tone={statusTone(batch.status)} dot>{batch.status.toLowerCase()}</Badge>
          <Button variant="secondary" size="sm" icon={showList ? "chevronLeft" : "layers"} onClick={() => setShowList((s) => !s)}>
            {showList ? "Close" : "Switch or create"}
          </Button>
        </>
      )}>
      {batch && Object.keys(types).length > 0 && !open && (
        <div className="row">
          {Object.entries(types).map(([t, n]) => <Badge key={t}>{t.replace("_", " ").toLowerCase()} · {n}</Badge>)}
        </div>
      )}

      {open && (
        <>
          <div className="row" style={{ alignItems: "flex-end" }}>
            <div style={{ flex: "2 1 240px" }}>
              <Field label="New batch name" htmlFor="batch-name">
                <input id="batch-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Retail mock run 1" />
              </Field>
            </div>
            <div style={{ flex: "1 1 180px" }}>
              <Field label="Source system (optional)" htmlFor="batch-source">
                <input id="batch-source" value={source} onChange={(e) => setSource(e.target.value)} placeholder="e.g. LEGACYCORE" />
              </Field>
            </div>
            <Button icon="plus" onClick={create} disabled={!name.trim()}>Create batch</Button>
          </div>
          <p className="muted small">The source system picks the mapping profiles for this batch's files. Leave it blank when the files use the template headers.</p>
          {message && <Banner tone="danger">{message}</Banner>}
          <Table
            columns={[
              { key: "name", label: "Batch", render: (b) => <div><b>{b.name}</b><div className="xs faint mono">{b.batch_id}</div></div> },
              { key: "source_system", label: "Source" },
              { key: "status", label: "Stage", render: (b) => <Badge tone={statusTone(b.status)} dot>{b.status.toLowerCase()}</Badge> },
              { key: "total_records", label: "Records", num: true },
              { key: "invalid_records", label: "Invalid", num: true,
                render: (b) => b.invalid_records > 0 ? <b style={{ color: "var(--danger)" }}>{b.invalid_records}</b> : "0" },
              { key: "created_at", label: "Created", render: (b) => <span className="small">{when(b.created_at)}</span> },
              { key: "open", label: "", render: (b) => (
                <Button size="sm" variant={b.batch_id === batch?.batch_id ? "primary" : "secondary"}
                  onClick={() => { onSelect(b.batch_id); setShowList(false); }}>
                  {b.batch_id === batch?.batch_id ? "Current" : "Open"}
                </Button>) },
            ]}
            rows={batches.map((b) => ({ ...b, key: b.batch_id }))}
            empty="No batches yet. Create the first one above."
          />
        </>
      )}
    </Card>
  );
}
