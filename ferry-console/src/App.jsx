import { useEffect, useState } from "react";
import { api } from "./api.js";
import { useFerry } from "./state.jsx";
import { useRoute } from "./router.jsx";
import { DEFAULT_MODULE, GROUPS, MODULES } from "./modules/registry.js";
import { AreaBoundary, Banner, Icon } from "./kit/ui.jsx";

/**
 * The shell. Navigation, breadcrumbs and routing come from the module registry; the header shows,
 * on every screen, which target environment the console points at and whether it can write to it.
 * Knowing where you are pointed is a safety feature, not decoration.
 */
export default function App() {
  const { operator, setOperator, status, setStatus, theme, setTheme, collapsed, setCollapsed } = useFerry();
  const route = useRoute();
  const [navOpen, setNavOpen] = useState(false);
  const [error, setError] = useState("");

  const refreshStatus = () =>
    api.get("/api/platform/status").then((s) => { setStatus(s); setError(""); }).catch((e) => setError(e.message));

  useEffect(() => { refreshStatus(); }, []);
  // The console is often opened before the server has finished starting: keep trying until it answers.
  useEffect(() => {
    if (!error) return undefined;
    const t = setInterval(refreshStatus, 5000);
    return () => clearInterval(t);
  }, [error]);

  const mod = MODULES.find((m) => m.id === route.module) ?? MODULES.find((m) => m.id === DEFAULT_MODULE);
  useEffect(() => { if (mod && route.module !== mod.id) route.go(mod.id); }, [mod?.id, route.module]);
  useEffect(() => { setNavOpen(false); }, [route.module, route.sub]);

  const group = GROUPS.find((g) => g.id === mod?.group);
  const tab = mod?.tabs?.find((t) => t.id === route.sub);
  const env = status?.environment;
  const Page = mod?.Component;

  return (
    <div className={`app${collapsed ? " collapsed" : ""}${navOpen ? " nav-open" : ""}`}>
      <aside className="sidebar" aria-label="Navigation">
        <div className="sb-brand">
          <span className="sb-mark"><Icon name="ferry" size={20} strokeWidth={2} /></span>
          <span className="sb-title"><b>Data Migration Ferry</b><span>MIGRATION CONSOLE</span></span>
        </div>
        <nav className="sb-nav">
          {GROUPS.map((g) => {
            const items = MODULES.filter((m) => m.group === g.id);
            if (!items.length) return null;
            return [
              <div key={`g-${g.id}`} className="sb-group">{g.label}</div>,
              ...items.map((m) => (
                <button key={m.id} className="sb-item" aria-current={m.id === mod?.id ? "page" : undefined}
                  onClick={() => route.go(m.id)} title={collapsed ? m.label : undefined}>
                  <span className="sb-icon"><Icon name={m.icon} size={19} /></span>
                  <span className="sb-label"><b>{m.label}</b>{m.description && <span>{m.description}</span>}</span>
                  {m.status === "planned" && <span className="sb-tag">{m.milestone || "Soon"}</span>}
                </button>
              )),
            ];
          })}
        </nav>
        <div className="sb-foot">
          <div className="sb-env" title={env ? `${env.label} · ${env.hostUrl}` : "Not connected"}>
            <span className={`dot ${!env ? "off" : env.liveCalls ? "live" : ""}`} />
            <span className="sb-env-text" style={{ minWidth: 0 }}>
              <b>{env ? env.label : error ? "Server not reachable" : "Connecting…"}</b>
              {status && <span>v{status.version} · {env.liveCalls ? "live calls on" : "safe mode"}</span>}
            </span>
          </div>
          <button className="sb-collapse" onClick={() => setCollapsed(!collapsed)} aria-label={collapsed ? "Expand navigation" : "Collapse navigation"}>
            <Icon name={collapsed ? "chevronRight" : "chevronLeft"} size={16} /><span>Collapse</span>
          </button>
        </div>
      </aside>
      <div className="scrim" onClick={() => setNavOpen(false)} />

      <div className="main">
        <header className="topbar">
          <button className="btn ghost sm tb-menu" onClick={() => setNavOpen(true)} aria-label="Open navigation">
            <Icon name="menu" size={18} />
          </button>
          <nav className="crumbs" aria-label="Breadcrumb">
            {group && <span className="crumb-group">{group.label}</span>}
            {group && <span className="sep">/</span>}
            <b>{mod?.label}</b>
            {tab && <><span className="sep">/</span><span>{tab.label}</span></>}
          </nav>
          <div className="tb-spacer" />
          {env && (
            <span className={`env-pill${env.liveCalls ? " live" : ""}`} title="Target environment">
              <span className={`dot${env.liveCalls ? " live" : ""}`} />
              <b>{env.liveCalls ? "Live calls on" : "Safe mode"}</b>
              <span className="muted">{env.label}</span>
              <span className="host">{env.hostUrl}</span>
            </span>
          )}
          <div className="seg" role="group" aria-label="Theme">
            {[["system", "monitor", "Follow system"], ["light", "sun", "Light"], ["dark", "moon", "Dark"]].map(([v, ic, label]) => (
              <button key={v} aria-pressed={theme === v} onClick={() => setTheme(v)} title={label} aria-label={label}>
                <Icon name={ic} size={15} />
              </button>
            ))}
          </div>
          <label className="operator" htmlFor="operator">
            <span className="avatar" aria-hidden="true">{initials(operator)}</span>
            <span className="sr-only">Your name</span>
            <input id="operator" value={operator} onChange={(e) => setOperator(e.target.value)} placeholder="Your name" />
          </label>
        </header>

        <main className="content">
          <div className="page">
            {error && (
              <Banner tone="danger" title="The server is not reachable.">
                {error}. Start it with <code>mvn spring-boot:run</code> in <code>ferry-server</code>; this page reconnects by itself.
              </Banner>
            )}
            {Page && (
              <AreaBoundary areaId={mod.id}>
                <Page module={mod} sub={route.sub} go={(sub) => route.go(mod.id, sub)} onConfigChanged={refreshStatus} />
              </AreaBoundary>
            )}
          </div>
        </main>
      </div>
    </div>
  );
}

function initials(name) {
  const parts = (name || "").trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return <Icon name="user" size={15} />;
  return (parts[0][0] + (parts[1]?.[0] ?? "")).toUpperCase();
}
