"""
Model loading and inference for WebSentry.

Shared by the training script and the FastAPI service so that the feature
pipeline used at training time is byte-for-byte the one used at scan time.
A mismatch between the two is the most common cause of a model that scores well
offline and behaves badly in the live demo.

Score convention (matches CLAUDE.md section 7 and the data model):
    riskScore 0   = safe
    riskScore 100 = dangerous
"""

from __future__ import annotations

from pathlib import Path

import joblib
import numpy as np
from scipy.sparse import csr_matrix, hstack

from features.url_features import FEATURE_LABELS, FEATURE_NAMES, extract_vector, hostname_of

MODEL_PATH = Path(__file__).resolve().parent / "models" / "risk_classifier.joblib"

# Band thresholds on the 0-100 risk score. These map to the three
# recommendations defined in the feature specification.
BAND_LOW_MAX = 39
BAND_MEDIUM_MAX = 69

RECOMMENDATIONS = {
    "LOW": "Stay Safe",
    "MEDIUM": "Proceed with Caution",
    "HIGH": "Leave Immediately",
}


def band_for(score: int) -> str:
    if score <= BAND_LOW_MAX:
        return "LOW"
    if score <= BAND_MEDIUM_MAX:
        return "MEDIUM"
    return "HIGH"


def lexical_matrix(urls):
    """Dense lexical feature matrix, used by the tree baselines."""
    return np.array([extract_vector(u) for u in urls], dtype=np.float32)


def build_matrix(urls, vectorizer, scaler, kind: str = "hybrid"):
    """Build the model input matrix.

    kind="hybrid"  -> [char n-grams | scaled lexical], sparse
    kind="lexical" -> lexical features only, dense

    Used identically by training and inference, so the two cannot drift.
    """
    lexical = lexical_matrix(urls)
    if kind == "lexical":
        return lexical
    hosts = [hostname_of(u) for u in urls]
    ngrams = vectorizer.transform(hosts)
    return hstack([ngrams, scaler.transform(csr_matrix(lexical))]).tocsr()


class RiskModel:
    """Loads the persisted bundle and scores URLs."""

    def __init__(self, path: Path | None = None):
        bundle = joblib.load(path or MODEL_PATH)
        self.model = bundle["model"]
        self.vectorizer = bundle.get("vectorizer")
        self.scaler = bundle.get("scaler")
        self.kind = bundle.get("kind", "hybrid")
        self.feature_names = bundle["feature_names"]
        self.metadata = bundle.get("metadata", {})
        # Coefficients for the lexical block only, used for local explanations.
        # In the hybrid model the n-gram block occupies the leading columns.
        self._lexical_offset = (
            len(self.vectorizer.vocabulary_) if self.kind == "hybrid" else 0
        )
        coefs = getattr(self.model, "coef_", None)
        self._lexical_coefs = (
            coefs[0][self._lexical_offset :] if coefs is not None else None
        )

    def score(self, url: str) -> dict:
        """Return the risk score, confidence and top contributing features."""
        matrix = build_matrix([url], self.vectorizer, self.scaler, self.kind)
        probability = float(self.model.predict_proba(matrix)[0, 1])
        risk_score = int(round(probability * 100))
        band = band_for(risk_score)
        return {
            "riskScore": risk_score,
            "riskBand": band,
            "recommendation": RECOMMENDATIONS[band],
            # Confidence in the decision, not in "phishing". A probability near
            # 0.5 is an uncertain call in either direction.
            "confidence": round(abs(probability - 0.5) * 2, 4),
            "phishingProbability": round(probability, 4),
            # Risk factors are only meaningful when the verdict actually leans
            # toward phishing. Naming "risk factors" on a site scored 0 would
            # give the explainer material that contradicts its own score, which
            # is the failure mode CLAUDE.md section 10 warns about.
            "topFeatures": self._top_features(url, matrix) if band != "LOW" else [],
            "modelVersion": self.metadata.get("model_version", "unknown"),
        }

    def _top_features(self, url: str, matrix, limit: int = 3) -> list[dict]:
        """Local explanation: which lexical features pushed this URL toward a
        phishing verdict. Contribution is coefficient times the scaled value,
        so a feature only appears when it is actually present in this URL."""
        raw = extract_vector(url)
        if self._lexical_coefs is not None:
            block = matrix[0, self._lexical_offset :]
            scaled = block.toarray()[0] if hasattr(block, "toarray") else np.asarray(block).ravel()
            contributions = self._lexical_coefs * scaled
        else:
            # Tree models expose global importances only. Weight them by whether
            # the feature is actually present in this URL so the explanation
            # still refers to something true about this address.
            importances = getattr(self.model, "feature_importances_", None)
            if importances is None:
                return []
            contributions = importances * (np.asarray(raw) > 0)
        ranked = sorted(
            (
                {
                    "feature": name,
                    "label": FEATURE_LABELS.get(name, name),
                    "contribution": round(float(value), 4),
                }
                for name, value in zip(self.feature_names, contributions)
                # Only surface features actively pushing toward phishing.
                if value > 0
            ),
            key=lambda row: row["contribution"],
            reverse=True,
        )
        return ranked[:limit]
