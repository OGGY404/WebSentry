/** WebSentry popup. */

const BAND_STYLE = {
  LOW: { colour: "#22c55e", soft: "rgba(34,197,94,0.12)", border: "rgba(34,197,94,0.3)" },
  MEDIUM: { colour: "#f59e0b", soft: "rgba(245,158,11,0.12)", border: "rgba(245,158,11,0.3)" },
  HIGH: { colour: "#ef4444", soft: "rgba(239,68,68,0.12)", border: "rgba(239,68,68,0.3)" }
};

/** Full length of the score ring: 2 * pi * 52. */
const RING_LENGTH = 326.7;

let currentTab = null;

// View switching

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

// Drawing the report

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

// --------------------------------------------------------------------------- History.

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

// Settings

const DEFAULT_BACKEND = "http://127.0.0.1:8080";

/** Reads the saved settings and puts them on the screen. */
async function renderSettings() {
  const stored = await chrome.storage.local.get(["backendUrl", "enabled", "clientId"]);

  document.getElementById("setting-enabled").checked = stored.enabled !== false;
  document.getElementById("setting-backend").value = stored.backendUrl || DEFAULT_BACKEND;
  document.getElementById("setting-client").textContent = stored.clientId || "not made yet";
  document.getElementById("test-result").textContent = "";
  document.getElementById("save-result").textContent = "";

  showView("settings");
}

/** Saves what the user typed. */
async function saveSettings() {
  const typed = document.getElementById("setting-backend").value.trim();
  const message = document.getElementById("save-result");

  if (typed && !typed.startsWith("http://") && !typed.startsWith("https://")) {
    message.textContent = "The address must start with http:// or https://";
    return;
  }

  await chrome.storage.local.set({
    enabled: document.getElementById("setting-enabled").checked,
    backendUrl: typed || DEFAULT_BACKEND
  });

  message.textContent = "Saved. The next website you visit will use these settings.";
}

/** Asks the backend whether it is there, and reports what it says. */
async function testConnection() {
  const address = document.getElementById("setting-backend").value.trim() || DEFAULT_BACKEND;
  const result = document.getElementById("test-result");

  result.className = "test-result";
  result.textContent = "Checking...";

  const answer = await chrome.runtime.sendMessage({ type: "TEST_CONNECTION", backendUrl: address });

  if (answer && answer.ok) {
    result.className = "test-result good";
    result.textContent =
      `Connected. Storage: ${answer.storage}. Explanations: ${answer.explanations}.`;
  } else {
    result.className = "test-result bad";
    result.textContent = (answer && answer.message) || "Could not reach that address.";
  }
}

// Loading the result for the tab that is open

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
    // The worker has not finished yet.
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
    showError("The background worker did not answer. It may have been restarted.");
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
  showError(result.message, result.url);
}

/** Picks a short piece of advice from what actually failed. */
function hintFor(detail) {
  const text = (detail || "").toLowerCase();
  if (text.includes("could not reach")) {
    return "The backend is probably not running. Start it, then press Try again.";
  }
  if (text.includes("500") || text.includes("503")) {
    return "The backend is running but something inside it failed. Look at the backend terminal.";
  }
  if (text.includes("401") || text.includes("403")) {
    return "The backend refused the request. Check the settings in the .env file.";
  }
  if (text.includes("risk score") || text.includes("not a scan result")) {
    return "The backend answered, but not with a scan result. Check that it is the WebSentry backend.";
  }
  return "Press Try again. If it keeps failing, open the service worker console on chrome://extensions.";
}

async function showError(detail, url) {
  document.getElementById("error-message").textContent =
    "WebSentry could not check this website.";
  document.getElementById("error-detail").textContent =
    detail || "No reason was recorded.";
  document.getElementById("error-hint").textContent = hintFor(detail);
  document.getElementById("diag-url").textContent = url || (currentTab && currentTab.url) || "unknown";
  document.getElementById("diag-time").textContent = new Date().toLocaleTimeString();

  // Ask the worker which address it is actually using.
  try {
    const info = await chrome.runtime.sendMessage({ type: "GET_DIAGNOSTICS" });
    document.getElementById("diag-backend").textContent =
      (info && info.backendUrl) || "unknown";
  } catch (problem) {
    document.getElementById("diag-backend").textContent = "could not ask the worker";
  }

  showView("error");
}

// --------------------------------------------------------------------------- Buttons.

document.addEventListener("DOMContentLoaded", () => {
  loadCurrentTab();

  for (const tab of document.querySelectorAll(".tab")) {
    tab.addEventListener("click", () => {
      setActiveTab(tab.dataset.view);
      if (tab.dataset.view === "history") {
        renderHistory();
      } else if (tab.dataset.view === "settings") {
        renderSettings();
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

  document.getElementById("save-button").addEventListener("click", saveSettings);
  document.getElementById("test-button").addEventListener("click", testConnection);

  document.getElementById("dashboard-button").addEventListener("click", async () => {
    // The identifier is passed so the dashboard can open on this computer's
    // own websites. It is a random value made here, not an account.
    const stored = await chrome.storage.local.get(["clientId", "dashboardUrl"]);
    const dashboard = stored.dashboardUrl || "http://localhost:5173";
    const address = stored.clientId
      ? `${dashboard}/?client=${encodeURIComponent(stored.clientId)}`
      : dashboard;
    chrome.tabs.create({ url: address });
  });

  document.getElementById("clear-history-button").addEventListener("click", async () => {
    await chrome.storage.local.set({ history: [] });
    renderHistory();
  });
});
