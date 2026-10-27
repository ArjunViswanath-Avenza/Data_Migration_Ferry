// The component kit. Every module builds its screens from these, so new features look and
// behave like the existing ones without restyling anything.
import { Component, isValidElement } from "react";
import Icon from "./Icon.jsx";

export { Icon };

/** Top of every screen: eyebrow (area group), title, one-line purpose, and actions. */
export function PageHeader({ eyebrow, icon, title, description, actions }) {
  return (
    <header className="page-head">
      <div>
        {eyebrow && <div className="eyebrow">{icon && <Icon name={icon} size={14} />}{eyebrow}</div>}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </header>
  );
}

export function Card({ title, sub, icon, actions, children, flat }) {
  return (
    <section className={`card${flat ? " flat" : ""}`}>
      {(title || actions) && (
        <div className="card-head">
          <div className="card-title">
            {icon && <span className="card-icon"><Icon name={icon} /></span>}
            <div>
              {title && <h2>{title}</h2>}
              {sub && <div className="sub">{sub}</div>}
            </div>
          </div>
          {actions && <div className="card-actions">{actions}</div>}
        </div>
      )}
      <div className="card-body">{children}</div>
    </section>
  );
}

/** variant: primary · secondary · ghost · danger */
export function Button({ variant = "primary", size, icon, children, ...props }) {
  return (
    <button className={`btn ${variant}${size === "sm" ? " sm" : ""}`} {...props}>
      {icon && <Icon name={icon} size={size === "sm" ? 14 : 16} />}
      {children}
    </button>
  );
}

/** A link styled as a button (downloads, external pages). */
export function LinkButton({ variant = "secondary", size, icon, children, ...props }) {
  return (
    <a className={`btn ${variant}${size === "sm" ? " sm" : ""}`} {...props}>
      {icon && <Icon name={icon} size={size === "sm" ? 14 : 16} />}
      {children}
    </a>
  );
}

/** tone: neutral · brand · ok · warn · danger · info · live */
export function Badge({ tone = "neutral", dot, children }) {
  return <span className={`badge ${tone}`}>{dot && <span className="bd" />}{children}</span>;
}

/** An assumption ID such as A-08, always styled the same way. */
export function AssumptionTag({ id }) {
  return <span className="tag-id" title={`Relied on assumption ${id} (see Settings → Assumptions)`}>{id}</span>;
}

const BANNER_ICON = { info: "info", brand: "info", ok: "checkCircle", warn: "alert", danger: "xCircle" };

export function Banner({ tone = "info", title, children }) {
  return (
    <div className={`banner ${tone}`} role={tone === "danger" ? "alert" : undefined}>
      <span className="bi"><Icon name={BANNER_ICON[tone] || "info"} /></span>
      <div>{title && <b>{title} </b>}{children}</div>
    </div>
  );
}

export function Stats({ children }) { return <div className="stats">{children}</div>; }

/** tone: ok · warn · danger · brand */
export function Stat({ value, label, tone }) {
  return (
    <div className={`stat ${tone || ""}`}>
      <span className="v">{value ?? "—"}</span>
      <span className="l">{label}</span>
    </div>
  );
}

/** columns: [{ key, label, render?(row), num? }] */
export function Table({ columns, rows, empty = "Nothing to show yet." }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>{columns.map((c) => <th key={c.key} className={c.num ? "num" : undefined}>{c.label}</th>)}</tr>
        </thead>
        <tbody>
          {(!rows || rows.length === 0) && <tr><td colSpan={columns.length} className="empty-cell">{empty}</td></tr>}
          {rows && rows.map((r, i) => (
            <tr key={r.key ?? r.id ?? i}>
              {columns.map((c) => (
                <td key={c.key} className={c.num ? "num" : undefined}>{c.render ? c.render(r) : formatCell(r[c.key])}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function formatCell(v) {
  if (v === null || v === undefined || v === "") return <span className="faint">—</span>;
  if (isValidElement(v)) return v;
  if (typeof v === "object") return <code>{JSON.stringify(v)}</code>;
  return String(v);
}

export function Field({ label, htmlFor, hint, children }) {
  return (
    <div className="field">
      {label && <label htmlFor={htmlFor}>{label}</label>}
      {children}
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

/** Label/value pairs: [[label, value], …] */
export function KeyValue({ pairs }) {
  return (
    <div className="kv">
      {pairs.map(([k, v]) => [<div key={`k-${k}`}>{k}</div>, <div key={`v-${k}`}>{v ?? <span className="faint">—</span>}</div>])}
    </div>
  );
}

/** steps: [{ id, label, hint, state: done | current | todo }] */
export function Stepper({ steps }) {
  return (
    <ol className="stepper" style={{ "--steps": steps.length, listStyle: "none", margin: 0, padding: 0 }} aria-label="Progress">
      {steps.map((s, i) => (
        <li key={s.id} className={`step ${s.state}`} aria-current={s.state === "current" ? "step" : undefined}>
          <span className="n">{s.state === "done" ? <Icon name="check" size={15} strokeWidth={2.4} /> : i + 1}</span>
          <span className="t"><b>{s.label}</b>{s.hint && <span>{s.hint}</span>}</span>
        </li>
      ))}
    </ol>
  );
}

/** items: [{ id, label, count? }] */
export function Tabs({ items, value, onChange, label = "Sections" }) {
  return (
    <div className="tabs" role="tablist" aria-label={label}>
      {items.map((t) => (
        <button key={t.id} role="tab" aria-selected={t.id === value} onClick={() => onChange(t.id)}>
          {t.icon && <Icon name={t.icon} size={15} />}{t.label}
          {t.count !== undefined && <span className="count">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}

export function EmptyState({ icon = "layers", title, children, action }) {
  return (
    <div className="empty">
      <span className="ei"><Icon name={icon} size={22} /></span>
      {title && <h3>{title}</h3>}
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function Json({ value }) {
  return <pre className="json">{JSON.stringify(value, null, 2)}</pre>;
}

/** Keeps one failing module from blanking the whole console. */
export class AreaBoundary extends Component {
  constructor(props) { super(props); this.state = { error: null }; }
  static getDerivedStateFromError(error) { return { error }; }
  componentDidUpdate(prev) { if (prev.areaId !== this.props.areaId && this.state.error) this.setState({ error: null }); }
  render() {
    if (!this.state.error) return this.props.children;
    return (
      <Banner tone="danger" title="This area could not be shown.">
        {String(this.state.error.message || this.state.error)}. Other areas still work; reload the page to try again.
      </Banner>
    );
  }
}

export function severityTone(s) { return s === "ERROR" ? "danger" : s === "WARN" ? "warn" : "neutral"; }

export function statusTone(s) {
  switch (s) {
    case "VALID": case "READY": case "SUCCESS": case "CONFIRMED": case "ALREADY_DONE": return "ok";
    case "INVALID": case "BLOCKED": case "FAILED": return "danger";
    case "EXCLUDED": case "PARTIAL": case "WARN": case "OVERRIDDEN": return "warn";
    case "ASSUMED": case "PLANNED": case "RUNNING": return "brand";
    default: return "neutral";
  }
}

export function when(ts) {
  if (!ts) return "—";
  const d = new Date(ts);
  return isNaN(d) ? String(ts) : d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

export function plural(n, one, many = `${one}s`) { return `${n} ${n === 1 ? one : many}`; }
