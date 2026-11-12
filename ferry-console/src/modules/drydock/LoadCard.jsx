import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { Badge, Banner, Button, Card, Icon, when } from "../../kit/ui.jsx";
import { bytes } from "./labels.js";

const OPTIONS = [
  ["ignoreIntDisplayWidth", "Ignore integer display width", "int(11) and int are the same column", true],
  ["ignoreColumnOrder", "Ignore column order", "a column in another position is not a difference", true],
  ["detectRenames", "Find renamed columns and tables", "pairs them by definition and name", true],
  ["ignoreComments", "Ignore comments", "", false],
  ["ignoreCollations", "Ignore collations", "", false],
];

/** Step 1: pick the two schema files (or the samples) and compare them. */
export default function LoadCard({ comparison, onCompared, onError }) {
  const [source, setSource] = useState(null);
  const [target, setTarget] = useState(null);
  const [opts, setOpts] = useState(() => Object.fromEntries(OPTIONS.map(([k, , , d]) => [k, d])));
  const [busy, setBusy] = useState("");
  const [recent, setRecent] = useState([]);

  const loadRecent = () => api.get("/api/drydock/comparisons").then(setRecent).catch(() => {});
  useEffect(() => { loadRecent(); }, [comparison?.id]);

  const run = async (kind) => {
    setBusy(kind); onError("");
    try {
      let c;
      if (kind === "samples") c = await api.post("/api/drydock/compare-samples");
      else {
        const fd = new FormData();
        fd.append("source", source);
        fd.append("target", target);
        c = await api.upload(`/api/drydock/compare?${new URLSearchParams(opts)}`, fd);
      }
      onCompared(c);
    } catch (e) { onError(e.message); }
    finally { setBusy(""); }
  };

  const reopen = async (id) => {
    if (!id) return;
    try { onCompared(await api.get(`/api/drydock/comparisons/${id}`)); } catch (e) { onError(e.message); }
  };

  return (
    <Card icon="compare" title="Load two schemas"
      sub="SQL scripts or dumps (.sql, .sql.gz, or a .zip of .sql files). Data rows in a dump are skipped, so whole dumps are fine; a schema-only export is quickest."
      actions={recent.length > 0 && (
        <select className="inline" aria-label="Open an earlier comparison" value="" onChange={(e) => reopen(e.target.value)}>
          <option value="">Earlier comparisons ({recent.length})</option>
          {recent.map((r) => <option key={r.id} value={r.id}>{r.id} · {r.source} → {r.target} · {when(r.createdAt)}</option>)}
        </select>
      )}>
      <div className="dd-pickers">
        <FilePick side="source" title="Source" tone="brand" file={source} onFile={setSource}
          help="The schema whose changes should travel, e.g. the bank's customised database." />
        <span className="dd-arrow" aria-hidden="true"><Icon name="arrowRight" size={22} /></span>
        <FilePick side="target" title="Target" tone="info" file={target} onFile={setTarget}
          help="The schema to bring up to date, e.g. the new release. The scripts change this one." />
      </div>

      <fieldset className="dd-options">
        <legend className="sr-only">What counts as a difference</legend>
        {OPTIONS.map(([k, label, hint]) => (
          <label key={k} title={hint}>
            <input type="checkbox" checked={opts[k]} onChange={(e) => setOpts({ ...opts, [k]: e.target.checked })} />
            <span>{label}</span>
          </label>
        ))}
      </fieldset>

      <div className="row">
        <Button icon="compare" onClick={() => run("files")} disabled={!source || !target || !!busy}>
          {busy === "files" ? "Reading and comparing…" : "Compare"}
        </Button>
        <Button variant="secondary" icon="play" onClick={() => run("samples")} disabled={!!busy}>
          {busy === "samples" ? "Comparing…" : "Try the sample files"}
        </Button>
        {busy === "files" && <span className="xs muted">Large dumps take a few seconds per gigabyte.</span>}
      </div>
      <p className="xs muted" style={{ margin: 0 }}>
        Quickest export: <code>mysqldump --no-data --routines --triggers your_db &gt; schema.sql</code>. Base scripts followed by
        upgrade scripts (ALTER, RENAME, DROP) are replayed in order, so a zip of a release's scripts gives its end state.
      </p>

      {comparison && (
        <div className="dd-sides">
          <SideSummary title="Source" tone="brand" side={comparison.source} />
          <SideSummary title="Target" tone="info" side={comparison.target} />
        </div>
      )}
    </Card>
  );
}

function FilePick({ side, title, tone, file, onFile, help }) {
  return (
    <div className="dd-pick">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <Badge tone={tone} dot>{title}</Badge>
        {file && <span className="xs muted">{bytes(file.size)}</span>}
      </div>
      <p className="xs muted" style={{ margin: 0 }}>{help}</p>
      <label className="file-drop" htmlFor={`dd-${side}`}>
        <Icon name="database" />
        <input id={`dd-${side}`} type="file" accept=".sql,.gz,.zip,.txt,.ddl" onChange={(e) => onFile(e.target.files?.[0] ?? null)} />
      </label>
    </div>
  );
}

function SideSummary({ title, tone, side }) {
  const parts = [
    `${side.tables} tables`, `${side.columns.toLocaleString()} columns`,
    side.views ? `${side.views} views` : null, side.procedures ? `${side.procedures} procedures` : null,
    side.functions ? `${side.functions} functions` : null, side.triggers ? `${side.triggers} triggers` : null,
  ].filter(Boolean);
  return (
    <div className="dd-side">
      <div className="row"><Badge tone={tone} dot>{title}</Badge><b className="dd-file">{side.fileName}</b>
        {side.database && <span className="muted xs">database {side.database}</span>}</div>
      <div className="xs muted">{parts.join(" · ")}</div>
      <div className="xs faint">
        {side.statementsRead.toLocaleString()} statements read, {side.statementsSkipped.toLocaleString()} data or session statements skipped
        {side.notUnderstood ? `, ${side.notUnderstood} not understood` : ""} · {bytes(side.characters)}
      </div>
      {side.warnings.length > 0 && (
        <details>
          <summary>{side.warnings.length} note{side.warnings.length === 1 ? "" : "s"} from reading the file</summary>
          <Banner tone="warn"><ul className="dd-notes">{side.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul></Banner>
        </details>
      )}
    </div>
  );
}
