/**
 * WebSentry popup.
 *
 * The popup does not scan anything itself. The background worker has already
 * done the work and saved the answer, so this file only reads that answer and
 * puts it on the screen.
 */

const BAND_STYLE = {
  LOW: { colour: "#22c55e", soft: "rgba(34,197,94,0.12)", border: "rgba(34,197,94,0.3)" },
  MEDIUM: { colour: "#f59e0b", soft: "rgba(245,158,11,0.12)", border: "rgba(245,158,11,0.3)" },
  HIGH: { colour: "#ef4444", soft: "rgba(239,68,68,0.12)", border: "rgba(239,68,68,0.3)" }
};

/** Full length of the score ring: 2 * pi * 52. */
const RING_LENGTH = 326.7;

let currentTab = null;

// ---------------------------------------------------------------------------
// View switching
// ---------------------------------------------------------------------------

function showView(name) {
  for (const view of document.querySelectorAll(".view")) {
    view.classList.add("hidden");
  }
  document.getElementById(`view-${name}`).classList.remove("hidden");
}

function setActiveTab(name) {
  for (const tab of document.querySelectorAll(".tab")) {
    tab.classList.toggle("is-active", tab.dataset.view === name);
  }
}

// ---------------------------------------------------------------------------
// Drawing the report
// ---------------------------------------------------------------------------

function applyBandColours(band) {
  const style = BAND_STYLE[band] || BAND_STYLE.LOW;
  const root = document.documentElement.style;
  root.setProperty("--band", style.colour);
  root.setProperty("--band-soft", style.soft);
  root.setProperty("--band-border", style.border);
}

/** Builds one row in the security checks list. */
function checkRow(label, state, detail) {
  const item = document.createElement("li");

  const left = document.createElement("div");
  left.className = "check-label";

  const mark = document.createElement("span");
  mark.className = `check-mark ${state}`;
  mark.textContent = state === "pass" ? "✓" : state === "fail" ? "!" : "?";

  const text = document.createElement("span");
  text.textContent = label;

  left.append(mark, text);

  const right = document.createElement("span");
  right.className = "check-detail";
  right.textContent = detail;

  item.append(left, right);
  return item;
}

function renderChecks(record) {
  const checks = record.checks || {};
  const list = document.getElementById("check-list");
  list.textContent = "";

  list.append(
    checkRow(
      "Encrypted connection",
      checks.https ? "pass" : "fail",
      checks.https ? "Yes, uses HTTPS" : "No, uses plain HTTP"
    )
  );

  if (checks.https) {
    let certDetail = "Could not be read";
    let certState = "unknown";
    if (checks.sslValid) {
      certState = "pass";
      certDetail = checks.sslIssuer ? `Valid, from ${checks.sslIssuer}` : "Valid";
      if (typeof checks.sslDaysToExpiry === "number") {
        certDetail += `, ${checks.sslDaysToExpiry} days left`;
      }
    } else {
      certState = "fail";
      certDetail = "Not trusted";
    }
    list.append(checkRow("Security certificate", certState, certDetail));

    list.append(
      checkRow(
        "All files encrypted",
        checks.mixedContent ? "fail" : "pass",
        checks.mixedContent ? "Some files use plain HTTP" : "Yes"
      )
    );
  }

  list.append(
    checkRow(
      "Known threat lists",
      checks.inPhishingDb ? "fail" : "pass",
      checks.inPhishingDb ? "Listed as dangerous" : "Not listed"
    )
  );

  list.append(
    checkRow(
      "Web address pattern",
      checks.suspiciousPattern ? "fail" : "pass",
      checks.suspiciousPattern ? "Looks suspicious" : "Looks normal"
    )
  );
}

function renderReasons(record) {
  const block = document.getElementById("reasons-block");
  const list = document.getElementById("reason-list");
  const reasons = (record.checks && record.checks.reasons) || [];

  list.textContent = "";
  if (reasons.length === 0) {
    block.classList.add("hidden");
    return;
  }

  for (const reason of reasons) {
    const item = document.createElement("li");
    item.textContent = reason;
    list.append(item);
  }
  block.classList.remove("hidden");
}

