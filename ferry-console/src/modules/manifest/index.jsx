import PlannedModule from "../PlannedModule.jsx";

export default {
  id: "manifest",
  order: 30,
  group: "migration",
  label: "Manifest",
  description: "Evidence and reconciliation",
  icon: "manifest",
  status: "planned",
  milestone: "Step 4",
  summary: "Prove what arrived: results per item and step, the ID register, the vendor reconciliation report and exception log.",
  roadmap: [
    { icon: "checkCircle", title: "Result per item and step", text: "Every request, response and outcome, searchable by contract, CIF or user." },
    { icon: "sheet", title: "Excel evidence pack", text: "One export per run for sign-off." },
    { icon: "layers", title: "Vendor reconciliation", text: "GetMigrationReconReport with expected counts calculated from the batch." },
    { icon: "alert", title: "Vendor exception log", text: "GetMigrationExceptions, matched to the items that caused them." },
  ],
  Component: PlannedModule,
};
