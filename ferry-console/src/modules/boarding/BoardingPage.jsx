import { useCallback, useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { Banner, Card, EmptyState, PageHeader, Stepper } from "../../kit/ui.jsx";
import BatchCard from "./BatchCard.jsx";
import UploadCard from "./UploadCard.jsx";
import ValidateCard from "./ValidateCard.jsx";
import PlanCard from "./PlanCard.jsx";

/**
 * Boarding: everything done before anything crosses, in the order it happens. Every card reads the
 * selected batch, so the operator picks it once.
 */
export default function BoardingPage({ module }) {
  const { batchId, setBatchId } = useFerry();
  const [batch, setBatch] = useState(null);
  const [error, setError] = useState("");
  const [version, setVersion] = useState(0);   // bumps after any change, so every card refetches

  const refresh = useCallback(() => {
    if (!batchId) { setBatch(null); return; }
    api.get(`/api/boarding/batches/${batchId}`)
      .then((b) => { setBatch(b); setError(""); })
      .catch((e) => { setBatch(null); setError(e.message); });
  }, [batchId]);

  useEffect(() => { refresh(); }, [refresh, version]);
  const changed = () => setVersion((v) => v + 1);

  return (
    <>
      <PageHeader eyebrow="Migration" icon={module.icon} title="Boarding" description={module.summary} />
      <Stepper steps={progress(batch)} />
      {error && <Banner tone="danger" title="Could not open the batch.">{error}</Banner>}
      <BatchCard batch={batch} onSelect={(id) => { setBatchId(id); changed(); }} version={version} />
      {batch ? (
        <>
          <UploadCard batch={batch} onChanged={changed} />
          <ValidateCard batch={batch} onChanged={changed} version={version} />
          <PlanCard batch={batch} onChanged={changed} version={version} />
        </>
      ) : (
        <Card>
          <EmptyState icon="layers" title="Start with a batch">
            A batch is one set of source files that travels together. Create one above, or pick an existing one,
            to load files, check them and build the plan.
          </EmptyState>
        </Card>
      )}
    </>
  );
}

function progress(batch) {
  const loaded = (batch?.counts ?? []).length > 0;
  const stage = batch?.status;
  const validated = stage === "VALIDATED" || stage === "PLANNED";
  const planned = stage === "PLANNED";
  const done = [!!batch, loaded, validated, planned];
  const firstOpen = done.indexOf(false);
  const labels = [
    ["batch", "Batch", batch ? batch.batch_id : "Create or pick"],
    ["load", "Load files", loaded ? summary(batch.counts) : "Excel or CSV"],
    ["validate", "Validate", validated ? "Checked" : "Find what cannot travel"],
    ["plan", "Plan", planned ? "Dry run ready" : "What will be sent"],
  ];
  return labels.map(([id, label, hint], i) => ({ id, label, hint, state: done[i] ? "done" : i === firstOpen ? "current" : "todo" }));
}

function summary(counts) {
  const n = counts.reduce((a, c) => a + c.records, 0);
  return `${n.toLocaleString()} records`;
}
