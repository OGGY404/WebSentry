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
| Evaluation script | Working. `run_eval.py` reproduces the full metrics table. |
| Backend (Java, Spring Boot 3.5) | Working. Rule engine, scoring, explanations, storage, admin API. |
| Extension (Manifest V3) | Working. Popup, badge, history, page checks. |
| Dashboard (React, Vite) | Working. Statistics, scans, flagged sites, model metrics. |

See `docs/RUNNING.md` for how to start everything, including how to load the extension in Chrome.

## Headline result

Run on the frozen 400-URL held-out set through the complete pipeline. All 400 scanned, none failed.

| Definition of a phishing verdict | Accuracy | Precision | Recall | F1 | False positives |
|---|---|---|---|---|---|
| **HIGH band** (Leave Immediately) | **88.75%** | 98.74% | 78.50% | 87.47% | 2 of 200 (1.0%) |
| HIGH or MEDIUM (any warning) | 90.25% | 87.79% | 93.50% | 90.56% | 26 of 200 (13.0%) |

The 85% target is met under both readings.

Reproduce with `python evaluation/run_eval.py`. The script refuses to run if the test set no longer
matches the SHA-256 in its manifest, so a reported figure always refers to the same data.

### Speed

| What is being measured | Time |
|---|---|
| Score only (rules + model), no explanation | **13 ms** mean, 27 ms worst |
| Full scan including a fresh Claude explanation | about **1 to 2.2 seconds** |
| Repeat visit to the same site (explanation cached) | **under 20 ms** |

The score itself is far inside the 1 second target. The Claude call is what costs the time, so the
first visit to a new website takes a second or two and every visit after that is instant. The
evaluation run uses the template rather than the API, because the wording cannot change the band
being measured, and 400 API calls would add ten minutes and a bill for text nobody reads.

## Score convention

`riskScore` runs 0 to 100 where **0 is safe and 100 is dangerous**. Bands:

| Band | Score | Recommendation |
|---|---|---|
| LOW | 0-39 | Stay Safe |
| MEDIUM | 40-69 | Proceed with Caution |
| HIGH | 70-100 | Leave Immediately |

The Figma prototype in `docs/prototype/` used the opposite direction (87 = safe). The built
extension uses the convention above throughout.

## How a scan works

```
extension  ──POST /api/v1/scan──>  backend
                                     1. Google Safe Browsing lookup (optional)
                                     2. rule engine: HTTPS, certificate, mixed content, address
                                     3. RiskScorer -> ML service (hostname model), 0-100
                                     4. RiskCombiner: rules can only raise the score
                                     5. Explainer -> Claude, or template if it fails
                                     6. save the record, update the flagged summary
```

`RiskCombiner` is where the model score and the live rule findings are joined. The weights are
written in one place and documented there, because they affect the reported accuracy.

## Failure behaviour

Every external dependency is optional at runtime. This is deliberate: a demo must not fail because
a key was not approved or the internet dropped.

| If this is missing | What happens |
|---|---|
| `ANTHROPIC_API_KEY` | A built-in template writes the explanation. The scan still works. |
| `SAFE_BROWSING_API_KEY` | The threat lookup is skipped. Rules and the model still decide. |
| `MONGODB_URI` | Scans are kept in memory. The extension and dashboard still work. |
| ML service not running | The model contributes 0 and the rule checks decide alone. |

## Repository layout

```
websentry/
├── ml-service/          Python FastAPI + scikit-learn
│   ├── app.py           service endpoints
│   ├── scoring.py       model loading and inference, shared by training and serving
│   ├── features/        hostname feature extraction
│   ├── training/        prepare_data.py, train.py
│   └── models/          serialised .joblib artefacts
├── backend/             Spring Boot 3.5, Java 17
│   └── src/main/java/com/websentry/
│       ├── controller/  ScanController, AdminController, HealthController
│       ├── service/     RuleEngine, RiskScorer, RiskCombiner, Explainer, ScanService
│       ├── model/       ScanRecord, RuleChecks, RiskAssessment
│       ├── repository/  ScanStore with MongoDB and in-memory versions
│       └── config/      settings, CORS, storage selection
├── extension/           Manifest V3 browser extension
│   ├── background/      service worker: scans on navigation, badge, warnings
│   ├── content/         reads the live page for mixed content and login forms
│   ├── popup/           the security report the user sees
│   └── dev/             preview.html, a development page only
├── dashboard/           React + Vite admin UI
├── evaluation/
│   ├── datasets/        frozen eval set + manifest (raw/ is gitignored)
│   ├── run_eval.py      reproduces the metrics table
│   └── results/         evaluation report, predictions, training report
└── docs/
    ├── RUNNING.md       how to start everything
    ├── screenshots/     images for the implementation chapter
    └── prototype/       the original Figma Make export, kept as design reference
```

## Testing

19 backend tests, all passing (`cd backend && mvnw test`):

- `RuleEngineTest` — 12 tests covering HTTPS, IP hosts, brand impersonation, abused endings,
  punycode, the `@` trick, and that skipping live checks never falsely fails a certificate.
- `ClaudeExplainerTest` — 6 tests that the explanation never contradicts its own score.
- `BackendApplicationTests` — the application context starts.

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

These are the model on its own. The headline project figure at the top of this file comes from the
complete pipeline on the held-out 400-URL set.

## Reproducing the model

```
python ml-service/training/prepare_data.py     # build corpus, freeze eval set
python ml-service/training/train.py            # train, compare, persist
```

`prepare_data.py` refuses to overwrite a frozen evaluation set without `--force`. Do not pass
`--force` after results have been reported: it invalidates every accuracy figure.
