import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { Badge, Banner, Button, Card, Json, Stat, statusTone } from "../../ui.jsx";

export default function PlanCard({ batch, onChanged, version }) {
  const { operator } = useFerry();
  const [voyages, setVoyages] = useState(null);
  const [filter, setFilter] = useState("");
  const [open, setOpen] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const planned = batch.status === "PLANNED";
  const canPlan = batch.status === "VALIDATED" || planned;

  useEffect(() => {
    if (!planned) { setVoyages(null); return; }
    api.get(`/api/boarding/batches/${batch.batch_id}/plan`).then(setVoyages).catch((e) => setError(e.message));
  }, [batch.batch_id, planned, version]);
  useEffect(() => { setOpen(null); setError(""); }, [batch.batch_id]);

  const run = async () => {
    setBusy(true); setError("");
    try {
      await api.post(`/api/boarding/batches/${batch.batch_id}/plan`, { performedBy: operator });
      onChanged();
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };

  const counts = { READY: 0, PARTIAL: 0, BLOCKED: 0 };
  (voyages ?? []).forEach((v) => { counts[v.status] = (counts[v.status] ?? 0) + 1; });
  const shown = (voyages ?? []).filter((v) => !filter || v.status === filter);

  return (
    <Card title="3 · Plan (dry run)"
      sub="For each contract, the calls Crossing will make, in order, and the exact request each would send. Nothing is sent."
      actions={<Button onClick={run} disabled={!canPlan || busy}>{busy ? "Planning…" : planned ? "Plan again" : "Build plan"}</Button>}>
      {error && <Banner tone="danger">{error}</Banner>}
      {!canPlan && <p className="muted" style={{ margin: 0 }}>Validate the batch first.</p>}

      {voyages && (
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <div className="row">
            <Stat value={counts.READY} label="Ready" tone="ok" />
            <Stat value={counts.PARTIAL} label="Partly ready" tone={counts.PARTIAL ? "warn" : undefined} />
            <Stat value={counts.BLOCKED} label="Blocked" tone={counts.BLOCKED ? "danger" : undefined} />
            <div className="row" style={{ marginLeft: "auto" }}>
              {["", "READY", "PARTIAL", "BLOCKED"].map((s) => (
                <Button key={s || "all"} small kind={filter === s ? "solid" : "ghost"} onClick={() => setFilter(s)}>{s || "All"}</Button>
              ))}
            </div>
          </div>

          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Contract</th><th>Primary CIF</th><th>Service definition</th><th>Status</th><th>Steps</th><th>Assumptions</th><th /></tr>
              </thead>
              <tbody>
                {shown.length === 0 && <tr><td colSpan={7} className="empty">No contracts with this status.</td></tr>}
                {shown.map((v) => {
                  const isOpen = open === v.contractRef;
                  return (
                    <VoyageRows key={v.contractRef} v={v} isOpen={isOpen} toggle={() => setOpen(isOpen ? null : v.contractRef)} />
                  );
                })}
              </tbody>
            </table>
          </div>
          <p className="muted small" style={{ margin: 0 }}>
            Values like <code>{"{{CONTRACT:B-100.contractId}}"}</code> are IDs the target creates in an earlier step; Crossing fills them in.
            “Already done” steps were found in the ID register for this environment and will not be sent again.
          </p>
        </div>
      )}
    </Card>
  );
}

function VoyageRows({ v, isOpen, toggle }) {
  const [payloadOf, setPayloadOf] = useState(null);
  const blocked = v.steps.filter((s) => s.status === "BLOCKED").length;
  return (
    <>
      <tr>
        <td><code>{v.contractRef}</code></td>
        <td><code>{v.primaryCif}</code></td>
        <td>{v.serviceDefinitionRef}</td>
        <td><Badge tone={statusTone(v.status)}>{v.status}</Badge></td>
        <td className="small">{v.steps.length} {v.steps.length === 1 ? "step" : "steps"}{blocked ? `, ${blocked} blocked` : ""}
          {v.excludedAccounts?.length > 0 && <div className="muted">Accounts left out: {v.excludedAccounts.join(", ")}</div>}</td>
        <td>{v.assumptions.map((a) => <Badge key={a} tone="sea">{a}</Badge>)}</td>
        <td><button className="btn ghost small" onClick={toggle} aria-expanded={isOpen}>{isOpen ? "Hide" : "Steps"}</button></td>
      </tr>
      {isOpen && (
        <tr>
          <td colSpan={7} style={{ background: "var(--surface-2)" }}>
            <ol style={{ margin: 0, paddingLeft: 20, display: "flex", flexDirection: "column", gap: 8 }}>
              {v.steps.map((s) => (
                <li key={s.stepId}>
                  <div className="row" style={{ gap: 8 }}>
                    <b>{s.label}</b>
                    <code className="small">{s.operation}</code>
                    <Badge tone={s.status === "ALREADY_DONE" ? "ok" : statusTone(s.status)}>{s.status === "ALREADY_DONE" ? `already done · ${s.registeredTargetId}` : s.status.toLowerCase()}</Badge>
                    {s.assumptions.map((a) => <Badge key={a} tone="sea">{a}</Badge>)}
                    <button className="btn ghost small" onClick={() => setPayloadOf(payloadOf === s.stepId ? null : s.stepId)}>
                      {payloadOf === s.stepId ? "Hide request" : "Request"}
                    </button>
                  </div>
                  {s.reasons.length > 0 && <div className="small" style={{ color: "var(--danger)" }}>{s.reasons.join(" ")}</div>}
                  {s.dependsOn.length > 0 && <div className="small muted">After: {s.dependsOn.join(", ")}</div>}
                  {payloadOf === s.stepId && <div style={{ marginTop: 6 }}><Json value={s.payload} /></div>}
                </li>
              ))}
            </ol>
          </td>
        </tr>
      )}
    </>
  );
}
