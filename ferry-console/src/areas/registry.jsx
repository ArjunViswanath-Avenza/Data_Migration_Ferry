import Upcoming from "./Upcoming.jsx";
import Settings from "./Settings.jsx";
import BoardingPanel from "./boarding/BoardingPanel.jsx";

/**
 * The areas of the console, in the order a migration runs. Adding an area = one entry here plus
 * a panel component; the shell builds navigation from this list.
 */
export const AREAS = [
  {
    id: "boarding", group: "migration", label: "Boarding",
    blurb: "Load, map, validate, plan",
    Component: BoardingPanel,
  },
  {
    id: "crossing", group: "migration", label: "Crossing", soon: "Step 3",
    blurb: "Run the plan",
    Component: () => <Upcoming title="Crossing" step="3" text="Execute the plan against the target: ordered calls per contract, parallel across contracts, with retries and live progress." />,
  },
  {
    id: "manifest", group: "migration", label: "Manifest", soon: "Step 4",
    blurb: "Evidence and reconciliation",
    Component: () => <Upcoming title="Manifest" step="4" text="Results per item and step, the ID register, the vendor reconciliation report and exception log, and Excel export." />,
  },
  {
    id: "return", group: "migration", label: "Return Crossing", soon: "Step 7",
    blurb: "Roll back",
    Component: () => <Upcoming title="Return Crossing" step="7" text="Roll back selected contracts or users through the vendor delete API, limited to IDs this tool created, with a preview first." />,
  },
  {
    id: "settings", group: "setup", label: "Settings",
    blurb: "Environment, assumptions, history",
    Component: Settings,
  },
];
