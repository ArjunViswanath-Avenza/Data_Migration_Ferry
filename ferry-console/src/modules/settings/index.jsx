import SettingsPage from "./SettingsPage.jsx";

export default {
  id: "settings",
  order: 10,
  group: "setup",
  label: "Settings",
  description: "Environment, assumptions, history",
  icon: "settings",
  status: "live",
  summary: "Where the console points, the open questions it works around, and what has been done so far. Values come from the config folder.",
  tabs: [
    { id: "environment", label: "Environment", icon: "globe" },
    { id: "assumptions", label: "Assumptions", icon: "book" },
    { id: "reference", label: "Reference data", icon: "database" },
    { id: "history", label: "History", icon: "history" },
  ],
  Component: SettingsPage,
};
