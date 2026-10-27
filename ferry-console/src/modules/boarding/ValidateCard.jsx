import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { AssumptionTag, Badge, Banner, Button, Card, EmptyState, Stat, Stats, Table, Tabs, severityTone } from "../../kit/ui.jsx";

export default function ValidateCard({ batch, onChanged, version }) {
  const { operator } = useFerry();
  const [summary, setSummary] = useState(null);
  const [findings, setFindings] = useState(null);
  const [view, setView] = useState("checks");
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

  const totals = {};
  (batch.counts ?? []).forEach((c) => { totals[c.status] = (totals[c.status] ?? 0) + c.records; });
  const errors = findings ? findings.summary.filter((f) => f.severity === "ERROR").reduce((a, f) => a + f.occurrences, 0) : null;
  const warnings = findings ? findings.summary.filter((f) => f.severity !== "ERROR").reduce((a, f) => a + f.occurrences, 0) : null;

  return (
    <Card icon="shield" title="Validate"
      sub="Field rules, checks across record types, and the target's own rules. Records that relied on an open question carry its assumption ID."
      actions={<Button icon={validated ? "refresh" : "shield"} variant={validated ? "secondary" : "primary"} onClick={run} disabled={!staged || busy}>
        {busy ? "Checking…" : validated ? "Validate again" : "Validate"}</Button>}>
      {error && <Banner tone="danger">{error}</Banner>}
      {!validated && (
        <EmptyState icon="shield" title={staged ? "Ready to check" : "Nothing to check yet"}>
          {staged ? "Files are loaded. Validate to see which records can travel and why the others cannot." : "Load files first."}
        </EmptyState>
      )}

      {validated && (
        <>
          <Stats>
            <Stat value={(totals.VALID ?? 0).toLocaleString()} label="Valid records" tone="ok" />
            <Stat value={(totals.INVALID ?? 0).toLocaleString()} label="Invalid records" tone={totals.INVALID ? "danger" : undefined} />
            <Stat value={(totals.EXCLUDED ?? 0).toLocaleString()} label="Left out by assumption" tone={totals.EXCLUDED ? "warn" : undefined} />
            <Stat value={errors?.toLocaleString()} label="Errors" tone={errors ? "danger" : undefined} />
            <Stat value={warnings?.toLocaleString()} label="Warnings" tone={warnings ? "warn" : undefined} />
          </Stats>
          {summary && <p className="xs muted">Checked {summary.records.toLocaleString()} records just now.</p>}

          {findings && (
            <>
              <Tabs value={view} onChange={setView} label="Findings view" items={[
                { id: "checks", label: "By check", count: findings.summary.length },
                { id: "rows", label: "Every finding", count: findings.findings.length },
              ]} />
              {view === "checks" ? (
                <Table
                  columns={[
                    { key: "severity", label: "Severity", render: (f) => <Badge tone={severityTone(f.severity)} dot>{f.severity.toLowerCase()}</Badge> },
                    { key: "rule_id", label: "Check", render: (f) => <code>{f.rule_id}</code> },
                    { key: "record_type", label: "Type" },
                    { key: "occurrences", label: "Rows", num: true },
                    { key: "assumption_id", label: "Assumption", render: (f) => f.assumption_id ? <AssumptionTag id={f.assumption_id} /> : "" },
                    { key: "example", label: "Example" },
                  ]}
                  rows={findings.summary.map((f, i) => ({ ...f, key: i }))}
                  empty="No findings. Every record passed."
                />
              ) : (
                <>
                  <div className="row">
                    {[["", "All"], ["ERROR", "Errors"], ["WARN", "Warnings"]].map(([s, l]) => (
                      <Button key={l} size="sm" variant={severity === s ? "primary" : "secondary"} onClick={() => setSeverity(s)}>{l}</Button>
                    ))}
                  </div>
                  <Table
                    columns={[
                      { key: "severity", label: "Severity", render: (f) => <Badge tone={severityTone(f.severity)} dot>{f.severity.toLowerCase()}</Badge> },
                      { key: "record_type", label: "Type" },
                      { key: "row_num", label: "Row", num: true },
                      { key: "record_key", label: "Record", render: (f) => <code>{f.record_key}</code> },
                      { key: "field", label: "Field" },
                      { key: "message", label: "What is wrong" },
                      { key: "rule_id", label: "Check", render: (f) => <code className="xs">{f.rule_id}</code> },
                    ]}
                    rows={findings.findings.map((f, i) => ({ ...f, key: i }))}
                  />
                </>
              )}
            </>
          )}
        </>
      )}
    </Card>
  );
}
