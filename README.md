# WebSentry

An AI-Powered Web Security Awareness Browser Extension.
Final Year Project, BSc (Hons) Software Engineering, Cardiff Metropolitan University / ICBT Campus.

Architecture: **Version B**. A locally trained classifier produces the 0-100 risk score from the
hostname; the Claude API is used only to write the plain-language explanation. The scoring engine
sits behind a `RiskScorer` interface so Version A remains a config change.

## Status

| Component | State |
|---|---|
| ML service (Python, FastAPI) | Working. Model trained, `/predict` live. |
| Evaluation set | Frozen, 200 phishing / 200 legitimate, SHA-256 in manifest. |
| Backend (Java, Spring Boot) | Not started. Requires JDK 17 and Maven. |
| Extension (Manifest V3) | Not started. |
| Dashboard (React, Vite) | Not started. |

## Score convention

`riskScore` runs 0 to 100 where **0 is safe and 100 is dangerous**. Bands:

| Band | Score | Recommendation |
|---|---|---|
| LOW | 0-39 | Stay Safe |
| MEDIUM | 40-69 | Proceed with Caution |
| HIGH | 70-100 | Leave Immediately |

Note the two Figma prototypes in the parent folder use the opposite direction (87 = safe). They
are visual reference only and need updating to match this convention before the demo.

## Running the ML service

```
cd ml-service
pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8000
```

| Method | Path | Purpose |
|---|---|---|
| POST | `/predict` | Score one URL. Returns riskScore, band, recommendation, confidence, topFeatures. |
| POST | `/predict/batch` | Score up to 500 URLs, used by the evaluation run. |
| POST | `/features` | Raw feature dictionary, for the report and for debugging. |
| GET | `/model/info` | Version, training date, metrics, model comparison. |
| GET | `/health` | Liveness. |

## Reproducing the model

```
python ml-service/training/prepare_data.py     # build corpus, freeze eval set
python ml-service/training/train.py            # train, compare, persist
```

`prepare_data.py` refuses to overwrite a frozen evaluation set without `--force`. Do not pass
`--force` after results have been reported: it invalidates every accuracy figure.

## Data sources

- **PhiUSIIL Phishing URL Dataset**, UCI ML Repository ID 967. 235,795 labelled URLs.
  Prasad, A. and Chandra, S. (2024) *Computers and Security*, 136, 103545.
- **Cisco Umbrella Popularity List**, top 1 million domains, used for the legitimate class and
  for removing label conflicts.

PhishTank now requires a registered API key and returns 403 anonymously; OpenPhish and Tranco were
unreachable. The feasibility study subsection A.6.2 and Chapter 1 objective 5 still name
PhishTank, OpenPhish and Tranco and **must be updated** to match what was actually used.

## Two dataset artefacts that were found and corrected

Both are worth writing up in the methodology chapter, because both inflate accuracy in ways that
are invisible unless specifically checked.

**1. Path and scheme leakage.** In PhiUSIIL, 100% of legitimate URLs are bare HTTPS homepages with
an empty path, while phishing samples are full URLs. A model given `path_length` and `is_https`
reaches 99.5% accuracy by learning how the corpus was assembled, and classifies
`https://google.com/search?q=test` as phishing. Fixed by restricting the model to the hostname.
HTTPS and SSL validity are measured live by the backend rule engine instead, which is where those
signals belong.

**2. Phishing hosted on legitimate infrastructure.** PhiUSIIL labels credential-harvesting pages
on `docs.google.com`, `s3.amazonaws.com` and similar as phishing. The full URL is malicious but
the hostname is not, so a hostname-only model learns that Google and Amazon are dangerous.
Observed directly: `amazon.co.uk` scored 91 (Leave Immediately). Fixed by removing phishing rows
whose *full hostname* is a top-ranked legitimate domain, and by adding 99,771 top-ranked domains to
the legitimate class. Matching on the full hostname rather than the registrable domain keeps
genuinely detectable cases such as `fkdygcrzga.duckdns.org` in the training data.

Detection of phishing hosted on legitimate platforms is delegated to Google Safe Browsing at scan
time and is a documented limitation of the model.

## Model results (internal test split, not the reported project figure)

| Model | Accuracy | Precision | Recall | F1 | ROC AUC |
|---|---|---|---|---|---|
| Random Forest (lexical) | 0.8089 | 0.8718 | 0.7245 | 0.7913 | 0.8833 |
| XGBoost (lexical) | 0.8100 | 0.8825 | 0.7151 | 0.7900 | 0.8854 |
| **Hybrid (char n-grams + lexical)** | **0.8544** | 0.8824 | 0.8177 | **0.8488** | 0.9346 |

5-fold stratified cross-validation on the hybrid: mean accuracy 0.8543, standard deviation 0.0022.
Mean scoring latency 7.2 ms per URL.

The headline project figure comes from `evaluation/run_eval.py` on the frozen 400-URL held-out set,
which has not been run yet. These numbers are from the internal split and must not be quoted as the
evaluation result.

## Repository layout

```
websentry/
├── ml-service/          Python FastAPI + scikit-learn
│   ├── app.py           service endpoints
│   ├── scoring.py       model loading and inference, shared by training and serving
│   ├── features/        hostname feature extraction
│   ├── training/        prepare_data.py, train.py
│   └── models/          serialised .joblib artefacts
├── evaluation/
│   ├── datasets/        frozen eval set + manifest (raw/ is gitignored)
│   └── results/         training report
├── backend/             Spring Boot (not started)
├── extension/           Manifest V3 (not started)
└── dashboard/           React + Vite (not started)
```
