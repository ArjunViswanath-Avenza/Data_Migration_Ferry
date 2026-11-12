import { Badge } from "../../kit/ui.jsx";
import { ACTION_LABEL, AREA_LABEL, KIND_LABEL, SEVERITY_TONE, defaultAspects, effectiveAction, effectiveAspects } from "./labels.js";

/** One difference: what it is, what each side has, and the choice for it. */
export default function DiffRow({ d, decision, onChange, showTable, code, compact }) {
  const decisions = decision ? { [d.id]: decision } : {};
  const action = effectiveAction(d, decisions);
  const changed = decision && (decision.action !== d.defaultAction || (decision.aspects && decision.aspects.join() !== defaultAspects(d).join()));

  return (
    <div className={`dd-diff${changed ? " changed" : ""}${action === "DROP" ? " dropping" : ""}`}>
      <div className="dd-diff-main">
        <div className="row" style={{ gap: 8 }}>
          <Badge tone={SEVERITY_TONE[d.severity]}>{d.severity}</Badge>
          <span className="dd-area">{AREA_LABEL[d.area] ?? d.area}</span>
          <b className="dd-obj">{showTable && d.table ? <span className="muted">{d.table}.</span> : null}{d.object}</b>
          <span className="dd-kind">{KIND_LABEL[d.kind] ?? d.kind}{d.confidence ? ` · ${d.confidence.toLowerCase()} confidence` : ""}</span>
        </div>
        {!compact && (d.source || d.target) && !code && d.aspects.length === 0 && (
          <div className="dd-defs">
            {d.source && <div><span className="dd-side-tag src">S</span><code>{d.source}</code></div>}
            {d.target && <div><span className="dd-side-tag tgt">T</span><code>{d.target}</code></div>}
          </div>
        )}
        {code && (d.source || d.target) && (
          <details>
            <summary>Show the SQL</summary>
            <div className="dd-code-pair">
              {d.source && <div><span className="dd-side-tag src">Source</span><pre className="dd-code">{d.source}</pre></div>}
              {d.target && <div><span className="dd-side-tag tgt">Target</span><pre className="dd-code">{d.target}</pre></div>}
            </div>
          </details>
        )}
        <p className="dd-sum">{d.summary}</p>
      </div>
      <DiffControl d={d} decision={decision} onChange={onChange} />
    </div>
  );
}

/** The choice for one difference; for a changed column, which of its differences to apply. */
export function DiffControl({ d, decision, onChange }) {
  const decisions = decision ? { [d.id]: decision } : {};
  const action = effectiveAction(d, decisions);
  const aspects = effectiveAspects(d, decisions);
  if (d.actions.length <= 1) return <div className="dd-ctl"><span className="xs muted">Nothing to choose</span></div>;
  return (
    <div className="dd-ctl">
      <select className={`inline${action === "DROP" ? " danger" : ""}`} value={action} aria-label={`Choice for ${d.object}`}
        onChange={(e) => onChange({ action: e.target.value, aspects: decision?.aspects })}>
        {d.actions.map((a) => <option key={a} value={a}>{ACTION_LABEL[a] ?? a}{a === d.defaultAction ? " (default)" : ""}</option>)}
      </select>
      {d.aspects.length > 0 && (
        <div className="dd-aspects">
          {d.aspects.map((a) => (
            <label key={a.key} className={action === "APPLY" ? "" : "off"} title={verdictText(a.verdict)}>
              <input type="checkbox" disabled={action !== "APPLY"} checked={action === "APPLY" && aspects.includes(a.key)}
                onChange={(e) => onChange({ action: "APPLY", aspects: e.target.checked ? [...aspects, a.key] : aspects.filter((k) => k !== a.key) })} />
              <span><b>{a.label}</b> <code>{a.target}</code> → <code>{a.source}</code>
                {a.verdict !== "DIFFERENT" && <em className={`dd-verdict ${a.verdict.toLowerCase()}`}>{verdictWord(a.verdict)}</em>}</span>
            </label>
          ))}
        </div>
      )}
    </div>
  );
}

function verdictWord(v) {
  return { WIDER: "wider in source", NARROWER: "target wider", RELAXED: "source allows NULL", STRICTER: "target allows NULL" }[v] ?? "";
}

function verdictText(v) {
  return {
    WIDER: "The source can hold everything the target can, and more: carrying it over loses nothing.",
    NARROWER: "The target can already hold everything the source can.",
    RELAXED: "The source allows NULL; making the target allow it too lets every source row load.",
    STRICTER: "The target already allows NULL.",
    DIFFERENT: "Different, neither side contains the other: decide which is right.",
  }[v];
}
