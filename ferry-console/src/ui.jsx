// Shared building blocks. Panels use these instead of styling elements themselves.
import { Component, isValidElement } from "react";

export function Card({ title, sub, actions, children }) {
  return (
    <section className="card">
      {(title || actions) && (
        <div className="card-head">
          <div>
            {title && <h2>{title}</h2>}
            {sub && <div className="sub">{sub}</div>}
          </div>
          {actions && <div className="row">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

export function Button({ kind = "solid", small, ...props }) {
  const cls = ["btn", kind === "ghost" ? "ghost" : "", small ? "small" : ""].join(" ").trim();
  return <button className={cls} {...props} />;
}

export function Badge({ tone = "neutral", children }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}

export function Banner({ tone = "info", title, children }) {
  return (
    <div className={`banner ${tone}`} role={tone === "danger" ? "alert" : undefined}>
      {title && <b>{title} </b>}
      {children}
    </div>
  );
}

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
          <tr>{columns.map((c) => <th key={c.key}>{c.label}</th>)}</tr>
        </thead>
        <tbody>
          {(!rows || rows.length === 0) && (
            <tr><td colSpan={columns.length} className="empty">{empty}</td></tr>
          )}
          {rows && rows.map((r, i) => (
            <tr key={r.id ?? r.key ?? i}>
              {columns.map((c) => (
                <td key={c.key} className={c.num ? "num" : undefined}>
                  {c.render ? c.render(r) : formatCell(r[c.key])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function formatCell(v) {
  if (v === null || v === undefined || v === "") return <span className="muted">—</span>;
  if (isValidElement(v)) return v;
  if (typeof v === "object") return <code>{JSON.stringify(v)}</code>;
  return String(v);
}

/** Keeps one failing area from blanking the whole console. */
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

export function Field({ label, htmlFor, children }) {
  return (
    <div className="field">
      {label && <label htmlFor={htmlFor}>{label}</label>}
      {children}
    </div>
  );
}

export function Json({ value }) {
  return <pre className="json">{JSON.stringify(value, null, 2)}</pre>;
}

export function severityTone(s) {
  return s === "ERROR" ? "danger" : s === "WARN" ? "warn" : "neutral";
}

export function statusTone(s) {
  switch (s) {
    case "VALID": case "READY": case "SUCCESS": case "CONFIRMED": return "ok";
    case "INVALID": case "BLOCKED": case "FAILED": return "danger";
    case "EXCLUDED": case "PARTIAL": case "OVERRIDDEN": case "WARN": return "warn";
    case "ASSUMED": case "STAGED": case "PLANNED": return "sea";
    default: return "neutral";
  }
}

export function when(ts) {
  if (!ts) return "—";
  const d = new Date(ts);
  return isNaN(d) ? String(ts) : d.toLocaleString();
}
