import { useEffect, useState } from "react";

import { getModel } from "../api.js";
import { percent } from "../format.js";

/**
 * How well the trained model performs.
 *
 * These numbers come from the ML service, which reads them from the file saved
 * during training. Showing them here means there is only one copy of the truth.
 */
export default function ModelPanel() {
  const [model, setModel] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    getModel().then(setModel).catch((problem) => setError(problem.message));
  }, []);

  if (error) {
    return <div className="message error">Could not load model details: {error}</div>;
  }
  if (!model) {
    return <div className="empty">Loading model details...</div>;
  }
  if (model.available === false) {
    return (
      <div className="message info">
        The ML service is not running, so model details cannot be shown.
        Start it with <code>uvicorn app:app --port 8000</code> inside the ml-service folder.
      </div>
    );
  }

  const metrics = model.metrics || {};
  const matrix = metrics.confusion_matrix || {};
  const comparison = model.modelComparison || {};
  const crossValidation = model.crossValidation || {};

  return (
    <>
      <div className="stat-grid">
        <div className="stat">
          <div className="stat-label">Accuracy</div>
          <div className="stat-value">{percent(metrics.accuracy)}</div>
          <div className="stat-note">internal test split</div>
        </div>
        <div className="stat">
          <div className="stat-label">Precision</div>
          <div className="stat-value">{percent(metrics.precision)}</div>
          <div className="stat-note">of flagged sites, how many were real</div>
        </div>
        <div className="stat">
          <div className="stat-label">Recall</div>
          <div className="stat-value">{percent(metrics.recall)}</div>
          <div className="stat-note">of real phishing, how much was caught</div>
        </div>
        <div className="stat">
          <div className="stat-label">F1 score</div>
          <div className="stat-value">{percent(metrics.f1)}</div>
          <div className="stat-note">balance of the two above</div>
        </div>
      </div>

      <div className="card">
        <h2 className="card-title">Model in use</h2>
        <table>
          <tbody>
            <tr>
              <td className="muted">Algorithm</td>
              <td>{model.algorithm}</td>
            </tr>
            <tr>
              <td className="muted">Version</td>
              <td>{model.modelVersion}</td>
            </tr>
            <tr>
              <td className="muted">Trained on</td>
              <td>{model.trainingRows?.toLocaleString()} labelled web addresses</td>
            </tr>
            <tr>
              <td className="muted">Trained at</td>
              <td className="mono">{model.trainedAt}</td>
            </tr>
            <tr>
              <td className="muted">Cross validation</td>
              <td>
                {crossValidation.folds
                  ? `${crossValidation.folds} folds, mean accuracy ${percent(
                      crossValidation.mean_accuracy
                    )}`
                  : "not recorded"}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div className="card">
        <h2 className="card-title">Confusion matrix (internal test split)</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th></th>
                <th>Predicted phishing</th>
                <th>Predicted safe</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className="muted">Actually phishing</td>
                <td className="mono" style={{ color: "var(--safe)" }}>{matrix.tp} (TP)</td>
                <td className="mono" style={{ color: "var(--danger)" }}>{matrix.fn} (FN)</td>
              </tr>
              <tr>
                <td className="muted">Actually safe</td>
                <td className="mono" style={{ color: "var(--danger)" }}>{matrix.fp} (FP)</td>
                <td className="mono" style={{ color: "var(--safe)" }}>{matrix.tn} (TN)</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <h2 className="card-title">Models compared during training</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Model</th>
                <th>Accuracy</th>
                <th>Precision</th>
                <th>Recall</th>
                <th>F1</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(comparison).map(([name, row]) => (
                <tr key={name}>
                  <td style={{ fontWeight: row.model === model.algorithm ? 600 : 400 }}>
                    {row.model}
                    {row.model === model.algorithm && (
                      <span className="pill low" style={{ marginLeft: 8 }}>chosen</span>
                    )}
                  </td>
                  <td className="mono">{percent(row.accuracy)}</td>
                  <td className="mono">{percent(row.precision)}</td>
                  <td className="mono">{percent(row.recall)}</td>
                  <td className="mono">{percent(row.f1)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {model.designNote && (
        <div className="message info">
          <strong>Design note.</strong> {model.designNote}
        </div>
      )}
    </>
  );
}
