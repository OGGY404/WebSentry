import { useEffect, useState } from "react";

import { getStats } from "../api.js";

/** Totals, a breakdown by risk band, and a simple bar chart of scans per day. */
export default function StatsPanel() {
  const [stats, setStats] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    getStats(7).then(setStats).catch((problem) => setError(problem.message));
  }, []);

  if (error) {
    return <div className="message error">Could not load statistics: {error}</div>;
  }
  if (!stats) {
    return <div className="empty">Loading statistics...</div>;
  }

  const bands = stats.bandCounts || {};
  const series = stats.scansPerDay || [];
  // The tallest bar sets the scale. Minimum of 1 avoids dividing by zero.
  const busiestDay = Math.max(1, ...series.map((day) => day.count));

  return (
    <>
      <div className="stat-grid">
        <div className="stat">
          <div className="stat-label">Total scans</div>
          <div className="stat-value">{stats.totalScansAllTime}</div>
          <div className="stat-note">all time</div>
        </div>
        <div className="stat">
          <div className="stat-label">Last {stats.windowDays} days</div>
          <div className="stat-value">{stats.scansInWindow}</div>
          <div className="stat-note">scans in this window</div>
        </div>
        <div className="stat">
          <div className="stat-label">Safe</div>
          <div className="stat-value" style={{ color: "var(--safe)" }}>
            {bands.LOW || 0}
          </div>
          <div className="stat-note">Stay Safe</div>
        </div>
        <div className="stat">
          <div className="stat-label">Caution</div>
          <div className="stat-value" style={{ color: "var(--caution)" }}>
            {bands.MEDIUM || 0}
          </div>
          <div className="stat-note">Proceed with Caution</div>
        </div>
        <div className="stat">
          <div className="stat-label">Dangerous</div>
          <div className="stat-value" style={{ color: "var(--danger)" }}>
            {bands.HIGH || 0}
          </div>
          <div className="stat-note">Leave Immediately</div>
        </div>
        <div className="stat">
          <div className="stat-label">Average scan</div>
          <div className="stat-value">{stats.averageScanMs}<span style={{ fontSize: 15 }}> ms</span></div>
          <div className="stat-note">target is under 1000 ms</div>
        </div>
      </div>

      <div className="card">
        <h2 className="card-title">Scans per day</h2>
        {series.length === 0 ? (
          <div className="empty">No scans yet.</div>
        ) : (
          <div className="chart">
            {series.map((day) => (
              <div className="chart-col" key={day.date}>
                <span className="chart-count">{day.count}</span>
                <div
                  className="chart-bar"
                  style={{ height: `${(day.count / busiestDay) * 100}%` }}
                  title={`${day.count} scans on ${day.date}`}
                />
                <span className="chart-day">{day.date.slice(5)}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="card">
        <h2 className="card-title">Explanations</h2>
        <p className="small muted" style={{ margin: 0, lineHeight: 1.6 }}>
          {stats.aiExplanationCount} explanations were written by Claude and{" "}
          {stats.templateExplanationCount} came from the built-in template.
          The template is used whenever the Claude API is switched off or does not
          answer, so a user always gets a readable report.
        </p>
      </div>
    </>
  );
}
