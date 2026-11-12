import DisembarkPage from "./DisembarkPage.jsx";
import "./disembark.css";

export default {
  id: "disembark",
  order: 20,
  group: "tools",
  label: "Disembark",
  description: "Un-enrol one customer",
  icon: "userMinus",
  status: "live",
  summary: "Take one customer off Temenos Digital: everything their enrolment created (logins, contract, limits, approval rules, "
    + "signatory groups, addresses) is found from one core customer ID and removed children first, in one transaction. "
    + "By default Disembark writes a script for a DBA to run; it can also preview against the database read-only.",
  Component: DisembarkPage,
};
