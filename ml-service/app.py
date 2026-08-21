"""
WebSentry ML service.

Internal FastAPI service that turns a URL into a 0-100 risk score. Called by
the Spring Boot backend's MlRiskScorer; not exposed to the browser extension
directly.

Run:
    cd ml-service
    uvicorn app:app --host 127.0.0.1 --port 8000

Contract (CLAUDE.md section 8):
    POST /predict      -> riskScore, confidence, topFeatures
    GET  /model/info   -> model version, training date, metrics
    GET  /health       -> liveness
"""

from __future__ import annotations

import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from features.url_features import FEATURE_NAMES, extract_features
from scoring import BAND_LOW_MAX, BAND_MEDIUM_MAX, MODEL_PATH, RiskModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("websentry.ml")

# Populated at startup so the model is loaded once, not per request.
state: dict[str, RiskModel | None] = {"model": None}


@asynccontextmanager
async def lifespan(_: FastAPI):
    if not MODEL_PATH.exists():
        logger.error("Model artefact missing at %s", MODEL_PATH)
        logger.error("Run: python ml-service/training/train.py")
    else:
        started = time.perf_counter()
        state["model"] = RiskModel()
        meta = state["model"].metadata
        logger.info(
            "Loaded %s v%s in %.2fs",
            meta.get("algorithm", "model"),
            meta.get("model_version", "?"),
            time.perf_counter() - started,
        )
    yield
    state["model"] = None


app = FastAPI(
    title="WebSentry ML Service",
    description="URL risk scoring for the WebSentry browser extension.",
    version="1.0.0",
    lifespan=lifespan,
)


class PredictRequest(BaseModel):
    url: str = Field(..., min_length=1, max_length=4096, examples=["http://paypa1-verify.tk/login"])


class PredictBatchRequest(BaseModel):
    urls: list[str] = Field(..., min_length=1, max_length=500)


def _require_model() -> RiskModel:
    model = state["model"]
    if model is None:
        raise HTTPException(
            status_code=503,
            detail="Model not loaded. Run ml-service/training/train.py first.",
        )
    return model


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "modelLoaded": state["model"] is not None}


@app.post("/predict")
def predict(request: PredictRequest) -> dict:
    """Score a single URL. Latency is logged so the figures can feed the
    performance section of the evaluation chapter."""
    model = _require_model()
    started = time.perf_counter()
    result = model.score(request.url)
    elapsed_ms = (time.perf_counter() - started) * 1000
    result["scoreDurationMs"] = round(elapsed_ms, 2)
    logger.info("scored %s -> %s (%s) in %.1fms",
                request.url[:80], result["riskScore"], result["riskBand"], elapsed_ms)
    return result


@app.post("/predict/batch")
def predict_batch(request: PredictBatchRequest) -> dict:
    """Batch scoring, used by evaluation/run_eval.py so the 400-URL run does
    not pay per-request overhead 400 times."""
    model = _require_model()
    started = time.perf_counter()
    results = [model.score(url) | {"url": url} for url in request.urls]
    return {
        "count": len(results),
        "totalDurationMs": round((time.perf_counter() - started) * 1000, 2),
        "results": results,
    }


@app.post("/features")
def features(request: PredictRequest) -> dict:
    """Expose the raw feature dictionary. Used for the feature engineering
    discussion in the report and for debugging odd scores."""
    return {"url": request.url, "features": extract_features(request.url)}


@app.get("/model/info")
def model_info() -> dict:
    model = _require_model()
    meta = model.metadata
    return {
        "modelVersion": meta.get("model_version"),
        "algorithm": meta.get("algorithm"),
        "trainedAt": meta.get("trained_at"),
        "trainingRows": meta.get("training_rows"),
        "lexicalFeatureCount": len(FEATURE_NAMES),
        "ngramVocabulary": meta.get("ngram_vocabulary"),
        "metrics": meta.get("selected_metrics"),
        "crossValidation": meta.get("cross_validation"),
        "modelComparison": meta.get("model_comparison"),
        "bands": {
            "LOW": f"0-{BAND_LOW_MAX}",
            "MEDIUM": f"{BAND_LOW_MAX + 1}-{BAND_MEDIUM_MAX}",
            "HIGH": f"{BAND_MEDIUM_MAX + 1}-100",
        },
        "designNote": meta.get("design_note"),
    }
