import { useEffect, useState } from "react";
import { api } from "./api.js";
import { useFerry } from "./state.jsx";
import { Badge, Banner } from "./ui.jsx";
import { AREAS } from "./areas/registry.jsx";

/**
 * The shell. The header shows, on every screen, which target environment the tool points at and
 * whether it can write to it. Knowing where you are pointed is a safety feature, not decoration.
 */
export default function App() {
  const { operator, setOperator, status, setStatus } = useFerry();
  const [areaId, setAreaId] = useState(() => {
    const h = location.hash.replace("#", "");
    return AREAS.some((a) => a.id === h) ? h : "boarding";
  });
  const [error, setError] = useState("");

  const refreshStatus = () =>
    api.get("/api/platform/status").then((s) => { setStatus(s); setError(""); })
      .catch((e) => setError(e.message));

  useEffect(() => { refreshStatus(); }, []);
  useEffect(() => { if (location.hash !== `#${areaId}`) history.replaceState(null, "", `#${areaId}`); }, [areaId]);

  const area = AREAS.find((a) => a.id === areaId) ?? AREAS[0];
  const env = status?.environment;
  const Panel = area.Component;

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M3 17c2 1.5 4 1.5 6 0s4-1.5 6 0 4 1.5 6 0" />
              <path d="M5 14l1.5-5h11L19 14" />
              <path d="M9 9V6h6v3" />
            </svg>
          </span>
          Data Migration Ferry
        </div>
        <div className="env" aria-live="polite">
          {env ? (
            <>
              <span>Target <b>{env.label}</b></span>
              <span className="host">{env.hostUrl}</span>
              {env.liveCalls
                ? <Badge tone="live">LIVE CALLS ON</Badge>
                : <Badge tone="sea">Safe mode · nothing is sent</Badge>}
            </>
          ) : <span>{error ? "Server not reachable" : "Connecting…"}</span>}
        </div>
        <div className="spacer" />
        <label className="operator" htmlFor="operator">
          Your name
          <input id="operator" value={operator} onChange={(e) => setOperator(e.target.value)} placeholder="for the audit trail" />
        </label>
      </header>

      <div className="body">
        <nav className="nav" aria-label="Areas">
          <div className="nav-group">Migration</div>
          {AREAS.filter((a) => a.group === "migration").map((a) => (
            <NavItem key={a.id} area={a} current={a.id === areaId} onClick={() => setAreaId(a.id)} />
          ))}
          <div className="nav-group">Setup</div>
          {AREAS.filter((a) => a.group === "setup").map((a) => (
            <NavItem key={a.id} area={a} current={a.id === areaId} onClick={() => setAreaId(a.id)} />
          ))}
        </nav>

        <main className="main">
          <div className="page">
            {error && (
              <Banner tone="danger" title="The server is not reachable.">
                {error}. Start it with <code>mvn spring-boot:run</code> in <code>ferry-server</code>, then reload this page.
              </Banner>
            )}
            <Panel onConfigChanged={refreshStatus} />
          </div>
        </main>
      </div>
    </div>
  );
}

function NavItem({ area, current, onClick }) {
  return (
    <button onClick={onClick} aria-current={current ? "page" : undefined}>
      <span className="nav-title">
        {area.label}
        {area.soon && <Badge>{area.soon}</Badge>}
      </span>
      <span className="nav-sub">{area.blurb}</span>
    </button>
  );
}
