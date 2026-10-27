import { Card } from "../ui.jsx";

export default function Upcoming({ title, step, text }) {
  return (
    <>
      <div className="page-head">
        <h1>{title}</h1>
        <p>{text}</p>
      </div>
      <Card>
        <p className="muted" style={{ margin: 0 }}>
          This area arrives in build step {step} of the design (<code>docs/DESIGN.md</code>, section 9).
        </p>
      </Card>
    </>
  );
}
