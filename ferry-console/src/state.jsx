import { createContext, useContext, useEffect, useState } from "react";

// Per-viewer conveniences only (operator name, selected batch, theme, sidebar). Everything that
// matters lives on the server. Storage may be unavailable, so every access is guarded.
const read = (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
const write = (k, v) => { try { localStorage.setItem(k, v); } catch { /* storage blocked: keep in memory */ } };

const Ctx = createContext(null);

/** system: follow the OS; light / dark: fixed. Applied as data-theme on <html>. */
function applyTheme(theme) {
  const root = document.documentElement;
  if (theme === "light" || theme === "dark") root.setAttribute("data-theme", theme);
  else root.removeAttribute("data-theme");
}

export function FerryState({ children }) {
  const [operator, setOperator] = useState(() => read("ferry:operator", ""));
  const [batchId, setBatchId] = useState(() => read("ferry:batch", ""));
  const [theme, setTheme] = useState(() => read("ferry:theme", "system"));
  const [collapsed, setCollapsed] = useState(() => read("ferry:sidebar", "") === "collapsed");
  const [status, setStatus] = useState(null);

  useEffect(() => write("ferry:operator", operator), [operator]);
  useEffect(() => write("ferry:batch", batchId), [batchId]);
  useEffect(() => { write("ferry:theme", theme); applyTheme(theme); }, [theme]);
  useEffect(() => write("ferry:sidebar", collapsed ? "collapsed" : ""), [collapsed]);

  return (
    <Ctx.Provider value={{ operator, setOperator, batchId, setBatchId, theme, setTheme, collapsed, setCollapsed, status, setStatus }}>
      {children}
    </Ctx.Provider>
  );
}

export const useFerry = () => useContext(Ctx);
