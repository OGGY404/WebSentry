# WebSentry Evaluation Results

Run at 2026-08-28T12:25:28.754236+00:00.
Test set: 400 URLs (200 phishing, 200 legitimate).
Model version 1.0.0, scoring engine `ml`.

Live website checks were switched off so the run is repeatable. See the note in `run_eval.py` for why.

## Confusion matrix (HIGH band counted as a phishing verdict)

| | Predicted phishing | Predicted safe |
|---|---|---|
| **Actually phishing** | 157 (TP) | 43 (FN) |
| **Actually safe** | 2 (FP) | 198 (TN) |

## Metrics

| Measure | Value |
|---|---|
| Accuracy | 88.75% |
| Precision | 98.74% |
| Recall | 78.50% |
| F1 score | 87.47% |
| False positive rate | 1.00% |

Target of 85% accuracy: **met**.

## Secondary reading: any warning counted as a phishing verdict

Here both HIGH (Leave Immediately) and MEDIUM (Proceed with Caution) are treated as the system flagging the site. This catches more phishing but warns about more safe websites.

| Measure | Value |
|---|---|
| Accuracy | 90.25% |
| Precision | 87.79% |
| Recall | 93.50% |
| F1 score | 90.56% |
| False positive rate | 13.00% |

## Speed

Mean scan time 14.12 ms, median 14 ms, slowest 31 ms.
