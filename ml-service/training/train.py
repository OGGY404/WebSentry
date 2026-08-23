"""Train the WebSentry URL risk classifier."""

from __future__ import annotations

import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from scipy.sparse import csr_matrix, hstack
from sklearn.ensemble import RandomForestClassifier
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import StratifiedKFold, cross_val_score, train_test_split
from sklearn.preprocessing import MaxAbsScaler
from xgboost import XGBClassifier

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "ml-service"))

from features.url_features import FEATURE_NAMES, extract_vector, hostname_of  # noqa: E402

TRAIN_POOL = REPO_ROOT / "ml-service" / "training" / "train_pool.csv"
MODEL_DIR = REPO_ROOT / "ml-service" / "models"
RESULTS_DIR = REPO_ROOT / "evaluation" / "results"

RANDOM_SEED = 20360306
TEST_SIZE = 0.2
CV_FOLDS = 5
MODEL_VERSION = "1.0.0"

NGRAM_RANGE = (3, 5)
NGRAM_MAX_FEATURES = 60000


def evaluate(name: str, model, X_test, y_test) -> dict:
    predictions = model.predict(X_test)
    probabilities = model.predict_proba(X_test)[:, 1]
    tn, fp, fn, tp = confusion_matrix(y_test, predictions).ravel()
    metrics = {
        "model": name,
        "accuracy": float(accuracy_score(y_test, predictions)),
        "precision": float(precision_score(y_test, predictions)),
        "recall": float(recall_score(y_test, predictions)),
        "f1": float(f1_score(y_test, predictions)),
        "roc_auc": float(roc_auc_score(y_test, probabilities)),
        "confusion_matrix": {"tn": int(tn), "fp": int(fp), "fn": int(fn), "tp": int(tp)},
    }
    print(f"\n  {name}")
    print(f"    accuracy  {metrics['accuracy']:.4f}")
    print(f"    precision {metrics['precision']:.4f}")
    print(f"    recall    {metrics['recall']:.4f}")
    print(f"    f1        {metrics['f1']:.4f}")
    print(f"    roc auc   {metrics['roc_auc']:.4f}")
    print(f"    confusion TN={tn}  FP={fp}  FN={fn}  TP={tp}")
    return metrics


