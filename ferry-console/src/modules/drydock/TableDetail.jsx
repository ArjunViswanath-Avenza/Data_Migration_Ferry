import { useEffect, useMemo, useState } from "react";
import { api } from "../../api.js";
import { Badge, Banner, Button } from "../../kit/ui.jsx";
import DiffRow, { DiffControl } from "./DiffRow.jsx";
import { STATUS_LABEL, STATUS_TONE, effectiveAction, tableTitle } from "./labels.js";

const MARK = { ONLY_IN_SOURCE: ["+", "Only in source"], ONLY_IN_TARGET: ["T", "Only in target"], CHANGED: ["≠", "Changed"], RENAMED: ["↔", "Renamed?"] };
const ROW = { ONLY_IN_SOURCE: "add", ONLY_IN_TARGET: "tonly", CHANGED: "chg", RENAMED: "ren" };

/** One table side by side: source columns on the left, target columns on the right, the choice on each row that differs. */
export default function TableDetail({ comparisonId, pair, decisions, setOne, carry, hide, onlyDiffering }) {
  const [detail, setDetail] = useState(null);
  const [diffOnly, setDiffOnly] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    setDetail(null); setError("");
    api.get(`/api/drydock/comparisons/${comparisonId}/tables/${encodeURIComponent(pair.key)}`).then(setDetail).catch((e) => setError(e.message));
  }, [comparisonId, pair.key]);

  // differences hidden from view (e.g. character sets) are left out of the rows and lists, not out of the scripts
  const shownDetail = useMemo(() => (detail && hide ? { ...detail, differences: detail.differences.filter((d) => !hide(d)) } : detail), [detail, hide]);
  const allRows = useMemo(() => (shownDetail ? align(shownDetail, pair.key) : []), [shownDetail, pair.key]);
  const rows = diffOnly || onlyDiffering ? allRows.filter((r) => r.diff) : allRows;
  if (error) return <Banner tone="danger" title="Could not open the table.">{error}</Banner>;
  if (!detail) return <p className="muted xs">Opening {tableTitle(pair)}…</p>;

  const diffs = shownDetail.differences;
  const tableDiff = diffs.find((d) => d.area === "TABLE");
  const others = diffs.filter((d) => d.area !== "TABLE" && d.area !== "COLUMN");
  const tableAction = tableDiff ? effectiveAction(tableDiff, decisions) : null;
  const separate = tableDiff?.kind === "RENAMED" && tableAction === "SEPARATE";

  return (
    <div className="stack">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <div className="row" style={{ gap: 8 }}>
          <h3 className="dd-h">{tableTitle(pair)}</h3>
          <Badge tone={STATUS_TONE[pair.status]}>{STATUS_LABEL[pair.status]}</Badge>
          <span className="xs muted">{diffs.length} difference{diffs.length === 1 ? "" : "s"}</span>
        </div>
        <div className="row">
          <label className="dd-check" title="Hide the columns that are the same on both sides">
            <input type="checkbox" checked={diffOnly || !!onlyDiffering} disabled={!!onlyDiffering} onChange={(e) => setDiffOnly(e.target.checked)} />
            <span>Only columns that differ</span>
          </label>
          <Button size="sm" variant="ghost" onClick={() => carry(diffs)}>Carry all from source</Button>
        </div>
      </div>

      {tableDiff && <DiffRow d={tableDiff} decision={decisions[tableDiff.id]} onChange={(v) => setOne(tableDiff.id, v)} />}
      {separate && <Banner tone="info">Treated as two tables: the source table is created as it is, so the column choices below do not apply.</Banner>}

      <div className="table-wrap">
        <table className="dd-grid">
          <thead>
            <tr>
              <th><span className="dd-side-tag src">S</span> Source {detail.source ? <span className="dd-thname">{detail.source.name}</span> : "(none)"}</th>
              <th aria-label="Difference" />
              <th><span className="dd-side-tag tgt">T</span> Target {detail.target ? <span className="dd-thname">{detail.target.name}</span> : "(none)"}</th>
              <th>Choice</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && <tr><td colSpan={4} className="empty-cell">No column differences here{onlyDiffering ? " of this kind" : ""}.</td></tr>}
            {rows.map((r, i) => {
              const d = r.diff;
              return (
                <tr key={i} className={d ? `dd-r-${ROW[d.kind] ?? ""}` : undefined}>
                  <td>{r.s ? <Col c={r.s} /> : <span className="faint">—</span>}</td>
                  <td className="dd-mark" title={d ? MARK[d.kind]?.[1] : "Same"}>{d ? MARK[d.kind]?.[0] : ""}</td>
                  <td>{r.t ? <Col c={r.t} /> : <span className="faint">—</span>}</td>
                  <td className="dd-choice">{d ? <DiffControl d={d} decision={decisions[d.id]} onChange={(v) => setOne(d.id, v)} /> : null}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {others.length > 0 && (
        <>
          <h4 className="dd-sub">Keys, indexes and table options</h4>
          <div className="dd-list">
            {others.map((d) => <DiffRow key={d.id} d={d} decision={decisions[d.id]} onChange={(v) => setOne(d.id, v)} />)}
          </div>
        </>
      )}
      {(detail.source?.options || detail.target?.options) && (
        <p className="xs faint" style={{ margin: 0 }}>Source: {detail.source?.options || "—"} · Target: {detail.target?.options || "—"}</p>
      )}
    </div>
  );
}

function Col({ c }) {
  return (
    <div className="dd-col">
      <b>{c.name}</b>
      <span className="dd-type">{c.type}{c.nullable ? "" : " not null"}{c.defaultValue != null ? ` = ${c.defaultValue}` : ""}</span>
      {c.extra && <span className="dd-extra">{c.extra}</span>}
    </div>
  );
}

/** Rows that line up a source column with the target column it matches (same name or a likely rename). */
function align(detail, key) {
  const sCols = detail.source?.columns ?? [], tCols = detail.target?.columns ?? [];
  const lc = (s) => s.toLowerCase();
  const byId = new Map(detail.differences.map((d) => [d.id, d]));
  const renames = detail.differences.filter((d) => d.area === "COLUMN" && d.kind === "RENAMED");
  const renameByTarget = new Map(renames.map((d) => [lc(d.targetName), d]));
  const srcBy = new Map(sCols.map((c) => [lc(c.name), c]));
  const used = new Set();

  const rows = tCols.map((t) => {
    const r = renameByTarget.get(lc(t.name));
    const s = r ? srcBy.get(lc(r.sourceName)) : srcBy.get(lc(t.name));
    if (s) used.add(lc(s.name));
    return { s, t, diff: r ?? byId.get(`C:${key}:${lc(t.name)}`) };
  });
  sCols.forEach((s, i) => {
    if (used.has(lc(s.name))) return;
    let at = 0;
    for (let j = i - 1; j >= 0; j--) {
      const idx = rows.findIndex((r) => r.s && lc(r.s.name) === lc(sCols[j].name));
      if (idx >= 0) { at = idx + 1; break; }
    }
    rows.splice(at, 0, { s, t: null, diff: byId.get(`C:${key}:${lc(s.name)}`) });
    used.add(lc(s.name));
  });
  return rows;
}
