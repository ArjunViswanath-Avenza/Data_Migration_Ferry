import { useCallback, useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { Banner } from "../../ui.jsx";
import BatchCard from "./BatchCard.jsx";
import UploadCard from "./UploadCard.jsx";
import ValidateCard from "./ValidateCard.jsx";
import PlanCard from "./PlanCard.jsx";

/**
 * Boarding: the four things done before anything crosses, in the order they happen.
 * Every card reads the selected batch, so the operator picks it once.
 */
export default function BoardingPanel() {
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
      <div className="page-head">
        <h1>Boarding</h1>
        <p>Load source files, map them to the canonical model, check them, and see exactly what would be sent
          for every contract. Nothing leaves this machine here.</p>
      </div>

      {error && <Banner tone="danger" title="Could not open the batch.">{error}</Banner>}

      <BatchCard batch={batch} onSelect={(id) => { setBatchId(id); changed(); }} version={version} />

      {batch ? (
        <>
          <UploadCard batch={batch} onChanged={changed} />
          <ValidateCard batch={batch} onChanged={changed} version={version} />
          <PlanCard batch={batch} onChanged={changed} version={version} />
        </>
      ) : (
        <Banner tone="info">Create a batch, or pick one from the list, to start loading files.</Banner>
      )}
    </>
  );
}
