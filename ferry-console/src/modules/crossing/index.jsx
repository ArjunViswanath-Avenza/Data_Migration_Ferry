import CrossingPage from "./CrossingPage.jsx";
import "./crossing.css";

export default {
  id: "crossing",
  order: 20,
  group: "migration",
  label: "Crossing",
  description: "Run the plan",
  icon: "crossing",
  status: "live",
  summary: "Send a planned batch to the target. A read-only survey checks the target first; every call is then checked in the "
    + "target's database (read-back); the first contracts are a pilot; the run pauses by itself when something does not look right. "
    + "Nothing is created twice.",
  Component: CrossingPage,
};
