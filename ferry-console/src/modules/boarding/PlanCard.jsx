import { Fragment, useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { AssumptionTag, Badge, Banner, Button, Card, EmptyState, Icon, Json, Stat, Stats, plural, statusTone } from "../../kit/ui.jsx";

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
    try { await api.post(`/api/boarding/batches/${batch.batch_id}/plan`, { performedBy: operator }); onChanged(); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };

  const counts = { READY: 0, PARTIAL: 0, BLOCKED: 0 };
  let calls = 0;
  (voyages ?? []).forEach((v) => { counts[v.status] = (counts[v.status] ?? 0) + 1; calls += v.steps.length; });
  const shown = (voyages ?? []).filter((v) => !filter || v.status === filter);

  return (
    <Card icon="plan" title="Plan (dry run)"
      sub="For each contract, the calls Crossing will make, in order, and the exact request each would send. Nothing is sent."
      actions={<Button icon={planned ? "refresh" : "plan"} variant={planned ? "secondary" : "primary"} onClick={run} disabled={!canPlan || busy}>
        {busy ? "Planning…" : planned ? "Plan again" : "Build plan"}</Button>}>
      {error && <Banner tone="danger">{error}</Banner>}
      {!planned && (
        <EmptyState icon="plan" title={canPlan ? "Ready to plan" : "Validate first"}>
          {canPlan ? "Build the plan to see every call per contract, in order, with its exact request." : "The plan is built from validated records only."}
        </EmptyState>
      )}

      {voyages && (
        <>
          <Stats>
            <Stat value={counts.READY} label="Contracts ready" tone="ok" />
            <Stat value={counts.PARTIAL} label="Partly ready" tone={counts.PARTIAL ? "warn" : undefined} />
            <Stat value={counts.BLOCKED} label="Blocked" tone={counts.BLOCKED ? "danger" : undefined} />
            <Stat value={calls} label="Calls planned in total" tone="brand" />
          </Stats>

          <div className="row">
            {[["", "All"], ["READY", "Ready"], ["PARTIAL", "Partly ready"], ["BLOCKED", "Blocked"]].map(([s, l]) => (
              <Button key={l} size="sm" variant={filter === s ? "primary" : "secondary"} onClick={() => setFilter(s)}>{l}</Button>
            ))}
          </div>

          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Contract</th><th>Primary CIF</th><th>Service definition</th><th>Status</th><th>Calls</th><th>Assumptions</th><th /></tr>
              </thead>
              <tbody>
                {shown.length === 0 && <tr><td colSpan={7} className="empty-cell">No contracts with this status.</td></tr>}
                {shown.map((v) => (
                  <Voyage key={v.contractRef} v={v} isOpen={open === v.contractRef}
                    toggle={() => setOpen(open === v.contractRef ? null : v.contractRef)} />
                ))}
              </tbody>
            </table>
          </div>
          <p className="xs muted">
            Values like <code>{"{{CONTRACT:B-100.contractId}}"}</code> are IDs the target creates in an earlier step; Crossing fills them in.
            Steps marked “already done” were found in the ID register for this environment and will not be sent again.
          </p>
        </>
      )}
    </Card>
  );
}

const MARK = { PLANNED: ["planned", "chevronRight"], BLOCKED: ["blocked", "xCircle"], ALREADY_DONE: ["done", "check"] };

function Voyage({ v, isOpen, toggle }) {
  const [payloadOf, setPayloadOf] = useState(null);
  const blocked = v.steps.filter((s) => s.status === "BLOCKED").length;
  return (
    <Fragment>
      <tr className={isOpen ? "expanded" : undefined}>
        <td><b className="mono">{v.contractRef}</b></td>
        <td><code>{v.primaryCif}</code></td>
        <td>{v.serviceDefinitionRef}</td>
        <td><Badge tone={statusTone(v.status)} dot>{v.status.toLowerCase()}</Badge></td>
        <td className="small">{plural(v.steps.length, "call")}{blocked ? <span style={{ color: "var(--danger)" }}> · {blocked} blocked</span> : ""}
          {v.excludedAccounts?.length > 0 && <div className="xs muted">Accounts left out: {v.excludedAccounts.join(", ")}</div>}</td>
        <td><div className="row" style={{ gap: 4 }}>{v.assumptions.map((a) => <AssumptionTag key={a} id={a} />)}</div></td>
        <td><Button size="sm" variant="ghost" icon={isOpen ? "chevronLeft" : "chevronRight"} onClick={toggle} aria-expanded={isOpen}>{isOpen ? "Hide" : "Calls"}</Button></td>
      </tr>
      {isOpen && (
        <tr className="expanded">
          <td colSpan={7}>
            <ol className="timeline">
              {v.steps.map((s) => {
                const [cls, icon] = MARK[s.status] || ["planned", "chevronRight"];
                return (
                  <li key={s.stepId}>
                    <span className={`mark ${cls}`}><Icon name={icon} size={11} strokeWidth={2.6} /></span>
                    <div className="head">
                      <b>{s.label}</b>
                      <code className="xs muted">{s.operation}</code>
                      <Badge tone={statusTone(s.status)}>{s.status === "ALREADY_DONE" ? `already done · ${s.registeredTargetId}` : s.status.toLowerCase()}</Badge>
                      {s.assumptions.map((a) => <AssumptionTag key={a} id={a} />)}
                      <Button size="sm" variant="ghost" icon="code" onClick={() => setPayloadOf(payloadOf === s.stepId ? null : s.stepId)}>
                        {payloadOf === s.stepId ? "Hide request" : "Request"}
                      </Button>
                    </div>
                    {s.reasons.length > 0 && <div className="reason">{s.reasons.join(" ")}</div>}
                    {s.dependsOn.length > 0 && <div className="detail">After {s.dependsOn.join(", ")}</div>}
                    {payloadOf === s.stepId && <div style={{ marginTop: 8 }}><Json value={s.payload} /></div>}
                  </li>
                );
              })}
            </ol>
          </td>
        </tr>
      )}
    </Fragment>
  );
}
