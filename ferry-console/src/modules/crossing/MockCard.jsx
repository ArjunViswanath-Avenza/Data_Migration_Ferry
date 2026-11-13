import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { Banner, Button, Card, Field, KeyValue } from "../../kit/ui.jsx";

const SWITCHES = [
  ["successWithoutId", "Answers carry no ID", "Read-back must find the new ID (contract and group do; users stop the run)."],
  ["contractDropsAccounts", "Contracts lose their accounts", "Read-back mismatch: the run pauses on the first contract."],
  ["linkUserUnsupported", "Core not supported for linked users", "LinkUserToContract answers 10001 (question 1)."],
  ["noApprovalRejected", "No-approval ranges refused", "Question 8: a NO_APPROVAL range answers 87115, as the vendor's Postman sample did."],
  ["groupRuleFlagsOnly", "groupRule is 1/0 only", "Question 3: a count above 1 in a group answers 87114."],
  ["rangesPlusOne", "Ranges start one above the previous end", "Question 4: the earlier tool's convention; Ferry's shared boundaries are refused."],
];
const NUMBERS = [
  ["latencyMs", "Delay per call (ms)"],
  ["throttleEvery", "Throttle every Nth call (429)"],
  ["serverErrorEvery", "Server error every Nth call (500)"],
  ["timeoutEvery", "Every Nth create answers too late"],
  ["slowMs", "…by this many ms"],
  ["tokenLifetimeSeconds", "Token lifetime (s)"],
];

/** The mock target's behaviour switches: play each way an open question could be answered. */
export default function MockCard() {
  const [b, setB] = useState(null);
  const [state, setState] = useState(null);
  const [saved, setSaved] = useState("");
  const [error, setError] = useState("");

  const load = () => {
    api.get("/api/mock/behaviour").then(setB).catch((e) => setError(e.message));
    api.get("/api/mock/state").then(setState).catch(() => {});
  };
  useEffect(load, []);

  const save = async () => {
    try { setB(await api.put("/api/mock/behaviour", b)); setSaved("Saved"); setTimeout(() => setSaved(""), 1500); }
    catch (e) { setError(e.message); }
  };
  const reset = async () => {
    if (!confirm("Empty the mock's database and switch every behaviour off?")) return;
    try { setState(await api.post("/api/mock/reset")); load(); } catch (e) { setError(e.message); }
  };

  if (!b) return null;
  const list = (k) => (b[k] || []).join(", ");
  const setList = (k, v) => setB({ ...b, [k]: v.split(",").map((x) => x.trim()).filter(Boolean) });

  return (
    <Card icon="sliders" title="Mock target" sub="Make the mock answer the way an open question might turn out, then run a crossing and see how Ferry copes."
      actions={<><Button size="sm" variant="secondary" icon="refresh" onClick={load}>Refresh</Button><Button size="sm" variant="ghost" onClick={reset}>Reset mock</Button></>}>
      {error && <Banner tone="danger">{error}</Banner>}
      <div className="cr-switches">
        {SWITCHES.map(([k, label, hint]) => (
          <label key={k} className="cr-check" title={hint}><input type="checkbox" checked={!!b[k]} onChange={(e) => setB({ ...b, [k]: e.target.checked })} /><span>{label}</span></label>
        ))}
      </div>
      <div className="cr-db">
        {NUMBERS.map(([k, label]) => (
          <Field key={k} label={label} htmlFor={`mk-${k}`}>
            <input id={`mk-${k}`} value={b[k]} inputMode="numeric" onChange={(e) => setB({ ...b, [k]: Number(e.target.value.replace(/\D/g, "")) || 0 })} />
          </Field>
        ))}
      </div>
      <div className="grid-2">
        <Field label="CIFs whose contract name clashes (10401)" htmlFor="mk-clash" hint="Comma-separated primary CIFs.">
          <input id="mk-clash" value={list("contractNameClashCifs")} onChange={(e) => setList("contractNameClashCifs", e.target.value)} />
        </Field>
        <Field label="Fields a virtual user must have" htmlFor="mk-req" hint="Question 6: e.g. add middleName, taxId.">
          <input id="mk-req" value={list("virtualUserRequired")} onChange={(e) => setList("virtualUserRequired", e.target.value)} />
        </Field>
      </div>
      <div className="row"><Button icon="check" onClick={save}>Save behaviour</Button>{saved && <span className="xs muted">{saved}</span>}</div>
      {state && (
        <details><summary>What the mock holds</summary>
          <div style={{ marginTop: 8 }}><KeyValue pairs={Object.entries(state).map(([k, v]) => [k, String(v)])} /></div>
        </details>
      )}
    </Card>
  );
}
