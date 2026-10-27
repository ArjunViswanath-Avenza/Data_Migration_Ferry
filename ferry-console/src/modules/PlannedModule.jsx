import { Badge, Card, EmptyState, Icon, PageHeader } from "../kit/ui.jsx";
import { GROUPS } from "./registry.js";

/**
 * The page a planned module shows until it is built: what it will do and what is coming, taken
 * from the module's own manifest (summary + roadmap). Building it later = replacing Component.
 */
export default function PlannedModule({ module }) {
  const group = GROUPS.find((g) => g.id === module.group);
  return (
    <>
      <PageHeader eyebrow={group?.label} icon={module.icon} title={module.label} description={module.summary}
        actions={<Badge tone="brand" dot>{module.milestone ? `Planned · ${module.milestone}` : "Planned"}</Badge>} />
      <Card>
        <EmptyState icon={module.icon} title={`${module.label} is not built yet`}>
          It arrives in {module.milestone ? module.milestone.toLowerCase() : "a later build step"} of the design
          (<code>docs/DESIGN.md</code>, section 9). Until then, Boarding prepares and checks everything it will run.
        </EmptyState>
        {module.roadmap?.length > 0 && (
          <div className="roadmap">
            {module.roadmap.map((r) => (
              <div className="item" key={r.title}>
                <span className="ri"><Icon name={r.icon || "checkCircle"} /></span>
                <div><b>{r.title}</b><span>{r.text}</span></div>
              </div>
            ))}
          </div>
        )}
      </Card>
    </>
  );
}
