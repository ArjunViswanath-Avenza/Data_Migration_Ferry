import { useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Icon, Tabs } from "../../kit/ui.jsx";
import TableDetail from "./TableDetail.jsx";
import DiffRow from "./DiffRow.jsx";
import { AREA_LABEL, KIND_LABEL, SEVERITIES, SEVERITY_TONE, STATUS_LABEL, STATUS_TONE, carryAll, isChanged, isCharsetOnly, tableTitle, worst } from "./labels.js";

const ROUTINES = "__routines";
const PAGE = 100;

/** Step 2 and 3: every difference, by table or as one list, with the choice for each. */
export default function DifferencesCard({ comparison, decisions, setDecisions }) {
  const [view, setView] = useState("tables");
  const [query, setQuery] = useState("");
  const [sev, setSev] = useState({ RISK: true, REVIEW: true, SAFE: true, INFO: false });
  const [area, setArea] = useState("");
  const [kind, setKind] = useState("");                     // e.g. CHANGED: only what differs on both sides
  const [onlyChanged, setOnlyChanged] = useState(false);
  const [hideCharset, setHideCharset] = useState(false);   // view only: the scripts still follow these choices
  const [selected, setSelected] = useState("");
  const [shown, setShown] = useState(PAGE);

  const diffs = comparison.differences;
  const q = query.trim().toLowerCase();
  const visible = useMemo(() => diffs.filter((d) =>
    sev[d.severity]
    && (!area || d.area === area)
    && (!kind || d.kind === kind)
    && (!onlyChanged || isChanged(d, decisions))
    && (!hideCharset || !isCharsetOnly(d))
    && (!q || `${d.table ?? ""} ${d.object ?? ""} ${d.sourceName ?? ""} ${d.targetName ?? ""}`.toLowerCase().includes(q))),
  [diffs, sev, area, kind, onlyChanged, hideCharset, q, decisions]);
  const kinds = useMemo(() => diffs.reduce((m, d) => ({ ...m, [d.kind]: (m[d.kind] ?? 0) + 1 }), {}), [diffs]);
  const hidden = (d) => (hideCharset && isCharsetOnly(d)) || (kind && d.kind !== kind && d.area !== "TABLE");
  const charsetCount = useMemo(() => diffs.filter(isCharsetOnly).length, [diffs]);

  // tables that have at least one visible difference, plus the routines group
  const tables = useMemo(() => {
    const byKey = new Map();
    for (const d of visible) {
      const k = d.tableKey ?? ROUTINES;
      if (!byKey.has(k)) byKey.set(k, []);
      byKey.get(k).push(d);
    }
    const rows = comparison.tables.filter((t) => byKey.has(t.key)).map((t) => ({ ...t, list: byKey.get(t.key) }));
    if (byKey.has(ROUTINES)) rows.push({ key: ROUTINES, targetName: "Views, procedures and triggers", status: "ROUTINES", list: byKey.get(ROUTINES) });
    return rows;
  }, [visible, comparison.tables]);

  useEffect(() => { if (!tables.some((t) => t.key === selected)) setSelected(tables[0]?.key ?? ""); }, [tables, selected]);
  useEffect(() => setShown(PAGE), [visible]);

  const areas = Object.keys(comparison.byArea);
  const setOne = (id, value) => setDecisions((m) => ({ ...m, [id]: value }));
  const carry = (list) => setDecisions((m) => {
    const next = { ...m };
    for (const d of list) { const c = carryAll(d); if (c) next[d.id] = c; }
    return next;
  });
  const changedTotal = diffs.filter((d) => isChanged(d, decisions)).length;
  const current = tables.find((t) => t.key === selected);

  return (
    <Card icon="layers" title="Differences"
      sub="Each difference starts on a choice that never loses data: what only the source has is added, wider source types are carried over, what only the target has is kept, and anything unclear stays as it is until you decide."
      actions={<>
        <Button size="sm" variant="secondary" icon="arrowRight" onClick={() => carry(visible)} title="Add, create and apply everything the source has, for the differences shown. Nothing is dropped.">
          Carry all shown
        </Button>
        <Button size="sm" variant="ghost" icon="refresh" onClick={() => setDecisions({})} disabled={!changedTotal}>Reset choices</Button>
      </>}>

      <div className="dd-filters">
        <label className="dd-search">
          <Icon name="search" size={16} />
          <span className="sr-only">Find a table or column</span>
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Find a table or column" />
        </label>
        <div className="dd-chips" role="group" aria-label="Severity">
          {SEVERITIES.map((s) => (
            <button key={s.id} className={`dd-chip ${s.tone}`} aria-pressed={sev[s.id]} title={s.hint}
              onClick={() => setSev({ ...sev, [s.id]: !sev[s.id] })}>
              {s.label}<span className="n">{comparison.bySeverity[s.id] ?? 0}</span>
            </button>
          ))}
        </div>
        <select className="inline" value={area} onChange={(e) => setArea(e.target.value)} aria-label="Kind of object">
          <option value="">Every kind</option>
          {areas.map((a) => <option key={a} value={a}>{AREA_LABEL[a] ?? a} ({comparison.byArea[a]})</option>)}
        </select>
        <select className="inline" value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Kind of difference">
          <option value="">Every difference</option>
          {Object.keys(KIND_LABEL).filter((k) => kinds[k]).map((k) => (
            <option key={k} value={k}>{k === "CHANGED" ? "Changed only" : KIND_LABEL[k]} ({kinds[k]})</option>
          ))}
        </select>
        <label className="dd-check">
          <input type="checkbox" checked={onlyChanged} onChange={(e) => setOnlyChanged(e.target.checked)} />
          <span>Only my changes ({changedTotal})</span>
        </label>
        <label className="dd-check" title="Hides differences that are only about character sets or collations, to make the list easier to read. They stay in the scripts with their current choice (Keep, unless you changed it).">
          <input type="checkbox" checked={hideCharset} onChange={(e) => setHideCharset(e.target.checked)} />
          <span>Ignore character sets ({charsetCount})</span>
        </label>
      </div>

      <Tabs value={view} onChange={setView} label="View" items={[
        { id: "tables", label: "By table", icon: "table", count: tables.length },
        { id: "all", label: "All differences", icon: "layers", count: visible.length },
      ]} />
      {hideCharset && charsetCount > 0 && (
        <p className="xs muted" style={{ margin: 0 }}>
          {charsetCount} character-set difference{charsetCount === 1 ? " is" : "s are"} hidden from view only; the scripts still follow their choices.
        </p>
      )}

      {visible.length === 0 ? (
        <EmptyState icon="checkCircle" title={diffs.length ? "Nothing matches these filters" : "The schemas match"}>
          {diffs.length ? "Turn on more severities or clear the search." : "With these settings there is nothing to carry over."}
        </EmptyState>
      ) : view === "tables" ? (
        <div className="dd-split">
          <nav className="dd-tables" aria-label="Tables with differences">
            {tables.map((t) => {
              const w = worst(t.list);
              return (
                <button key={t.key} aria-current={t.key === selected ? "true" : undefined} onClick={() => setSelected(t.key)}>
                  <span className={`dd-dot ${SEVERITY_TONE[w]}`} title={w} />
                  <span className="dd-tname">{tableTitle(t)}</span>
                  {t.status !== "CHANGED" && t.status !== "ROUTINES" && <Badge tone={STATUS_TONE[t.status]}>{STATUS_LABEL[t.status]}</Badge>}
                  <span className="dd-count">{t.list.length}</span>
                </button>
              );
            })}
          </nav>
          <div className="dd-detail">
            {current && (current.key === ROUTINES
              ? <RoutineList list={current.list} decisions={decisions} setOne={setOne} carry={carry} />
              : <TableDetail key={current.key} comparisonId={comparison.id} pair={current} visibleIds={new Set(current.list.map((d) => d.id))}
                  decisions={decisions} setOne={setOne} carry={carry} hide={hideCharset || kind ? hidden : null} onlyDiffering={!!kind} />)}
          </div>
        </div>
      ) : (
        <div className="dd-list">
          {visible.slice(0, shown).map((d) => (
            <DiffRow key={d.id} d={d} decision={decisions[d.id]} onChange={(v) => setOne(d.id, v)} showTable />
          ))}
          {visible.length > shown && (
            <div className="row" style={{ justifyContent: "center", padding: 12 }}>
              <Button variant="secondary" size="sm" onClick={() => setShown(shown + PAGE)}>Show {Math.min(PAGE, visible.length - shown)} more of {visible.length - shown}</Button>
            </div>
          )}
        </div>
      )}
    </Card>
  );
}

function RoutineList({ list, decisions, setOne, carry }) {
  return (
    <div className="stack">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h3 className="dd-h">Views, procedures, functions and triggers</h3>
        <Button size="sm" variant="ghost" onClick={() => carry(list)}>Carry all from source</Button>
      </div>
      <p className="xs muted" style={{ margin: 0 }}>Compared without definer, quoting, spacing or schema prefixes. The refit script recreates the ones you choose, without a definer, so they run as whoever runs the script.</p>
      <div className="dd-list">
        {list.map((d) => <DiffRow key={d.id} d={d} decision={decisions[d.id]} onChange={(v) => setOne(d.id, v)} code />)}
      </div>
    </div>
  );
}
