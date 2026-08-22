# Running WebSentry

How to start every part of the system, in the right order.
Written so it can be followed step by step on the demo day.

---

## What you need installed

| Tool | Version used | Check with |
|---|---|---|
| Java (JDK) | 17 | `java -version` |
| Python | 3.12 | `python --version` |
| Node.js | 24 | `node --version` |
| Google Chrome or Microsoft Edge | any recent version | |

Maven is **not** needed separately. The backend folder contains `mvnw.cmd`, which downloads the
correct Maven version by itself the first time it runs.

If `java -version` does not work, set the two Windows settings below, then close and reopen the
terminal:

- `JAVA_HOME` = `C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`
- add `C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot\bin` to `Path`

---

## Step 1. Settings file

Copy `.env.example` to `.env` in the project root and fill in what you have.

**Everything is optional.** A blank key switches that feature off rather than breaking a scan, so
the system runs with an empty file. What each key changes:

| Key | If you leave it blank |
|---|---|
| `ANTHROPIC_API_KEY` | The built-in template writes the explanation instead of Claude. |
| `SAFE_BROWSING_API_KEY` | The known-threat lookup is skipped. |
| `MONGODB_URI` | Scans are kept in memory and are lost when the backend stops. |

On Windows the backend reads these as environment variables. The simplest way is to set them in the
same terminal before starting the backend:

```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."
$env:MONGODB_URI = "mongodb+srv://..."
```

---

## Step 2. Start the ML service (port 8000)

Open a terminal and run:

```
cd "D:\ICBT\Final Project\WebSentry\ml-service"
pip install -r requirements.txt
python -m uvicorn app:app --host 127.0.0.1 --port 8000
```

Wait until it prints `Application startup complete`.

Check it works by opening <http://127.0.0.1:8000/health> in a browser.
You should see `{"status":"ok","modelLoaded":true}`.

**Leave this terminal open.**

---

## Step 3. Start the backend (port 8080)

Open a **second** terminal and run:

```
cd "D:\ICBT\Final Project\WebSentry\backend"
.\mvnw.cmd spring-boot:run
```

The first run downloads Maven and the libraries, so it takes a few minutes. Later runs take a few
seconds.

Wait until it prints `Started BackendApplication`.

Check it works by opening <http://127.0.0.1:8080/api/v1/health>. This one call shows which parts are
switched on, for example:

```json
{
  "status": "ok",
  "scorer": "ml",
  "storage": "in-memory (development fallback, data is not saved)",
  "claudeExplanations": "template fallback",
  "safeBrowsing": "off",
  "mlServiceUrl": "http://127.0.0.1:8000"
}
```

**Leave this terminal open.**

---

## Step 4. Load the extension in Chrome

This part cannot be scripted, because Chrome does not allow the extension settings page to be
controlled automatically. Follow these steps by hand.

1. Open Chrome.
2. Click the **three dots** at the very top right of the window.
3. Choose **Extensions**, then **Manage Extensions**.
   (Or type `chrome://extensions` in the address bar and press Enter.)
4. Look at the **top right** of that page. Turn **Developer mode** ON. It is a small switch.
5. Three new buttons appear at the **top left**: *Load unpacked*, *Pack extension*, *Update*.
6. Click **Load unpacked**.
7. In the folder window, go to:
   `D:\ICBT\Final Project\WebSentry\extension`
8. Select that `extension` folder itself, then click **Select Folder**.
9. A card named **WebSentry** appears on the page. Make sure its switch, at the bottom right of the
   card, is ON.
10. Click the **puzzle piece icon** at the top right of Chrome, find **WebSentry** in the list, and
    click the **pin icon** next to it. The shield icon now stays visible in the toolbar.

### Using it

- Visit any website. After the page loads, the shield icon shows a **number badge**: the risk score.
  Green means safe, orange means caution, red means dangerous.
- Click the **shield icon** to open the report: the score, the recommendation, a plain-English
  explanation, the security checks, and what was found.
- Click **History** at the top right of the popup to see recently checked websites.

### If the badge shows `!`

That means the extension could not reach the backend. Check that the terminal from Step 3 is still
running, then click the shield icon and press **Try again**.

---

## Step 5. Start the dashboard (port 5173)

Open a **third** terminal and run:

```
cd "D:\ICBT\Final Project\WebSentry\dashboard"
npm install
npm run dev
```

Open <http://localhost:5173> in a browser.

Sign in with the username and password from `.env`. If you have not changed them, they are:

- Username: `admin`
- Password: `change-me-before-demo`

**Change the password before the demo.** Set `ADMIN_PASSWORD` in `.env`.

The dashboard has four tabs along the top: **Statistics**, **All scans**, **Flagged websites**
and **Model**.

---

## Step 6. Run the evaluation

The backend and the ML service must be running first (Steps 2 and 3).

```
cd "D:\ICBT\Final Project\WebSentry"
python evaluation/run_eval.py
```

It takes about 7 seconds and prints the confusion matrix, accuracy, precision, recall and F1.

It also writes three files into `evaluation/results/`:

- `evaluation_report.json` — every number, for the record
- `evaluation_results.md` — a table ready to paste into the results chapter
- `predictions.csv` — the verdict for each of the 400 URLs

The script checks the test set against the SHA-256 in its manifest first. If the file has been
changed it stops and refuses to run, because a changed test set would make the reported accuracy
mean nothing.

---

## Checking the popup without installing the extension

Useful for taking screenshots. The backend must be running.

```
cd "D:\ICBT\Final Project\WebSentry\extension"
python -m http.server 5500 --bind 127.0.0.1
```

Then open <http://127.0.0.1:5500/dev/preview.html>.

This page runs the real popup code against the real backend, with a small stand-in for the Chrome
extension APIs. It is a development aid only and is not part of the extension that ships.

---

## Stopping everything

Press `Ctrl` + `C` in each terminal.

---

## Quick troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| Backend says `APPLICATION FAILED TO START` and mentions port 8080 | Something else is using the port | Close the other program, or set `$env:BACKEND_PORT = "8081"` |
| Every website scores 0 and the report looks empty | The ML service is not running | Do Step 2 again |
| Dashboard shows "Could not load statistics" | The backend is not running | Do Step 3 again |
| Explanations all say "written by WebSentry" | No Anthropic key is set | Expected. Set `ANTHROPIC_API_KEY` to use Claude |
| Scans disappear after a restart | No MongoDB address is set | Expected. Set `MONGODB_URI` to keep them |
