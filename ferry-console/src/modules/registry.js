/**
 * Module registry. Every folder in src/modules with an index.jsx is a module; this file finds them
 * all at build time, so adding a feature means adding a folder. The shell, navigation, routing and
 * breadcrumbs are generated from the manifests and never need editing.
 *
 * Manifest (default export of modules/<id>/index.jsx):
 *   id           url segment and key, e.g. "boarding"
 *   order        position in its group (lower first)
 *   group        one of GROUPS below
 *   label        navigation name
 *   description  one line under the label in navigation
 *   icon         a name from kit/Icon.jsx
 *   status       "live" or "planned"
 *   milestone    optional tag shown in navigation, e.g. "Step 3"
 *   tabs         optional [{ id, label, icon }] sub-pages, routed as #/<id>/<tab>
 *   summary      optional one-paragraph purpose, shown in the page header
 *   roadmap      optional [{ icon, title, text }] for planned modules
 *   Component    the page; receives { module, sub, go, onConfigChanged }
 *
 * See docs/CONSOLE.md for a worked example.
 */
const found = import.meta.glob("./*/index.jsx", { eager: true });

export const GROUPS = [
  { id: "migration", label: "Migration" },
  { id: "operations", label: "Operations" },
  { id: "setup", label: "Setup" },
];

const REQUIRED = ["id", "group", "label", "icon", "Component"];

export const MODULES = Object.entries(found)
  .map(([path, mod]) => {
    const m = mod.default;
    const missing = REQUIRED.filter((k) => !m?.[k]);
    if (missing.length) { console.error(`Module ${path} is missing ${missing.join(", ")} and is not shown.`); return null; }
    if (!GROUPS.some((g) => g.id === m.group)) { console.error(`Module ${m.id}: unknown group "${m.group}".`); return null; }
    return { status: "live", order: 100, ...m };
  })
  .filter(Boolean)
  .sort((a, b) => GROUPS.findIndex((g) => g.id === a.group) - GROUPS.findIndex((g) => g.id === b.group) || a.order - b.order);

export const DEFAULT_MODULE = MODULES.find((m) => m.status === "live")?.id ?? MODULES[0]?.id;
