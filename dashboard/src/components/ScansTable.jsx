import { useEffect, useState } from "react";

import { getScans } from "../api.js";
import { bandClass, formatWhen, shorten } from "../format.js";

/** Every scan in the system, newest first, one page at a time. */
export default function ScansTable() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    setError("");
    getScans(page, 25).then(setData).catch((problem) => setError(problem.message));
  }, [page]);

  if (error) {
    return <div className="message error">Could not load scans: {error}</div>;
  }
  if (!data) {
    return <div className="empty">Loading scans...</div>;
  }
  if (data.items.length === 0) {
    return (
      <div className="card">
        <div className="empty">
          No scans yet. Browse to a website with the extension installed, then come back.
        </div>
      </div>
    );
  }

  return (
    <div className="card">
      <h2 className="card-title">All scans ({data.totalItems})</h2>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Website</th>
              <th>Score</th>
              <th>Verdict</th>
              <th>Explanation</th>
              <th>Time</th>
              <th>Speed</th>
            </tr>
          </thead>
          <tbody>
            {data.items.map((scan) => (
              <tr key={scan.id}>
                <td className="url-cell">
                  <div style={{ fontWeight: 500 }}>{scan.domain}</div>
                  <div className="muted small" style={{ marginTop: 2 }}>
                    {shorten(scan.url)}
                  </div>
                </td>
                <td>
                  <span className={`score ${bandClass(scan.riskBand)}`}>{scan.riskScore}</span>
                </td>
                <td>
                  <span className={`pill ${bandClass(scan.riskBand)}`}>{scan.recommendation}</span>
                </td>
                <td className="explanation-cell">{scan.aiExplanation}</td>
                <td className="mono">{formatWhen(scan.timestamp)}</td>
                <td className="mono">{scan.scanDurationMs} ms</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="pager">
        <button
          className="button ghost"
          onClick={() => setPage((current) => Math.max(0, current - 1))}
          disabled={page === 0}
        >
          Previous
        </button>
        <span>
          Page {data.page + 1} of {Math.max(1, data.totalPages)}
        </span>
        <button
          className="button ghost"
          onClick={() => setPage((current) => current + 1)}
          disabled={data.page + 1 >= data.totalPages}
        >
          Next
        </button>
      </div>
    </div>
  );
}
