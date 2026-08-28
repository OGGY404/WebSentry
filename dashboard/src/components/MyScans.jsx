import { useEffect, useState } from "react";

import { getMyScans } from "../api.js";
import { bandClass, formatWhen, shorten } from "../format.js";
import { downloadCsv } from "../csv.js";

/**
 * The scans belonging to one browser, opened from the extension.
 *
 * The identifier is a random value made on the user's own computer. It is not
 * an account and it holds no personal details, so this view shows a person
 * their own history without the system ever knowing who they are.
 */
export default function MyScans({ clientId }) {
  const [scans, setScans] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!clientId) {
      return;
    }
    getMyScans(clientId, 50).then(setScans).catch((problem) => setError(problem.message));
  }, [clientId]);

  if (!clientId) {
    return (
      <div className="card">
        <div className="empty">
          Open this page from the WebSentry extension to see the websites checked on
          this computer.
        </div>
      </div>
    );
  }

  if (error) {
    return <div className="message error">Could not load your scans: {error}</div>;
  }
  if (!scans) {
    return <div className="empty">Loading your scans...</div>;
  }

  const dangerous = scans.filter((s) => s.riskBand === "HIGH").length;
  const caution = scans.filter((s) => s.riskBand === "MEDIUM").length;

  return (
    <>
      <div className="message info">
        Showing the websites checked on this computer. The identifier{" "}
        <code>{clientId}</code> was made on your own machine and is not linked to any
        name, email address or account.
      </div>

      <div className="stat-grid">
        <div className="stat">
          <div className="stat-label">Websites checked</div>
          <div className="stat-value">{scans.length}</div>
          <div className="stat-note">most recent first</div>
        </div>
        <div className="stat">
          <div className="stat-label">Warned about</div>
          <div className="stat-value" style={{ color: "var(--caution)" }}>{caution}</div>
          <div className="stat-note">Proceed with Caution</div>
        </div>
        <div className="stat">
          <div className="stat-label">Dangerous</div>
          <div className="stat-value" style={{ color: "var(--danger)" }}>{dangerous}</div>
          <div className="stat-note">Leave Immediately</div>
        </div>
      </div>

      <div className="card">
        <div className="card-head">
          <h2 className="card-title">Your websites</h2>
          {scans.length > 0 && (
            <button
              className="button ghost small-button"
              onClick={() => downloadCsv(scans, "websentry-my-websites")}
            >
              Download CSV
            </button>
          )}
        </div>

        {scans.length === 0 ? (
          <div className="empty">
            Nothing checked yet on this computer. Browse to a website with the
            extension installed, then come back.
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Website</th>
                  <th>Score</th>
                  <th>Verdict</th>
                  <th>Explanation</th>
                  <th>Time</th>
                </tr>
              </thead>
              <tbody>
                {scans.map((scan) => (
                  <tr key={scan.id}>
                    <td className="url-cell">
                      <div style={{ fontWeight: 500 }}>{scan.domain}</div>
                      <div className="muted small" style={{ marginTop: 2 }}>
                        {shorten(scan.url)}
                      </div>
                    </td>
                    <td>
                      <span className={`score ${bandClass(scan.riskBand)}`}>
                        {scan.riskScore}
                      </span>
                    </td>
                    <td>
                      <span className={`pill ${bandClass(scan.riskBand)}`}>
                        {scan.recommendation}
                      </span>
                    </td>
                    <td className="explanation-cell">{scan.aiExplanation}</td>
                    <td className="mono">{formatWhen(scan.timestamp)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}