function renderReport(record) {
  applyBandColours(record.riskBand);

  document.getElementById("score-number").textContent = record.riskScore;
  document.getElementById("recommendation-text").textContent = record.recommendation;
  document.getElementById("domain-text").textContent = record.domain || record.url;
  document.getElementById("explanation-text").textContent = record.aiExplanation || "";

  // Fill the ring in proportion to the risk score.
  const filled = RING_LENGTH - (record.riskScore / 100) * RING_LENGTH;
  document.getElementById("dial-value").style.strokeDashoffset = String(filled);

  document.getElementById("source-note").textContent = record.explanationFromAi
    ? "Explanation written by Claude."
    : "Explanation written by WebSentry.";

  document.getElementById("timing-text").textContent =
    typeof record.scanDurationMs === "number" ? `Checked in ${record.scanDurationMs} ms` : "";

  renderChecks(record);
  renderReasons(record);

  showView("report");
}

// ---------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------

function formatWhen(isoString) {
  const when = new Date(isoString);
  if (Number.isNaN(when.getTime())) {
    return "";
  }
  const minutesAgo = Math.round((Date.now() - when.getTime()) / 60000);
  if (minutesAgo < 1) {
    return "just now";
  }
  if (minutesAgo < 60) {
    return `${minutesAgo} min ago`;
  }
  const hoursAgo = Math.round(minutesAgo / 60);
  if (hoursAgo < 24) {
    return `${hoursAgo} h ago`;
  }
  return when.toLocaleDateString();
}

async function renderHistory() {
  const stored = await chrome.storage.local.get("history");
  const history = stored.history || [];

  const list = document.getElementById("history-list");
  const empty = document.getElementById("history-empty");
  list.textContent = "";

  if (history.length === 0) {
    empty.classList.remove("hidden");
  } else {
    empty.classList.add("hidden");
  }

  for (const entry of history) {
    const style = BAND_STYLE[entry.riskBand] || BAND_STYLE.LOW;

    const item = document.createElement("li");

    const left = document.createElement("div");
    const domain = document.createElement("div");
    domain.className = "history-domain";
    domain.textContent = entry.domain || entry.url;
    const time = document.createElement("div");
    time.className = "history-time";
    time.textContent = `${entry.recommendation} · ${formatWhen(entry.timestamp)}`;
    left.append(domain, time);

    const score = document.createElement("span");
    score.className = "history-score";
    score.textContent = entry.riskScore;
    score.style.background = style.soft;
    score.style.color = style.colour;

    item.append(left, score);
    list.append(item);
  }

  showView("history");
}

// ---------------------------------------------------------------------------
// Loading the result for the tab that is open
// ---------------------------------------------------------------------------

function isScannable(url) {
  return typeof url === "string" && (url.startsWith("http://") || url.startsWith("https://"));
}

async function loadCurrentTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  currentTab = tab;

  if (!tab || !isScannable(tab.url)) {
    showView("unsupported");
    return;
  }

  showView("loading");
  const result = await chrome.runtime.sendMessage({ type: "GET_RESULT", tabId: tab.id });

  if (!result) {
    // The worker has not finished yet. Ask it to scan now.
    const fresh = await chrome.runtime.sendMessage({
      type: "RESCAN",
      tabId: tab.id,
      url: tab.url
    });
    handleResult(fresh);
    return;
  }

  handleResult(result);
}

function handleResult(result) {
  if (!result) {
    showError("WebSentry did not get an answer. Please try again.");
    return;
  }
  if (result.status === "ok") {
    renderReport(result.record);
    return;
  }
  if (result.status === "scanning") {
    showView("loading");
    // Look again shortly: the scan is still running.
    setTimeout(loadCurrentTab, 900);
    return;
  }
  showError(result.message);
}

function showError(message) {
  document.getElementById("error-message").textContent =
    message || "WebSentry could not reach its backend.";
  showView("error");
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

document.addEventListener("DOMContentLoaded", () => {
  loadCurrentTab();

  for (const tab of document.querySelectorAll(".tab")) {
    tab.addEventListener("click", () => {
      setActiveTab(tab.dataset.view);
      if (tab.dataset.view === "history") {
        renderHistory();
      } else {
        loadCurrentTab();
      }
    });
  }

  document.getElementById("retry-button").addEventListener("click", () => {
    if (currentTab) {
      showView("loading");
      chrome.runtime
        .sendMessage({ type: "RESCAN", tabId: currentTab.id, url: currentTab.url, force: true })
        .then(handleResult);
    }
  });

  document.getElementById("rescan-button").addEventListener("click", () => {
    if (currentTab) {
      showView("loading");
      chrome.runtime
        .sendMessage({ type: "RESCAN", tabId: currentTab.id, url: currentTab.url, force: true })
        .then(handleResult);
    }
  });

  document.getElementById("clear-history-button").addEventListener("click", async () => {
    await chrome.storage.local.set({ history: [] });
    renderHistory();
  });
});
