import PlannedModule from "../PlannedModule.jsx";

export default {
  id: "return-crossing",
  order: 40,
  group: "migration",
  label: "Return Crossing",
  description: "Roll back",
  icon: "rollback",
  status: "planned",
  milestone: "Step 7",
  summary: "Undo migrated contracts or users through the vendor delete API, limited to IDs this tool created, with a preview first.",
  roadmap: [
    { icon: "database", title: "Only what we created", text: "Targets come from the ID register; nothing else can be selected." },
    { icon: "shield", title: "Preview first", text: "What would be removed, per item, before anything is sent." },
    { icon: "rollback", title: "Vendor delete", text: "DeleteMigratedData (assumption A-12), recorded in the audit trail." },
  ],
  Component: PlannedModule,
};
