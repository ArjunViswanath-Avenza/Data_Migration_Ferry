import BoardingPage from "./BoardingPage.jsx";

export default {
  id: "boarding",
  order: 10,
  group: "migration",
  label: "Boarding",
  description: "Load, check and plan",
  icon: "boarding",
  status: "live",
  summary: "Load source files, map them to the canonical model, check them, and see exactly what would be sent for every contract. Nothing leaves this machine here.",
  Component: BoardingPage,
};
