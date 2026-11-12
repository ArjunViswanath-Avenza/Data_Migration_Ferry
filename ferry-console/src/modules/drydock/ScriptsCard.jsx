import { useEffect, useRef, useState } from "react";
import { api } from "../../api.js";
import { Banner, Button, Card, Field, LinkButton, Tabs } from "../../kit/ui.jsx";

const MODES = [
  ["INSERT", "INSERT: stop at a row whose key already exists"],
  ["INSERT_IGNORE", "INSERT IGNORE: skip rows whose key already exists"],
  ["UPSERT", "Upsert: existing rows take the source values"],
  ["REPLACE", "REPLACE: delete and insert existing rows again"],
];

/** Step 4: the refit script and the data move script, written from the current choices. */
export default function ScriptsCard({ comparison, decisions, changedCount, onWritten }) {
  const [sourceSchema, setSourceSchema] = useState(comparison.source.database || "");
  const [targetSchema, setTargetSchema] = useState(comparison.target.database || "");
  const [dataMode, setDataMode] = useState("INSERT");
  const [emptyFirst, setEmptyFirst] = useState(false);
  const [result, setResult] = useState(null);
  const [tab, setTab] = useState("refit");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState("");
  const first = useRef(true);

  useEffect(() => {
    setSourceSchema(comparison.source.database || "");
    setTargetSchema(comparison.target.database || "");
    setResult(null);
  }, [comparison.id]);

  const writeScripts = async () => {
    setBusy(true); setError("");
    try {
      const r = await api.post(`/api/drydock/comparisons/${comparison.id}/scripts`, {
        decisions, sourceSchema, targetSchema, dataMode, emptyTargetFirst: emptyFirst, excludeTables: [],
      });
      setResult(r);
      onWritten();
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };

  // once written, the scripts follow every change of choice
  useEffect(() => {
    if (first.current) { first.current = false; return undefined; }
    if (!result) return undefined;
    const t = setTimeout(writeScripts, 450);
    return () => clearTimeout(t);
  }, [decisions, sourceSchema, targetSchema, dataMode, emptyFirst]);

  const text = result ? (tab === "refit" ? result.refit : result.dataMove) : "";
  const fileName = `${comparison.id}_${tab === "refit" ? "1_refit" : "2_data_move"}.sql`;

  const copy = async () => {
    try { await navigator.clipboard.writeText(text); setCopied(tab); setTimeout(() => setCopied(""), 1600); }
    catch { setError("Copying is blocked in this browser; use Download instead."); }
  };
  const download = () => {
    const url = URL.createObjectURL(new Blob([text], { type: "application/sql;charset=utf-8" }));
    const a = Object.assign(document.createElement("a"), { href: url, download: fileName });
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  return (
    <Card icon="code" title="Scripts"
      sub="Run the refit script on the target first, then the data move. Both are text for a DBA to read and run; Dry Dock runs nothing."
      actions={<LinkButton size="sm" icon="download" href={api.href(`/api/drydock/comparisons/${comparison.id}/report.xlsx`)}
        title="Every difference with its default and chosen action. Uses the choices of the last script written.">Differences report (.xlsx)</LinkButton>}>
      <div className="dd-script-opts">
        <Field label="Source schema" htmlFor="dd-ss" hint="Where the source rows are read from">
          <input id="dd-ss" value={sourceSchema} onChange={(e) => setSourceSchema(e.target.value.trim())} placeholder="source_db" />
        </Field>
        <Field label="Target schema" htmlFor="dd-ts" hint="The refit script runs USE on it">
          <input id="dd-ts" value={targetSchema} onChange={(e) => setTargetSchema(e.target.value.trim())} placeholder="target_db" />
        </Field>
        <Field label="When a row already exists" htmlFor="dd-mode">
          <select id="dd-mode" value={dataMode} onChange={(e) => setDataMode(e.target.value)}>
            {MODES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </Field>
      </div>
      <div className="row">
        <label className="dd-check">
          <input type="checkbox" checked={emptyFirst} onChange={(e) => setEmptyFirst(e.target.checked)} />
          <span>Empty each target table before moving rows (DELETE FROM)</span>
        </label>
        <div style={{ flex: 1 }} />
        <span className="xs muted">{changedCount ? `${changedCount} choice${changedCount === 1 ? "" : "s"} changed from the defaults` : "Using the safe defaults"}</span>
        <Button icon="code" onClick={writeScripts} disabled={busy}>{busy ? "Writing…" : result ? "Write again" : "Write scripts"}</Button>
      </div>

      {error && <Banner tone="danger" title="The scripts were not written.">{error}</Banner>}

      {result && (
        <>
          {result.warnings.length > 0 && (
            <Banner tone="warn" title={`${result.warnings.length} thing${result.warnings.length === 1 ? "" : "s"} to check.`}>
              <ul className="dd-notes">{result.warnings.slice(0, 12).map((w, i) => <li key={i}>{w}</li>)}</ul>
              {result.warnings.length > 12 && <span className="xs">…and {result.warnings.length - 12} more, marked CHECK or REVIEW in the scripts.</span>}
            </Banner>
          )}
          {result.removals > 0 && (
            <Banner tone="danger" title={`${result.removals} removal${result.removals === 1 ? "" : "s"} chosen.`}>
              They are the last section of the refit script and delete data or objects from the target.
            </Banner>
          )}
          <div className="row" style={{ justifyContent: "space-between" }}>
            <Tabs value={tab} onChange={setTab} label="Script" items={[
              { id: "refit", label: "1 · Refit target schema", icon: "drydock", count: result.refitStatements },
              { id: "data", label: "2 · Move the data", icon: "arrowRight", count: result.dataStatements },
            ]} />
            <div className="row">
              <Button size="sm" variant="secondary" icon="copy" onClick={copy}>{copied === tab ? "Copied" : "Copy"}</Button>
              <Button size="sm" variant="secondary" icon="download" onClick={download}>Download {fileName}</Button>
            </div>
          </div>
          <pre className="dd-script" tabIndex={0} aria-label={tab === "refit" ? "Refit script" : "Data move script"}>{text}</pre>
        </>
      )}
    </Card>
  );
}
