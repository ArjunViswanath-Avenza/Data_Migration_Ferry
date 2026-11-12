import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { Badge, Banner, Button, Card, Stat, Stats, Table, when } from "../../kit/ui.jsx";

const OUTCOME_TONE = {
  SUCCESS: "ok", ALREADY_DONE: "ok", RECOVERED: "info", PARTIAL: "warn", FAILED: "danger", UNKNOWN: "danger", MISMATCH: "danger",
  SKIPPED: "neutral", SKIPPED_BLOCKED: "neutral", SKIPPED_DEPENDENCY: "neutral", SKIPPED_STOPPED: "neutral", QUEUED: "neutral", RUNNING: "brand",
};
const STATE_TONE = { RUNNING: "brand", PAUSED: "warn", COMPLETED: "ok", STOPPED: "neutral", FAILED: "danger" };

/** One run, refreshed while it is running or paused. */
export default function RunCard({ runId }) {
  const { operator } = useFerry();
  const [run, setRun] = useState(null);
  const [open, setOpen] = useState("");
  const [steps, setSteps] = useState([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const load = () => api.get(`/api/crossing/runs/${runId}`).then((r) => { setRun(r); setError(""); }).catch((e) => setError(e.message));
  useEffect(() => { setRun(null); setOpen(""); load(); }, [runId]);
  useEffect(() => {
    if (!run || !["RUNNING", "PAUSED"].includes(run.state)) return undefined;
    const t = setInterval(load, run.state === "RUNNING" ? 1200 : 4000);
    return () => clearInterval(t);
  }, [run?.state, runId]);
  useEffect(() => {
    if (!open) { setSteps([]); return; }
    api.get(`/api/crossing/runs/${runId}/contracts/${encodeURIComponent(open)}`).then(setSteps).catch((e) => setError(e.message));
  }, [open, runId, run?.byOutcome && JSON.stringify(run.byOutcome)]);

  const act = async (what) => {
    setBusy(true);
    try { setRun(await api.post(`/api/crossing/runs/${runId}/${what}`, { performedBy: operator })); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };

  if (!run) return error ? <Banner tone="danger">{error}</Banner> : null;
  const by = run.byOutcome || {};
  const finished = run.voyages.filter((v) => !["QUEUED", "RUNNING"].includes(v.outcome)).length;
  const pct = run.contracts ? Math.round((100 * finished) / run.contracts) : 0;

  return (
    <Card icon="crossing" title={`Run ${run.run_id.slice(0, 8)}`} sub={`Batch ${run.batch_id} → ${run.environment} · read-back ${run.read_back.toLowerCase()} · pilot ${run.pilot} · ${run.parallelism} in parallel · started ${when(run.started_at)} by ${run.started_by || "unknown"}`}
      actions={<>
        <Badge tone={STATE_TONE[run.state]} dot>{run.state}</Badge>
        {run.state === "PAUSED" && <Button size="sm" icon="play" onClick={() => act("continue")} disabled={busy}>Continue</Button>}
        {["RUNNING", "PAUSED"].includes(run.state) && <Button size="sm" variant="secondary" onClick={() => act("stop")} disabled={busy}>Stop</Button>}
      </>}>
      {error && <Banner tone="danger">{error}</Banner>}
      {run.reason && <Banner tone={run.state === "PAUSED" ? "warn" : run.state === "FAILED" ? "danger" : "info"} title={run.state === "PAUSED" ? "Paused." : ""}>{run.reason}</Banner>}
      <div className="cr-progress" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label="Contracts finished">
        <span style={{ width: `${pct}%` }} />
      </div>
      <Stats>
        <Stat value={`${finished} / ${run.contracts}`} label="Contracts finished" tone="brand" />
        <Stat value={by.SUCCESS ?? 0} label="Succeeded" tone="ok" />
        <Stat value={by.PARTIAL ?? 0} label="Partly" tone={by.PARTIAL ? "warn" : undefined} />
        <Stat value={(by.FAILED ?? 0) + (by.UNKNOWN ?? 0) + (by.MISMATCH ?? 0)} label="Failed, unknown or mismatched" tone={(by.FAILED || by.UNKNOWN || by.MISMATCH) ? "danger" : undefined} />
        <Stat value={by.SKIPPED ?? 0} label="Blocked in the plan" />
      </Stats>
      <Table columns={[
        { key: "seq", label: "#", num: true },
        { key: "contract_ref", label: "Contract", render: (v) => <code>{v.contract_ref}</code> },
        { key: "outcome", label: "Outcome", render: (v) => <Badge tone={OUTCOME_TONE[v.outcome]}>{v.outcome.replace("_", " ").toLowerCase()}</Badge> },
        { key: "reason", label: "First problem", render: (v) => v.reason ? <span className="xs">{v.reason}</span> : null },
        { key: "open", label: "", render: (v) => !["QUEUED"].includes(v.outcome) && (
          <Button size="sm" variant="ghost" onClick={() => setOpen(open === v.contract_ref ? "" : v.contract_ref)}>{open === v.contract_ref ? "Hide" : "Steps"}</Button>) },
      ]} rows={run.voyages.map((v) => ({ ...v, key: v.contract_ref }))} />

      {open && (
        <div className="cr-steps">
          <h3 className="cr-h">Steps of <code>{open}</code></h3>
          {steps.map((s) => <Step key={s.step_id + s.at} s={s} />)}
        </div>
      )}
    </Card>
  );
}

function Step({ s }) {
  let rb = null;
  try { rb = s.read_back ? JSON.parse(s.read_back) : null; } catch { rb = null; }
  return (
    <details className="cr-step">
      <summary>
        <Badge tone={OUTCOME_TONE[s.outcome]}>{s.outcome.replaceAll("_", " ").toLowerCase()}</Badge>
        <b>{s.operation}</b> <span className="muted">{s.subject_ref}</span>
        {s.target_id && <code className="xs">→ {s.target_id}</code>}
        {s.attempts > 1 && <span className="xs muted">{s.attempts} attempts</span>}
        {s.millis > 0 && <span className="xs faint">{s.millis} ms</span>}
      </summary>
      {s.message && <p className="xs" style={{ margin: "6px 0" }}>{s.error_code ? <><b>{s.error_code}</b> · </> : null}{s.message}</p>}
      {rb && rb.checks?.length > 0 && (
        <ul className="cr-rb">
          {rb.checks.map((c) => <li key={c.label} className={c.ok ? "ok" : "bad"}>{c.ok ? "✓" : "✗"} {c.label}: expected {c.expected}, found {c.actual ?? c.error}</li>)}
        </ul>
      )}
      <div className="cr-json">
        {s.request_json && <div><span className="xs muted">Request</span><pre>{pretty(s.request_json)}</pre></div>}
        {s.response_json && <div><span className="xs muted">Answer</span><pre>{pretty(s.response_json)}</pre></div>}
      </div>
    </details>
  );
}

function pretty(t) { try { return JSON.stringify(JSON.parse(t), null, 2); } catch { return t; } }
