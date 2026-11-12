import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { Badge, Banner, Button, Card, Field, PageHeader, Table, when } from "../../kit/ui.jsx";
import RunCard from "./RunCard.jsx";
import MockCard from "./MockCard.jsx";

const read = (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
const write = (k, v) => { try { localStorage.setItem(k, v); } catch { /* storage blocked */ } };

const CHECK_TONE = { OK: "ok", INFO: "info", WARN: "warn", ERROR: "danger", NOT_AVAILABLE: "neutral" };
const VERDICT = { GO: ["ok", "Go"], GO_WITH_WARNINGS: ["warn", "Go, with warnings"], NO_GO: ["danger", "No go"] };

export function isMockEnv(env) {
  try { const u = new URL(env.hostUrl); return ["localhost", "127.0.0.1"].includes(u.hostname) && u.pathname.startsWith("/mock"); }
  catch { return false; }
}

/** Crossing: survey the target, start a run of a planned batch, watch it, continue after the pilot. */
export default function CrossingPage({ module }) {
  const { operator, batchId, setBatchId, status } = useFerry();
  const env = status?.environment;
  const mockEnv = env ? isMockEnv(env) : false;
  const [batches, setBatches] = useState([]);
  const [db, setDb] = useState(() => ({ host: read("ferry:crossing:host", ""), port: read("ferry:crossing:port", "3306"),
    schema: read("ferry:crossing:schema", ""), user: read("ferry:crossing:user", ""), password: "" }));
  const [readBack, setReadBack] = useState("");
  const [survey, setSurvey] = useState(null);
  const [skipBlocked, setSkipBlocked] = useState(true);
  const [pilot, setPilot] = useState("");
  const [parallel, setParallel] = useState("");
  const [confirmLive, setConfirmLive] = useState(false);
  const [runId, setRunId] = useState("");
  const [runs, setRuns] = useState([]);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");

  const planned = batches.filter((b) => b.status === "PLANNED");
  const rb = readBack || (mockEnv ? "MOCK" : "DATABASE");
  const connected = db.host.trim() && db.user.trim();
  const dbBody = connected ? { host: db.host.trim(), port: Number(db.port) || 3306, schema: db.schema.trim() || undefined, user: db.user.trim(), password: db.password } : null;

  useEffect(() => { api.get("/api/boarding/batches?limit=100").then(setBatches).catch((e) => setError(e.message)); }, []);
  useEffect(() => { ["host", "port", "schema", "user"].forEach((k) => write(`ferry:crossing:${k}`, db[k])); }, [db.host, db.port, db.schema, db.user]);
  useEffect(() => {
    setSurvey(null);
    if (!batchId) { setRuns([]); return; }
    api.get(`/api/crossing/runs?batchId=${batchId}&limit=10`).then((r) => { setRuns(r); if (r[0] && ["RUNNING", "PAUSED"].includes(r[0].state)) setRunId(r[0].run_id); }).catch(() => {});
  }, [batchId]);

  const runSurvey = async () => {
    setBusy("survey"); setError("");
    try {
      setSurvey(await api.post("/api/crossing/survey", { batchId, source: rb === "MOCK" ? "MOCK" : "DATABASE", db: dbBody, performedBy: operator }));
    } catch (e) { setError(e.message); }
    finally { setBusy(""); }
  };

  const start = async () => {
    setBusy("start"); setError("");
    try {
      let contractRefs;
      if (survey && skipBlocked && survey.blockedContracts.length) {
        const plan = await api.get(`/api/boarding/batches/${batchId}/plan`);
        contractRefs = plan.map((v) => v.contractRef).filter((c) => !survey.blockedContracts.includes(c));
      }
      const r = await api.post("/api/crossing/runs", {
        batchId, contractRefs, readBack: rb, db: rb === "DATABASE" ? dbBody : null, performedBy: operator,
        pilot: pilot === "" ? undefined : Number(pilot), parallelism: parallel === "" ? undefined : Number(parallel),
      });
      setRunId(r.run_id);
      api.get(`/api/crossing/runs?batchId=${batchId}&limit=10`).then(setRuns).catch(() => {});
    } catch (e) { setError(e.message); }
    finally { setBusy(""); }
  };

  const canSend = env && (env.liveCalls || mockEnv);
  const needsConfirm = env && !mockEnv;

  return (
    <>
      <PageHeader eyebrow="Migration" icon={module.icon} title="Crossing" description={module.summary} />
      {error && <Banner tone="danger" title="That did not work.">{error}</Banner>}
      {env && !canSend && (
        <Banner tone="info" title={`Live calls are off for ${env.label}.`}>
          Nothing can be sent there. Use the built-in mock target to try a run, or switch <code>liveCalls</code> on in
          <code> config/environments.json</code> when you mean to write to it.
        </Banner>
      )}
      {env && mockEnv && <Banner tone="brand" title="Pointing at the built-in mock target.">It answers like the vendor's APIs and keeps a small database shaped like the product's, so every check works. Its behaviour switches are at the bottom of the page.</Banner>}

      <div className="grid-2">
        <Card icon="layers" title="Batch" sub="A batch planned in Boarding. The run sends exactly the steps reviewed there.">
          <Field label="Planned batch" htmlFor="cr-batch" hint={planned.length ? "" : "No planned batch yet: plan one in Boarding."}>
            <select id="cr-batch" value={batchId} onChange={(e) => setBatchId(e.target.value)}>
              <option value="">Choose a batch</option>
              {planned.map((b) => <option key={b.batch_id} value={b.batch_id}>{b.batch_id} · {b.name || "unnamed"}</option>)}
            </select>
          </Field>
          <Field label="Read-back (checks every call in the target's database)" htmlFor="cr-rb">
            <select id="cr-rb" value={rb} onChange={(e) => setReadBack(e.target.value)}>
              {mockEnv && <option value="MOCK">Built-in mock database</option>}
              <option value="DATABASE">Target database (read-only connection)</option>
              <option value="NONE">Off (not recommended)</option>
            </select>
          </Field>
          {rb === "NONE" && <Banner tone="warn">Without read-back a call is judged by its answer alone, an answer without an ID cannot be resolved, and a timed-out call stops the run.</Banner>}
        </Card>

        <Card icon="database" title="Target database" sub="A read-only user on the target's database, for the survey and read-back. The password is sent with each request and never stored.">
          <div className="cr-db">
            <Field label="Host" htmlFor="cr-host"><input id="cr-host" value={db.host} onChange={(e) => setDb({ ...db, host: e.target.value })} placeholder="db-uat.bank.example" autoComplete="off" disabled={rb === "MOCK"} /></Field>
            <Field label="Port" htmlFor="cr-port"><input id="cr-port" value={db.port} onChange={(e) => setDb({ ...db, port: e.target.value })} disabled={rb === "MOCK"} /></Field>
            <Field label="Schema" htmlFor="cr-schema"><input id="cr-schema" value={db.schema} onChange={(e) => setDb({ ...db, schema: e.target.value })} placeholder="dbxdb" disabled={rb === "MOCK"} /></Field>
            <Field label="User" htmlFor="cr-user"><input id="cr-user" value={db.user} onChange={(e) => setDb({ ...db, user: e.target.value })} autoComplete="off" disabled={rb === "MOCK"} /></Field>
            <Field label="Password" htmlFor="cr-pass"><input id="cr-pass" type="password" value={db.password} onChange={(e) => setDb({ ...db, password: e.target.value })} autoComplete="new-password" disabled={rb === "MOCK"} /></Field>
          </div>
          {rb === "MOCK" && <p className="xs muted" style={{ margin: 0 }}>Not needed: the mock's own database is used.</p>}
        </Card>
      </div>

      <Card icon="shield" title="1 · Survey the target" sub="Read-only. Confirms what Ferry relies on and finds what would collide: accounts already on a contract, CIFs already enrolled, login names taken."
        actions={<Button icon="search" onClick={runSurvey} disabled={!batchId || !!busy || (rb !== "MOCK" && !connected)}>{busy === "survey" ? "Surveying…" : "Run survey"}</Button>}>
        {!survey && <p className="xs muted" style={{ margin: 0 }}>Recommended before every run. It reads only.</p>}
        {survey && (
          <>
            <div className="row"><Badge tone={VERDICT[survey.verdict][0]} dot>{VERDICT[survey.verdict][1]}</Badge>
              {survey.blockedContracts.length > 0 && <span className="xs muted">{survey.blockedContracts.length} contract(s) would fail: {survey.blockedContracts.join(", ")}</span>}</div>
            <Table columns={[
              { key: "label", label: "Check" },
              { key: "status", label: "", render: (c) => <Badge tone={CHECK_TONE[c.status]}>{c.status.replace("_", " ").toLowerCase()}</Badge> },
              { key: "detail", label: "What was found", render: (c) => <span className="xs">{c.detail}</span> },
            ]} rows={survey.checks.map((c) => ({ ...c, key: c.id }))} />
            {survey.blockedContracts.length > 0 && (
              <label className="cr-check"><input type="checkbox" checked={skipBlocked} onChange={(e) => setSkipBlocked(e.target.checked)} />
                <span>Leave these contracts out of the run</span></label>
            )}
          </>
        )}
      </Card>

      <Card icon="play" title="2 · Start the crossing" sub="The run pauses after the pilot, and on its own when a check fails, an outcome is unknown, or too many contracts fail.">
        <div className="row" style={{ alignItems: "flex-end" }}>
          <div style={{ flex: "1 1 160px" }}><Field label="Pilot contracts" htmlFor="cr-pilot" hint="Default from config/crossing.json (3); 0 for none.">
            <input id="cr-pilot" value={pilot} onChange={(e) => setPilot(e.target.value.replace(/\D/g, ""))} placeholder="3" inputMode="numeric" /></Field></div>
          <div style={{ flex: "1 1 160px" }}><Field label="Contracts in parallel" htmlFor="cr-par" hint="Default from assumption A-11.">
            <input id="cr-par" value={parallel} onChange={(e) => setParallel(e.target.value.replace(/\D/g, ""))} placeholder="4" inputMode="numeric" /></Field></div>
          {needsConfirm && (
            <label className="cr-check" style={{ flex: "1 1 260px" }}><input type="checkbox" checked={confirmLive} onChange={(e) => setConfirmLive(e.target.checked)} />
              <span>I mean to write to <b>{env?.label}</b></span></label>
          )}
          <Button variant={mockEnv ? "primary" : "danger"} icon="crossing" onClick={start}
            disabled={!batchId || !canSend || !!busy || (needsConfirm && !confirmLive) || (rb === "DATABASE" && !connected)}>
            {busy === "start" ? "Starting…" : "Start crossing"}
          </Button>
        </div>
      </Card>

      {runId && <RunCard runId={runId} />}

      {runs.length > 0 && (
        <Card icon="history" title="Runs of this batch">
          <Table columns={[
            { key: "started_at", label: "Started", render: (r) => when(r.started_at) },
            { key: "state", label: "State", render: (r) => <Badge tone={{ COMPLETED: "ok", PAUSED: "warn", RUNNING: "brand", STOPPED: "neutral", FAILED: "danger" }[r.state]}>{r.state}</Badge> },
            { key: "contracts", label: "Contracts", num: true },
            { key: "read_back", label: "Read-back" },
            { key: "started_by", label: "By" },
            { key: "open", label: "", render: (r) => <Button size="sm" variant="ghost" onClick={() => setRunId(r.run_id)}>Open</Button> },
          ]} rows={runs.map((r) => ({ ...r, key: r.run_id }))} />
        </Card>
      )}

      {mockEnv && <MockCard />}
    </>
  );
}
