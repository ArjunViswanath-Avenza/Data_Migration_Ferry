import DryDockPage from "./DryDockPage.jsx";
import "./drydock.css";

export default {
  id: "drydock",
  order: 10,
  group: "tools",
  label: "Dry Dock",
  description: "Compare and refit schemas",
  icon: "drydock",
  status: "live",
  summary: "Bring one database schema in line with another. Load two SQL files, see every difference (wider or narrower types, "
    + "new, removed and renamed columns and tables, keys, indexes, views and routines), choose what travels, and get a refit "
    + "script and a data move script. Dry Dock only reads files and writes scripts; it never connects to a database.",
  Component: DryDockPage,
};
