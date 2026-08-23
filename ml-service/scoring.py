"""Model loading and inference for WebSentry."""

from __future__ import annotations

from pathlib import Path

import joblib
import numpy as np
from scipy.sparse import csr_matrix, hstack

from features.url_features import (
    FEATURE_LABELS,
    FEATURE_NAMES,
    extract_vector,
    hostname_of,
    is_worth_naming,
)

MODEL_PATH = Path(__file__).resolve().parent / "models" / "risk_classifier.joblib"

# Band thresholds on the 0-100 risk score.
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
    """Build the model input matrix."""
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
            # Confidence in the decision, not in "phishing".
            "confidence": round(abs(probability - 0.5) * 2, 4),
            "phishingProbability": round(probability, 4),
            # Risk factors are only meaningful when the verdict actually leans toward phishing.
            "topFeatures": self._top_features(url, matrix) if band != "LOW" else [],
            "modelVersion": self.metadata.get("model_version", "unknown"),
        }

    def _top_features(self, url: str, matrix, limit: int = 3) -> list[dict]:
        """Local explanation: which lexical features pushed this URL toward a phishing verdict."""
        raw = extract_vector(url)
        if self._lexical_coefs is not None:
            block = matrix[0, self._lexical_offset :]
            scaled = block.toarray()[0] if hasattr(block, "toarray") else np.asarray(block).ravel()
            contributions = self._lexical_coefs * scaled
        else:
            # Tree models expose global importances only.
            importances = getattr(self.model, "feature_importances_", None)
            if importances is None:
                return []
            contributions = importances * (np.asarray(raw) > 0)
        # Name a feature only when its value is genuinely large, so the label is true.
        raw_by_name = dict(zip(self.feature_names, raw))
        ranked = sorted(
            (
                {
                    "feature": name,
                    "label": FEATURE_LABELS.get(name, name),
                    "contribution": round(float(value), 4),
                    "value": round(float(raw_by_name[name]), 4),
                }
                for name, value in zip(self.feature_names, contributions)
                if value > 0 and is_worth_naming(name, raw_by_name[name])
            ),
            key=lambda row: row["contribution"],
            reverse=True,
        )
        return ranked[:limit]
