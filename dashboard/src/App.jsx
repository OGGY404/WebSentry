import { useEffect, useState } from "react";

import { getHealth } from "./api.js";
import FlaggedTable from "./components/FlaggedTable.jsx";
import Login from "./components/Login.jsx";
import ModelPanel from "./components/ModelPanel.jsx";
import ScansTable from "./components/ScansTable.jsx";
import StatsPanel from "./components/StatsPanel.jsx";

const TABS = [
  { key: "stats", label: "Statistics" },
  { key: "scans", label: "All scans" },
  { key: "flagged", label: "Flagged websites" },
  { key: "model", label: "Model" }
];

export default function App() {
  const [username, setUsername] = useState(null);
  const [tab, setTab] = useState("stats");
  const [health, setHealth] = useState(null);

  // Show which parts of the system are switched on, so a demo never starts
  // with a surprise such as the database being off.
  useEffect(() => {
    if (username) {
      getHealth().then(setHealth).catch(() => setHealth(null));
    }
  }, [username]);

  if (!username) {
    return <Login onSignedIn={setUsername} />;
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">W</span>
          <span>WebSentry Admin</span>
        </div>
        <div className="topbar-right">
          {health && (
            <span>
              storage: {health.storage} · explanations: {health.claudeExplanations} · safe browsing:{" "}
              {health.safeBrowsing}
            </span>
          )}
          <button className="button ghost" onClick={() => setUsername(null)}>
            Sign out
          </button>
        </div>
      </header>

      <nav className="tabs">
        {TABS.map((item) => (
          <button
            key={item.key}
            className={`tab ${tab === item.key ? "is-active" : ""}`}
            onClick={() => setTab(item.key)}
          >
            {item.label}
          </button>
        ))}
      </nav>

      <main className="content">
        {tab === "stats" && <StatsPanel />}
        {tab === "scans" && <ScansTable />}
        {tab === "flagged" && <FlaggedTable />}
        {tab === "model" && <ModelPanel />}
      </main>
    </div>
  );
}
