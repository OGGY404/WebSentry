"""WebSentry evaluation."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests

BASE_DIR = Path(__file__).resolve().parent
EVAL_CSV = BASE_DIR / "datasets" / "eval_set_400.csv"
MANIFEST = BASE_DIR / "datasets" / "eval_set_400.manifest.json"
RESULTS_DIR = BASE_DIR / "results"

DEFAULT_BACKEND = "http://127.0.0.1:8080"

# A site counts as detected when it lands in the HIGH band.
PRIMARY_POSITIVE_BANDS = {"HIGH"}
SECONDARY_POSITIVE_BANDS = {"HIGH", "MEDIUM"}


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_dataset() -> dict:
    """Stops the run if the frozen test set has been changed."""
    if not EVAL_CSV.exists():
        sys.exit(f"Test set not found: {EVAL_CSV}")
    if not MANIFEST.exists():
        sys.exit(f"Manifest not found: {MANIFEST}")

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    expected = manifest.get("eval_sha256")
    actual = sha256_of(EVAL_CSV)

    if expected != actual:
        sys.exit(
            "The evaluation set does not match its manifest.\n"
            f"  expected {expected}\n"
            f"  found    {actual}\n"
            "Every published accuracy figure refers to the original file. "
            "Restore it before running the evaluation again."
        )

    print(f"Test set verified against manifest (SHA-256 {actual[:16]}...)")
    return manifest


def load_rows() -> list[dict]:
    with EVAL_CSV.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    for row in rows:
        row["is_phishing"] = int(row["is_phishing"])
    return rows


def scan(session: requests.Session, backend: str, url: str, timeout: float) -> dict | None:
    """Sends one URL through the backend."""
    try:
        response = session.post(
            f"{backend}/api/v1/scan",
            params={"live": "false"},
            json={"url": url, "clientId": "evaluation-run"},
            timeout=timeout,
        )
        response.raise_for_status()
        return response.json()
    except Exception as error:  # noqa: BLE001 - one bad URL must not stop the run
        print(f"  ! scan failed for {url[:70]}: {error}")
        return None


def confusion(results: list[dict], positive_bands: set[str]) -> dict:
    """Counts true and false positives and negatives."""
    tp = fp = tn = fn = 0
    for row in results:
        predicted_phishing = row["riskBand"] in positive_bands
        actually_phishing = row["is_phishing"] == 1

        if predicted_phishing and actually_phishing:
            tp += 1
        elif predicted_phishing and not actually_phishing:
            fp += 1
        elif not predicted_phishing and actually_phishing:
            fn += 1
        else:
            tn += 1
    return {"tp": tp, "fp": fp, "tn": tn, "fn": fn}


def metrics_from(matrix: dict) -> dict:
    tp, fp, tn, fn = matrix["tp"], matrix["fp"], matrix["tn"], matrix["fn"]
    total = tp + fp + tn + fn

    accuracy = (tp + tn) / total if total else 0.0
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0

    return {
        "confusion_matrix": matrix,
        "total": total,
        "accuracy": round(accuracy, 4),
        "precision": round(precision, 4),
        "recall": round(recall, 4),
        "f1": round(f1, 4),
        # False alarms matter: too many warnings and people switch the tool off.
        "false_positive_rate": round(fp / (fp + tn), 4) if (fp + tn) else 0.0,
    }


def print_table(title: str, result: dict, target: float = 0.85) -> None:
    matrix = result["confusion_matrix"]
    print()
    print(title)
    print("-" * len(title))
    print("                    predicted phishing   predicted safe")
    print(f"  actually phishing        {matrix['tp']:>5}            {matrix['fn']:>5}")
    print(f"  actually safe            {matrix['fp']:>5}            {matrix['tn']:>5}")
    print()
    print(f"  Accuracy             {result['accuracy'] * 100:6.2f} %")
    print(f"  Precision            {result['precision'] * 100:6.2f} %")
    print(f"  Recall               {result['recall'] * 100:6.2f} %")
    print(f"  F1 score             {result['f1'] * 100:6.2f} %")
    print(f"  False positive rate  {result['false_positive_rate'] * 100:6.2f} %")
    print()
    verdict = "MET" if result["accuracy"] >= target else "NOT MET"
    print(f"  Target of {target * 100:.0f}% accuracy: {verdict}")


def write_markdown(report: dict, path: Path) -> None:
    """Writes a table that can be pasted straight into the results chapter."""
    primary = report["primary"]
    secondary = report["secondary"]
    matrix = primary["confusion_matrix"]

    lines = [
        "# WebSentry Evaluation Results",
        "",
        f"Run at {report['run_at']}.",
        f"Test set: {report['dataset']['rows']} URLs "
        f"({report['dataset']['phishing']} phishing, {report['dataset']['legitimate']} legitimate).",
        f"Model version {report['model_version']}, scoring engine `{report['scorer']}`.",
        "",
        "Live website checks were switched off so the run is repeatable. "
        "See the note in `run_eval.py` for why.",
        "",
        "## Confusion matrix (HIGH band counted as a phishing verdict)",
        "",
        "| | Predicted phishing | Predicted safe |",
        "|---|---|---|",
        f"| **Actually phishing** | {matrix['tp']} (TP) | {matrix['fn']} (FN) |",
        f"| **Actually safe** | {matrix['fp']} (FP) | {matrix['tn']} (TN) |",
        "",
        "## Metrics",
        "",
        "| Measure | Value |",
        "|---|---|",
        f"| Accuracy | {primary['accuracy'] * 100:.2f}% |",
        f"| Precision | {primary['precision'] * 100:.2f}% |",
        f"| Recall | {primary['recall'] * 100:.2f}% |",
        f"| F1 score | {primary['f1'] * 100:.2f}% |",
        f"| False positive rate | {primary['false_positive_rate'] * 100:.2f}% |",
        "",
        f"Target of 85% accuracy: **{'met' if primary['accuracy'] >= 0.85 else 'not met'}**.",
        "",
        "## Secondary reading: any warning counted as a phishing verdict",
        "",
        "Here both HIGH (Leave Immediately) and MEDIUM (Proceed with Caution) are "
        "treated as the system flagging the site. This catches more phishing but "
        "warns about more safe websites.",
        "",
        "| Measure | Value |",
        "|---|---|",
        f"| Accuracy | {secondary['accuracy'] * 100:.2f}% |",
        f"| Precision | {secondary['precision'] * 100:.2f}% |",
        f"| Recall | {secondary['recall'] * 100:.2f}% |",
        f"| F1 score | {secondary['f1'] * 100:.2f}% |",
        f"| False positive rate | {secondary['false_positive_rate'] * 100:.2f}% |",
        "",
        "## Speed",
        "",
        f"Mean scan time {report['timing']['mean_scan_ms']} ms, "
        f"median {report['timing']['median_scan_ms']} ms, "
        f"slowest {report['timing']['max_scan_ms']} ms.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the WebSentry evaluation.")
    parser.add_argument("--backend", default=DEFAULT_BACKEND, help="Backend base URL")
    parser.add_argument("--timeout", type=float, default=30.0, help="Per-request timeout in seconds")
    parser.add_argument("--limit", type=int, default=0, help="Only run the first N URLs (for a quick check)")
    args = parser.parse_args()

    manifest = verify_dataset()
    rows = load_rows()
    if args.limit:
        rows = rows[: args.limit]

    session = requests.Session()

    # Check the backend is up before starting a long run.
    try:
        health = session.get(f"{args.backend}/api/v1/health", timeout=10).json()
        print(f"Backend ready. Scoring engine: {health.get('scorer')}, storage: {health.get('storage')}")
    except Exception as error:  # noqa: BLE001
        sys.exit(f"Could not reach the backend at {args.backend}: {error}\n"
                 "Start it with: cd backend && mvnw spring-boot:run")

    print(f"Scanning {len(rows)} URLs...")
    started = time.perf_counter()

    results: list[dict] = []
    failures = 0
    model_version = "unknown"

    for index, row in enumerate(rows, start=1):
        record = scan(session, args.backend, row["url"], args.timeout)
        if record is None:
            failures += 1
            continue

        model_version = record.get("modelVersion", model_version)
        results.append(
            {
                "url": row["url"],
                "is_phishing": row["is_phishing"],
                "riskScore": record.get("riskScore", 0),
                "riskBand": record.get("riskBand", "LOW"),
                "recommendation": record.get("recommendation", ""),
                "scanDurationMs": record.get("scanDurationMs", 0),
            }
        )

        if index % 50 == 0:
            print(f"  {index}/{len(rows)} scanned")

    elapsed = time.perf_counter() - started
    print(f"Finished in {elapsed:.1f} s. {len(results)} scanned, {failures} failed.")

    if not results:
        sys.exit("No URLs were scanned successfully, so no metrics can be produced.")

    if failures:
        # Never hide dropped rows: a silently smaller test set inflates confidence.
        print(f"WARNING: {failures} URLs could not be scanned and are excluded from the metrics.")

    primary = metrics_from(confusion(results, PRIMARY_POSITIVE_BANDS))
    secondary = metrics_from(confusion(results, SECONDARY_POSITIVE_BANDS))

    durations = sorted(row["scanDurationMs"] for row in results)
    timing = {
        "mean_scan_ms": round(sum(durations) / len(durations), 2),
        "median_scan_ms": durations[len(durations) // 2],
        "max_scan_ms": durations[-1],
    }

    report = {
        "run_at": datetime.now(timezone.utc).isoformat(),
        "backend": args.backend,
        "live_checks": False,
        "scorer": health.get("scorer", "unknown"),
        "model_version": model_version,
        "dataset": {
            "file": EVAL_CSV.name,
            "sha256": manifest.get("eval_sha256"),
            "rows": len(results),
            "phishing": sum(1 for row in results if row["is_phishing"] == 1),
            "legitimate": sum(1 for row in results if row["is_phishing"] == 0),
            "failed_scans": failures,
        },
        "primary": primary,
        "secondary": secondary,
        "timing": timing,
        "band_definition": {
            "primary": "HIGH counted as a phishing verdict",
            "secondary": "HIGH or MEDIUM counted as a phishing verdict",
        },
    }

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    (RESULTS_DIR / "evaluation_report.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    (RESULTS_DIR / "predictions.csv").write_text(
        "url,is_phishing,riskScore,riskBand,recommendation\n"
        + "\n".join(
            f"\"{row['url']}\",{row['is_phishing']},{row['riskScore']},{row['riskBand']},{row['recommendation']}"
            for row in results
        ),
        encoding="utf-8",
    )
    write_markdown(report, RESULTS_DIR / "evaluation_results.md")

    print_table("PRIMARY: HIGH band counted as a phishing verdict", primary)
    print_table("SECONDARY: HIGH or MEDIUM counted as a phishing verdict", secondary)

    print()
    print(f"Mean scan {timing['mean_scan_ms']} ms, median {timing['median_scan_ms']} ms, "
          f"slowest {timing['max_scan_ms']} ms")
    print()
    print(f"Written to {RESULTS_DIR}")


if __name__ == "__main__":
    main()
