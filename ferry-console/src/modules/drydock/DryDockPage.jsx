import { useEffect, useMemo, useState } from "react";
import { api } from "../../api.js";
import { Banner, PageHeader, Stat, Stats, Stepper, plural } from "../../kit/ui.jsx";
import LoadCard from "./LoadCard.jsx";
import DifferencesCard from "./DifferencesCard.jsx";
import ScriptsCard from "./ScriptsCard.jsx";
import { isChanged } from "./labels.js";

const read = (k) => { try { return localStorage.getItem(k) || ""; } catch { return ""; } };
const write = (k, v) => { try { v ? localStorage.setItem(k, v) : localStorage.removeItem(k); } catch { /* storage blocked */ } };

/**
 * Dry Dock: load two schemas, review the differences, choose what travels, write the scripts.
 * Choices live here (one map of difference id → { action, aspects }) so every card sees the same ones.
 */
export default function DryDockPage({ module }) {
  const [comparison, setComparison] = useState(null);
  const [decisions, setDecisions] = useState({});
  const [scripted, setScripted] = useState(false);
  const [error, setError] = useState("");

  // reopen the last comparison after a reload, if the server still has it
  useEffect(() => {
    const id = read("ferry:drydock");
    if (!id) return;
    api.get(`/api/drydock/comparisons/${id}`).then(open).catch(() => write("ferry:drydock", ""));
  }, []);

  const open = (c) => {
    setComparison(c);
    setDecisions({});
    setScripted(false);
    setError("");
    write("ferry:drydock", c.id);
  };

  const changedCount = useMemo(
    () => (comparison ? comparison.differences.filter((d) => isChanged(d, decisions)).length : 0),
    [comparison, decisions]);

  const sev = comparison?.bySeverity ?? {};
  const steps = [
    { id: "load", label: "Load two schemas", hint: comparison ? `${comparison.source.fileName} → ${comparison.target.fileName}` : "SQL, .gz or .zip", state: comparison ? "done" : "current" },
    { id: "review", label: "Review differences", hint: comparison ? plural(comparison.differences.length, "difference") : "Every change, explained", state: comparison ? (scripted ? "done" : "current") : "todo" },
    { id: "choose", label: "Choose what travels", hint: changedCount ? `${changedCount} changed from default` : "Safe defaults set", state: comparison ? (scripted ? "done" : "todo") : "todo" },
    { id: "scripts", label: "Write the scripts", hint: "Refit, then data move", state: scripted ? "done" : "todo" },
  ];

  return (
    <>
      <PageHeader eyebrow="Tools" icon={module.icon} title="Dry Dock" description={module.summary} />
      <Stepper steps={steps} />
      {error && <Banner tone="danger" title="Something went wrong.">{error}</Banner>}
      <LoadCard comparison={comparison} onCompared={open} onError={setError} />
      {comparison && (
        <>
          <Stats>
            <Stat value={`${comparison.source.tables} / ${comparison.target.tables}`} label="Tables, source / target" />
            <Stat value={comparison.differences.length.toLocaleString()} label="Differences" tone="brand" />
            <Stat value={sev.RISK ?? 0} label="Risk: values could change" tone={sev.RISK ? "danger" : undefined} />
            <Stat value={sev.REVIEW ?? 0} label="Review: needs a decision" tone={sev.REVIEW ? "warn" : undefined} />
            <Stat value={sev.SAFE ?? 0} label="Safe to carry over" tone="ok" />
            <Stat value={sev.INFO ?? 0} label="Info: target covers it" />
          </Stats>
          <DifferencesCard comparison={comparison} decisions={decisions} setDecisions={setDecisions} />
          <ScriptsCard comparison={comparison} decisions={decisions} changedCount={changedCount} onWritten={() => setScripted(true)} />
        </>
      )}
    </>
  );
}
