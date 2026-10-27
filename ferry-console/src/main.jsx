import React from "react";
import { createRoot } from "react-dom/client";
import "./styles/tokens.css";
import "./styles/app.css";
import App from "./App.jsx";
import { FerryState } from "./state.jsx";

createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <FerryState>
      <App />
    </FerryState>
  </React.StrictMode>
);