def main() -> int:
    if not TRAIN_POOL.exists():
        print(f"Training pool not found: {TRAIN_POOL}", file=sys.stderr)
        print("Run ml-service/training/build_eval_set.py first.", file=sys.stderr)
        return 2

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    print(f"Loading {TRAIN_POOL.name} ...")
    df = pd.read_csv(TRAIN_POOL)

    # Balance the two classes, so accuracy is meaningful.
    phishing = df[df.is_phishing == 1]
    legitimate = df[df.is_phishing == 0]
    n = min(len(phishing), len(legitimate))
    balanced = pd.concat(
        [
            phishing.sample(n=n, random_state=RANDOM_SEED),
            legitimate.sample(n=n, random_state=RANDOM_SEED),
        ]
    ).sample(frac=1.0, random_state=RANDOM_SEED)
    print(f"Balanced training corpus: {len(balanced):,} rows ({n:,} per class)")

    urls = balanced["url"].tolist()
    y = balanced["is_phishing"].to_numpy()

    print("Extracting lexical features ...")
    start = time.perf_counter()
    X_lex = np.array([extract_vector(u) for u in urls], dtype=np.float32)
    elapsed = time.perf_counter() - start
    print(f"  {len(urls):,} vectors in {elapsed:.1f}s ({len(urls)/elapsed:,.0f} URLs/sec)")

    # One split index reused for every model, so the comparison is like for like.
    idx_train, idx_test = train_test_split(
        np.arange(len(y)), test_size=TEST_SIZE, stratify=y, random_state=RANDOM_SEED
    )
    y_train, y_test = y[idx_train], y[idx_test]
    print(f"Split: {len(idx_train):,} train / {len(idx_test):,} internal test")

    results = {}

    print("\nTraining Random Forest baseline (lexical features) ...")
    rf = RandomForestClassifier(
        n_estimators=300, min_samples_leaf=2, n_jobs=-1, random_state=RANDOM_SEED
    )
    rf.fit(X_lex[idx_train], y_train)
    results["RandomForest"] = evaluate("RandomForest (lexical)", rf, X_lex[idx_test], y_test)

    print("\nTraining XGBoost challenger (lexical features) ...")
    xgb = XGBClassifier(
        n_estimators=400,
        max_depth=8,
        learning_rate=0.1,
        subsample=0.9,
        colsample_bytree=0.9,
        eval_metric="logloss",
        n_jobs=-1,
        random_state=RANDOM_SEED,
    )
    xgb.fit(X_lex[idx_train], y_train)
    results["XGBoost"] = evaluate("XGBoost (lexical)", xgb, X_lex[idx_test], y_test)

    print("\nTraining hybrid: char n-grams + lexical ...")
    hosts = [hostname_of(u) for u in urls]
    hosts_train = [hosts[i] for i in idx_train]
    hosts_test = [hosts[i] for i in idx_test]

    vectorizer = TfidfVectorizer(
        analyzer="char_wb",
        ngram_range=NGRAM_RANGE,
        min_df=3,
        max_features=NGRAM_MAX_FEATURES,
        sublinear_tf=True,
    )
    ngrams_train = vectorizer.fit_transform(hosts_train)
    ngrams_test = vectorizer.transform(hosts_test)
    print(f"  n-gram vocabulary: {len(vectorizer.vocabulary_):,} features")

    scaler = MaxAbsScaler().fit(csr_matrix(X_lex[idx_train]))
    X_hyb_train = hstack(
        [ngrams_train, scaler.transform(csr_matrix(X_lex[idx_train]))]
    ).tocsr()
    X_hyb_test = hstack(
        [ngrams_test, scaler.transform(csr_matrix(X_lex[idx_test]))]
    ).tocsr()

    hybrid = LogisticRegression(max_iter=2000, C=4.0, n_jobs=-1)
    hybrid.fit(X_hyb_train, y_train)
    results["Hybrid"] = evaluate("Hybrid (n-grams + lexical)", hybrid, X_hyb_test, y_test)

    # Choose on F1, which balances missed phishing against false alarms.
    winner_key = max(results, key=lambda k: results[k]["f1"])
    print(f"\nSelected model: {results[winner_key]['model']} (highest F1)")

    if winner_key == "Hybrid":
        winner, kind = hybrid, "hybrid"
        X_cv, cv_note = X_hyb_train, "hybrid features, training split only"
    else:
        winner = rf if winner_key == "RandomForest" else xgb
        kind = "lexical"
        X_cv, cv_note = X_lex[idx_train], "lexical features, training split only"

    print(f"\nRunning {CV_FOLDS}-fold stratified cross-validation ...")
    cv = StratifiedKFold(n_splits=CV_FOLDS, shuffle=True, random_state=RANDOM_SEED)
    cv_scores = cross_val_score(winner, X_cv, y_train, cv=cv, scoring="accuracy", n_jobs=-1)
    print(f"  fold accuracies: {', '.join(f'{s:.4f}' for s in cv_scores)}")
    print(f"  mean {cv_scores.mean():.4f}  std {cv_scores.std():.4f}")

    # Lexical feature ranking, reported for the methodology chapter.
    ranked = []
    if hasattr(winner, "feature_importances_"):
        pairs = zip(FEATURE_NAMES, winner.feature_importances_)
    elif hasattr(winner, "coef_"):
        offset = len(vectorizer.vocabulary_)
        pairs = zip(FEATURE_NAMES, winner.coef_[0][offset:])
    else:
        pairs = []
    ranked = sorted(
        ({"feature": f, "weight": float(w)} for f, w in pairs),
        key=lambda r: abs(r["weight"]),
        reverse=True,
    )
    if ranked:
        print("\n  Lexical features by absolute weight:")
        for row in ranked[:10]:
            print(f"    {row['feature']:<28} {row['weight']:+.4f}")

    metadata = {
        "model_version": MODEL_VERSION,
        "algorithm": results[winner_key]["model"],
        "kind": kind,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "random_seed": RANDOM_SEED,
        "training_rows": int(len(idx_train)),
        "internal_test_rows": int(len(idx_test)),
        "lexical_feature_count": len(FEATURE_NAMES),
        "lexical_feature_names": FEATURE_NAMES,
        "ngram_range": list(NGRAM_RANGE),
        "ngram_vocabulary": int(len(vectorizer.vocabulary_)),
        "model_comparison": results,
        "selected_metrics": results[winner_key],
        "cross_validation": {
            "folds": CV_FOLDS,
            "scope": cv_note,
            "fold_accuracies": [float(s) for s in cv_scores],
            "mean_accuracy": float(cv_scores.mean()),
            "std_accuracy": float(cv_scores.std()),
        },
        "lexical_feature_weights": ranked,
        "design_note": (
            "Hostname-only features. Path, query and scheme are deliberately "
            "excluded because in the PhiUSIIL corpus every legitimate URL is a "
            "bare HTTPS homepage, which lets a model reach 99.5 percent by "
            "learning the collection method rather than phishing. HTTPS and SSL "
            "validity are measured live by the backend rule engine instead."
        ),
        "note": (
            "Metrics here are from the internal train/test split and are NOT the "
            "reported project result. The headline figure comes from "
            "evaluation/run_eval.py on the frozen 400-URL held-out set."
        ),
    }

    model_path = MODEL_DIR / "risk_classifier.joblib"
    joblib.dump(
        {
            "model": winner,
            "vectorizer": vectorizer,
            "scaler": scaler,
            "kind": kind,
            "feature_names": FEATURE_NAMES,
            "metadata": metadata,
        },
        model_path,
    )
    (MODEL_DIR / "model_metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    (RESULTS_DIR / "training_report.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")

    print(f"\nSaved model:    {model_path}")
    print(f"Saved metadata: {MODEL_DIR / 'model_metadata.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
