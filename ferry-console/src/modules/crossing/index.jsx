import PlannedModule from "../PlannedModule.jsx";

export default {
  id: "crossing",
  order: 20,
  group: "migration",
  label: "Crossing",
  description: "Run the plan",
  icon: "crossing",
  status: "planned",
  milestone: "Step 3",
  summary: "Execute a reviewed plan against the target: ordered calls per contract, parallel across contracts, with live progress.",
  roadmap: [
    { icon: "key", title: "Target sign-in", text: "Fabric login with the app key and secret entered at run time; the token is refreshed before it expires." },
    { icon: "plan", title: "Runs the reviewed plan", text: "Exactly the steps and requests shown in Boarding, with IDs filled in from earlier steps." },
    { icon: "shield", title: "Judges every response", text: "Success only when the target says so (assumption A-10), never on HTTP 200 alone." },
    { icon: "database", title: "Registers every ID", text: "Each created contract, user and group goes into the ID register, so reruns skip it." },
  ],
  Component: PlannedModule,
};
