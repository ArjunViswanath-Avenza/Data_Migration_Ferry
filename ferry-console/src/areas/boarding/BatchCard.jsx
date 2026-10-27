import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { Badge, Banner, Button, Card, Field, Table, statusTone, when } from "../../ui.jsx";

const STAGES = ["STAGED", "VALIDATED", "PLANNED"];

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
      setName(""); setMessage(null); setShowList(false);
      onSelect(b.batch_id);
    } catch (e) { setMessage(e.message); }
  };

  const counts = {};
  (batch?.counts ?? []).forEach((c) => {
    counts[c.record_type] = counts[c.record_type] || {};
    counts[c.record_type][c.status] = c.records;
  });

  return (
    <Card
      title={batch ? `Batch ${batch.batch_id}` : "Batch"}
      sub={batch ? `${batch.name}${batch.source_system ? ` · source ${batch.source_system}` : ""} · created ${when(batch.created_at)} by ${batch.created_by || "—"}` : "A batch is one set of source files that sails together."}
      actions={<Button kind="ghost" small onClick={() => setShowList((s) => !s)}>{showList ? "Hide batches" : "Switch or create batch"}</Button>}
    >
      {batch && (
        <div className="row" style={{ marginBottom: showList ? 14 : 0 }}>
          {STAGES.map((s, i) => {
            const reached = STAGES.indexOf(batch.status) >= i;
            return <Badge key={s} tone={reached ? (s === batch.status ? "sea" : "ok") : "neutral"}>{i + 1}. {s.toLowerCase()}</Badge>;
          })}
          <span className="muted small" style={{ marginLeft: 8 }}>
            {Object.keys(counts).length === 0 ? "No files loaded yet." :
              Object.entries(counts).map(([type, byStatus]) =>
                `${type}: ${Object.values(byStatus).reduce((a, b) => a + b, 0)}`).join(" · ")}
          </span>
        </div>
      )}

      {(showList || !batch) && (
        <div className="stack" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          <div className="row" style={{ alignItems: "flex-end" }}>
            <div style={{ flex: "2 1 220px" }}>
              <Field label="New batch name" htmlFor="batch-name">
                <input id="batch-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Retail mock run 1" />
              </Field>
            </div>
            <div style={{ flex: "1 1 160px" }}>
              <Field label="Source system (optional)" htmlFor="batch-source">
                <input id="batch-source" value={source} onChange={(e) => setSource(e.target.value)} placeholder="e.g. LEGACYCORE" />
              </Field>
            </div>
            <Button onClick={create} disabled={!name.trim()}>Create batch</Button>
          </div>
          <p className="muted small" style={{ margin: 0 }}>
            The source system picks the mapping profiles used for this batch's files. Leave it blank when the files use the template headers.
          </p>
          {message && <Banner tone="danger">{message}</Banner>}
          <Table
            columns={[
              { key: "batch_id", label: "Batch", render: (b) => <code>{b.batch_id}</code> },
              { key: "name", label: "Name" },
              { key: "source_system", label: "Source" },
              { key: "status", label: "Stage", render: (b) => <Badge tone={statusTone(b.status)}>{b.status}</Badge> },
              { key: "total_records", label: "Records", num: true },
              { key: "invalid_records", label: "Invalid", num: true,
                render: (b) => b.invalid_records > 0 ? <b style={{ color: "var(--danger)" }}>{b.invalid_records}</b> : "0" },
              { key: "created_at", label: "Created", render: (b) => when(b.created_at) },
              { key: "open", label: "", render: (b) => (
                <Button small kind={b.batch_id === batch?.batch_id ? "solid" : "ghost"}
                  onClick={() => { onSelect(b.batch_id); setShowList(false); }}>
                  {b.batch_id === batch?.batch_id ? "Open" : "Select"}
                </Button>) },
            ]}
            rows={batches.map((b) => ({ ...b, key: b.batch_id }))}
            empty="No batches yet. Create the first one above."
          />
        </div>
      )}
    </Card>
  );
}
