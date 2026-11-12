// Plain-language names for what the server sends, and the choice helpers every Dry Dock card shares.

export const ACTION_LABEL = {
  CREATE: "Create in target",
  ADD: "Add to target",
  APPLY: "Apply source",
  REPLACE: "Replace with source",
  MAP: "Map (keep target name)",
  RENAME: "Rename to source name",
  SEPARATE: "Treat as separate",
  KEEP: "Keep target as is",
  SKIP: "Skip",
  DROP: "Drop from target",
};

export const KIND_LABEL = {
  ONLY_IN_SOURCE: "Only in source",
  ONLY_IN_TARGET: "Only in target",
  CHANGED: "Changed",
  RENAMED: "Renamed?",
  NAME_ONLY: "Name only",
};

export const AREA_LABEL = {
  TABLE: "Table", COLUMN: "Column", PRIMARY_KEY: "Primary key", INDEX: "Index", FOREIGN_KEY: "Foreign key",
  TABLE_OPTIONS: "Table options", VIEW: "View", PROCEDURE: "Procedure", FUNCTION: "Function", TRIGGER: "Trigger", EVENT: "Event",
};

export const SEVERITIES = [
  { id: "RISK", label: "Risk", tone: "danger", hint: "values could be cut or converted" },
  { id: "REVIEW", label: "Review", tone: "warn", hint: "needs a person to decide" },
  { id: "SAFE", label: "Safe", tone: "ok", hint: "carried over, cannot hurt" },
  { id: "INFO", label: "Info", tone: "neutral", hint: "target already covers it" },
];

export const SEVERITY_TONE = Object.fromEntries(SEVERITIES.map((s) => [s.id, s.tone]));
const RANK = { RISK: 0, REVIEW: 1, SAFE: 2, INFO: 3 };
export const worst = (list) => list.reduce((w, d) => (w === null || RANK[d.severity] < RANK[w] ? d.severity : w), null);

/** Actions that move something from the source to the target, in order of preference. */
const CARRY = ["CREATE", "ADD", "APPLY", "REPLACE"];

export function effectiveAction(d, decisions) {
  return decisions[d.id]?.action ?? d.defaultAction;
}

export function defaultAspects(d) {
  const def = d.aspects.filter((a) => a.applyByDefault).map((a) => a.key);
  return def.length ? def : d.aspects.map((a) => a.key);
}

export function effectiveAspects(d, decisions) {
  return decisions[d.id]?.aspects ?? defaultAspects(d);
}

/** The choice that carries everything from the source, without dropping anything the target has. */
export function carryAll(d) {
  if (d.kind === "RENAMED") return null;
  const a = CARRY.find((x) => d.actions.includes(x));
  return a ? { action: a, aspects: a === "APPLY" ? d.aspects.map((x) => x.key) : undefined } : null;
}

export function isChanged(d, decisions) {
  const dec = decisions[d.id];
  if (!dec) return false;
  if (dec.action !== d.defaultAction) return true;
  if (dec.action === "APPLY" && dec.aspects) {
    const a = [...dec.aspects].sort().join(), b = [...defaultAspects(d)].sort().join();
    return a !== b;
  }
  return false;
}

/** A difference that is only about character sets or collations (table defaults or a column's own). */
const CHARSET_KEYS = new Set(["charset", "collation"]);
export function isCharsetOnly(d) {
  return d.aspects.length > 0 && d.aspects.every((a) => CHARSET_KEYS.has(a.key));
}

export function tableTitle(t) {
  if (t.sourceName && t.targetName && t.sourceName.toLowerCase() !== t.targetName.toLowerCase()) return `${t.sourceName} → ${t.targetName}`;
  return t.targetName || t.sourceName;
}

export const STATUS_LABEL = {
  SAME: "Same", CHANGED: "Changed", ONLY_IN_SOURCE: "Only in source", ONLY_IN_TARGET: "Only in target", RENAMED: "Renamed?",
};
export const STATUS_TONE = { SAME: "neutral", CHANGED: "warn", ONLY_IN_SOURCE: "ok", ONLY_IN_TARGET: "info", RENAMED: "brand" };

export function bytes(n) {
  if (!n && n !== 0) return "—";
  if (n < 1024) return `${n} B`;
  if (n < 1024 ** 2) return `${(n / 1024).toFixed(0)} KB`;
  if (n < 1024 ** 3) return `${(n / 1024 ** 2).toFixed(1)} MB`;
  return `${(n / 1024 ** 3).toFixed(2)} GB`;
}
