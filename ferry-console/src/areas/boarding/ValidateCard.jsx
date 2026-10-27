import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { Badge, Banner, Button, Card, Stat, Table, severityTone } from "../../ui.jsx";

export default function ValidateCard({ batch, onChanged, version }) {
  const { operator } = useFerry();
  const [summary, setSummary] = useState(null);
  const [findings, setFindings] = useState(null);
  const [severity, setSeverity] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const validated = batch.status === "VALIDATED" || batch.status === "PLANNED";
  const staged = (batch.counts ?? []).length > 0;

  useEffect(() => {
    if (!validated) { setFindings(null); return; }
    api.get(`/api/boarding/batches/${batch.batch_id}/findings${severity ? `?severity=${severity}` : ""}`)
      .then(setFindings).catch((e) => setError(e.message));
  }, [batch.batch_id, validated, severity, version]);
  useEffect(() => { setSummary(null); setError(""); }, [batch.batch_id]);

  const run = async () => {
    setBusy(true); setError("");
    try {
      setSummary(await api.post(`/api/boarding/batches/${batch.batch_id}/validate`, { performedBy: operator }));
      onChanged();
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };

  const totals = summarise(batch.counts);

  return (
    <Card title="2 · Validate"
      sub="Field rules, checks across record types, and the target's own rules. Records that relied on an open question are tagged with its assumption ID."
      actions={<Button onClick={run} disabled={!staged || busy}>{busy ? "Checking…" : validated ? "Validate again" : "Validate"}</Button>}>
      {error && <Banner tone="danger">{error}</Banner>}
      {!validated && <p className="muted" style={{ margin: 0 }}>{staged ? "Files are loaded. Validate to see what can sail." : "Load files first."}</p>}

      {validated && (
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <div className="row">
            <Stat value={totals.VALID ?? 0} label="Valid records" tone="ok" />
            <Stat value={totals.INVALID ?? 0} label="Invalid records" tone={totals.INVALID ? "danger" : undefined} />
            <Stat value={totals.EXCLUDED ?? 0} label="Excluded (by assumption)" tone={totals.EXCLUDED ? "warn" : undefined} />
            {summary && <Stat value={summary.errors} label="Errors" tone={summary.errors ? "danger" : undefined} />}
            {summary && <Stat value={summary.warnings} label="Warnings" tone={summary.warnings ? "warn" : undefined} />}
          </div>

          {findings && (
            <>
              <Table
                columns={[
                  { key: "severity", label: "Severity", render: (f) => <Badge tone={severityTone(f.severity)}>{f.severity}</Badge> },
                  { key: "rule_id", label: "Check", render: (f) => <code>{f.rule_id}</code> },
                  { key: "record_type", label: "Type" },
                  { key: "occurrences", label: "Rows", num: true },
                  { key: "assumption_id", label: "Assumption", render: (f) => f.assumption_id ? <Badge tone="sea">{f.assumption_id}</Badge> : "" },
                  { key: "example", label: "Example" },
                ]}
                rows={findings.summary.map((f, i) => ({ ...f, key: i }))}
                empty="No findings. Every record passed."
              />
              <details>
                <summary className="small">Every finding ({findings.findings.length}{findings.findings.length >= 2000 ? "+" : ""})</summary>
                <div className="row" style={{ margin: "8px 0" }}>
                  {["", "ERROR", "WARN"].map((s) => (
                    <Button key={s || "all"} small kind={severity === s ? "solid" : "ghost"} onClick={() => setSeverity(s)}>
                      {s || "All"}
                    </Button>
                  ))}
                </div>
                <Table
                  columns={[
                    { key: "severity", label: "Severity", render: (f) => <Badge tone={severityTone(f.severity)}>{f.severity}</Badge> },
                    { key: "record_type", label: "Type" },
                    { key: "row_num", label: "Row", num: true },
                    { key: "record_key", label: "Record", render: (f) => <code>{f.record_key}</code> },
                    { key: "field", label: "Field" },
                    { key: "message", label: "What is wrong" },
                    { key: "rule_id", label: "Check", render: (f) => <code>{f.rule_id}</code> },
                  ]}
                  rows={findings.findings.map((f, i) => ({ ...f, key: i }))}
                />
              </details>
            </>
          )}
        </div>
      )}
    </Card>
  );
}

function summarise(counts) {
  const out = {};
  (counts ?? []).forEach((c) => { out[c.status] = (out[c.status] ?? 0) + c.records; });
  return out;
}
