import { useEffect, useState } from "react";

import { getFlagged } from "../api.js";
import { bandClass, formatWhen } from "../format.js";

/** The websites that have been flagged as risky most often. */
export default function FlaggedTable() {
  const [rows, setRows] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    getFlagged(20).then(setRows).catch((problem) => setError(problem.message));
  }, []);

  if (error) {
    return <div className="message error">Could not load flagged websites: {error}</div>;
  }
  if (!rows) {
    return <div className="empty">Loading flagged websites...</div>;
  }

  return (
    <div className="card">
      <h2 className="card-title">Most flagged websites</h2>

      {rows.length === 0 ? (
        <div className="empty">
          No website has been flagged yet. Only medium and high risk scans appear here.
        </div>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Website</th>
                <th>Times flagged</th>
                <th>Last score</th>
                <th>Last verdict</th>
                <th>First seen</th>
                <th>Last seen</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.domain}>
                  <td className="url-cell" style={{ fontWeight: 500 }}>
                    {row.domain}
                  </td>
                  <td className="mono">{row.hitCount}</td>
                  <td>
                    <span className={`score ${bandClass(row.lastRiskBand)}`}>
                      {row.lastRiskScore}
                    </span>
                  </td>
                  <td>
                    <span className={`pill ${bandClass(row.lastRiskBand)}`}>
                      {row.lastRiskBand}
                    </span>
                  </td>
                  <td className="mono">{formatWhen(row.firstSeen)}</td>
                  <td className="mono">{formatWhen(row.lastSeen)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
