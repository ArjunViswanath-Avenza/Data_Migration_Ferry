# Console guide

How the Ferry console is put together, and how to add a feature without touching the shell.

## Layout

```
ferry-console/src/
├── main.jsx              entry; loads the two stylesheets
├── App.jsx               the shell: sidebar, top bar, breadcrumbs, routing, error boundary
├── router.jsx            hash routes: #/<module>/<tab>
├── state.jsx             per-viewer state: operator name, batch, theme, sidebar, server status
├── api.js                fetch helpers for the ferry-server API
├── styles/
│   ├── tokens.css        every colour, size and font, for light and dark
│   └── app.css           shell and component styles, built only from tokens
├── kit/
│   ├── Icon.jsx          the icon set (inline SVG, no dependency)
│   └── ui.jsx            the component kit
└── modules/
    ├── registry.js       finds every module folder at build time
    ├── PlannedModule.jsx page shown for modules not built yet
    ├── boarding/         live
    ├── crossing/         planned (step 3)
    ├── manifest/         planned (step 4)
    ├── return-crossing/  planned (step 7)
    └── settings/         live
```

## Adding a module

A module is a folder under `src/modules` with an `index.jsx`. The registry picks it up at build
time. The sidebar entry, route, breadcrumb and error boundary all come from its manifest, so
`App.jsx` never needs editing.

1. Create `src/modules/<id>/index.jsx`:

   ```jsx
   import ReconPage from "./ReconPage.jsx";

   export default {
     id: "recon",                 // URL segment: #/recon
     order: 30,                   // position within its group
     group: "operations",         // migration · operations · setup (GROUPS in registry.js)
     label: "Reconciliation",
     description: "Vendor report vs register",   // one line under the label
     icon: "checkCircle",         // any name in kit/Icon.jsx
     status: "live",              // or "planned"
     summary: "Compare the vendor's migration report with what Ferry registered.",
     tabs: [                      // optional; routed as #/recon/<tab>
       { id: "summary", label: "Summary", icon: "layers" },
       { id: "gaps", label: "Gaps", icon: "alert" },
     ],
     Component: ReconPage,
   };
   ```

2. Write the page. It receives `{ module, sub, go, onConfigChanged }`:

   | Prop | Meaning |
   |---|---|
   | `module` | its own manifest, for the title, summary and tabs |
   | `sub` | the current tab id from the URL, or `""` |
   | `go(tab)` | switch tab and update the URL |
   | `onConfigChanged()` | call after anything that changes server config, so the top bar refreshes |

   ```jsx
   import { PageHeader, Tabs, Card } from "../../kit/ui.jsx";

   export default function ReconPage({ module, sub, go }) {
     const tab = sub || module.tabs[0].id;
     return (
       <>
         <PageHeader eyebrow="Operations" icon={module.icon} title={module.label} description={module.summary} />
         <Tabs items={module.tabs} value={tab} onChange={go} />
         {tab === "summary" && <Card icon="layers" title="Summary">…</Card>}
       </>
     );
   }
   ```

3. To show a feature before it is built, set `status: "planned"`, a `milestone` such as
   `"Step 5"`, a `roadmap` list of `{ icon, title, text }`, and `Component: PlannedModule`.
   When it is built, swap in the real page and set `status: "live"`.

A manifest missing `id`, `group`, `label`, `icon` or `Component`, or naming an unknown group, is left
out and logged in the browser console. A page that throws is caught by its own error boundary,
so the rest of the console keeps working.

To add a sidebar group, add it to `GROUPS` in `registry.js`.

## The kit

Build screens from `kit/ui.jsx` so new features match the existing ones.

| Component | Use |
|---|---|
| `PageHeader` | top of every page: eyebrow, title, description, actions |
| `Card` | a section with icon, title, sub-line and header actions |
| `Button`, `LinkButton` | `variant` primary · secondary · ghost · danger; `size="sm"`; `icon` |
| `Badge` | `tone` neutral · brand · ok · warn · danger · info · live; `dot` |
| `AssumptionTag` | an assumption ID such as `A-08` |
| `Banner` | inline message; `tone` info · ok · warn · danger |
| `Stats` + `Stat` | headline numbers; `tone` ok · warn · danger · brand |
| `Table` | `columns=[{ key, label, render?, num? }]`, `rows`, `empty` |
| `Field` | label + input + hint |
| `KeyValue` | label/value pairs |
| `Stepper` | progress across steps (`done` · `current` · `todo`) |
| `Tabs` | sub-sections; pair with `sub`/`go` so tabs are linkable |
| `EmptyState` | what to do when there is nothing yet |
| `Json` | formatted request or response bodies |

Helpers: `statusTone(status)`, `severityTone(severity)`, `when(timestamp)`, `plural(n, word)`.

## Theme

The theme follows the Avenza brand: black chrome, orange accent (`#F28500`), Sora for headings,
Inter for text, JetBrains Mono for IDs and code.

- All colours live in `styles/tokens.css`. Use the semantic tokens (`--surface`, `--text`,
  `--muted`, `--accent`, `--ok`, `--danger`…), never raw hex, so light and dark both work.
- The theme switch in the top bar offers system, light and dark. "System" (the default) follows the
  OS setting. The choice is stored per browser.
- The sidebar stays black in both themes.
- Orange is the brand, not a warning. Live calls use their own red token (`--live`), so the
  "Live calls on" state can never be mistaken for decoration.
- The layout switches to an off-canvas sidebar at 900px and below, and is checked at 375px with
  no horizontal scroll. Wide tables scroll inside their own container.
