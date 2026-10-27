import { createContext, useContext, useEffect, useState } from "react";

// Small per-viewer conveniences only (operator name, selected batch). Everything that matters
// lives on the server; storage may be unavailable, so every access is guarded.
const read = (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
const write = (k, v) => { try { localStorage.setItem(k, v); } catch { /* storage blocked: keep in memory */ } };

const Ctx = createContext(null);

export function FerryState({ children }) {
  const [operator, setOperator] = useState(() => read("ferry:operator", ""));
  const [batchId, setBatchId] = useState(() => read("ferry:batch", ""));
  const [status, setStatus] = useState(null);

  useEffect(() => write("ferry:operator", operator), [operator]);
  useEffect(() => write("ferry:batch", batchId), [batchId]);

  return (
    <Ctx.Provider value={{ operator, setOperator, batchId, setBatchId, status, setStatus }}>
      {children}
    </Ctx.Provider>
  );
}

export const useFerry = () => useContext(Ctx);
