/**
 * WebSentry background service worker.
 *
 * This is the part that runs on its own. When you open or change a tab it
 * sends the address to the backend, saves the answer, colours the toolbar
 * badge, and warns you if the website is dangerous.
 *
 * In Manifest V3 this worker is stopped when it is idle, so nothing important
 * is kept in normal variables. Everything is written to chrome.storage.
 */

const DEFAULT_BACKEND = "http://127.0.0.1:8080";

/** How long a result for the same website is reused, in milliseconds. */
const RESULT_TTL_MS = 5 * 60 * 1000;

/** How many past scans we keep on the computer. */
const HISTORY_LIMIT = 50;

const BAND_COLOURS = {
  LOW: "#22c55e",
  MEDIUM: "#f59e0b",
  HIGH: "#ef4444"
};

// ---------------------------------------------------------------------------
// Small storage helpers
// ---------------------------------------------------------------------------

async function getSettings() {
  const stored = await chrome.storage.local.get(["backendUrl", "clientId", "enabled"]);

  let clientId = stored.clientId;
  if (!clientId) {
    // An anonymous id, made on this computer. It is not linked to a name or an
    // email, because the ethics form says no personal data is collected.
    clientId = crypto.randomUUID();
    await chrome.storage.local.set({ clientId });
  }

  return {
    backendUrl: stored.backendUrl || DEFAULT_BACKEND,
    clientId,
    enabled: stored.enabled !== false
  };
}

/** Saves the result for one tab so the popup can read it. */
async function saveTabResult(tabId, payload) {
  await chrome.storage.session.set({ [`tab:${tabId}`]: payload });
}

async function readTabResult(tabId) {
  const key = `tab:${tabId}`;
  const stored = await chrome.storage.session.get(key);
  return stored[key] || null;
}

/** Adds one scan to the history list kept on this computer. */
async function addToHistory(record) {
  const stored = await chrome.storage.local.get("history");
  const history = stored.history || [];

  history.unshift({
    url: record.url,
    domain: record.domain,
    riskScore: record.riskScore,
    riskBand: record.riskBand,
    recommendation: record.recommendation,
    timestamp: record.timestamp || new Date().toISOString()
  });

  await chrome.storage.local.set({ history: history.slice(0, HISTORY_LIMIT) });
}

// ---------------------------------------------------------------------------
// Badge
// ---------------------------------------------------------------------------

async function setBadge(tabId, state, record) {
  if (state === "scanning") {
    await chrome.action.setBadgeText({ tabId, text: "..." });
    await chrome.action.setBadgeBackgroundColor({ tabId, color: "#6b7280" });
    return;
  }
  if (state === "error") {
    await chrome.action.setBadgeText({ tabId, text: "!" });
    await chrome.action.setBadgeBackgroundColor({ tabId, color: "#6b7280" });
    return;
  }
  if (state === "off") {
    await chrome.action.setBadgeText({ tabId, text: "" });
    return;
  }

  await chrome.action.setBadgeText({ tabId, text: String(record.riskScore) });
  await chrome.action.setBadgeBackgroundColor({
    tabId,
    color: BAND_COLOURS[record.riskBand] || "#6b7280"
  });
}

// ---------------------------------------------------------------------------
// Warning for dangerous websites
// ---------------------------------------------------------------------------

async function warnIfDangerous(record) {
  if (record.riskBand !== "HIGH") {
    return;
  }
  try {
    await chrome.notifications.create(`websentry-${Date.now()}`, {
      type: "basic",
      iconUrl: chrome.runtime.getURL("assets/icon128.png"),
      title: "WebSentry: leave this website",
      message: `${record.domain} scored ${record.riskScore} out of 100. ${record.recommendation}.`,
      priority: 2
    });
  } catch (error) {
    // A blocked notification must never stop the scan from finishing.
    console.warn("WebSentry could not show a notification:", error);
  }
}

// ---------------------------------------------------------------------------
// Scanning
// ---------------------------------------------------------------------------

/** True for addresses we can actually check. */
function isScannable(url) {
  return typeof url === "string" && (url.startsWith("http://") || url.startsWith("https://"));
}

/**
 * @param force true when the user pressed "Check again", so the saved answer
 *              is ignored and the website is checked from the start.
 */
async function scanUrl(tabId, url, force = false) {
  const settings = await getSettings();
  if (!settings.enabled) {
    await setBadge(tabId, "off");
    return;
  }

  // Reuse a recent answer for the same address instead of asking again.
  const existing = await readTabResult(tabId);
  if (
    !force &&
    existing &&
    existing.status === "ok" &&
    existing.record.url === url &&
    Date.now() - existing.scannedAt < RESULT_TTL_MS
  ) {
    await setBadge(tabId, "ok", existing.record);
    return;
  }

  await saveTabResult(tabId, { status: "scanning", url, scannedAt: Date.now() });
  await setBadge(tabId, "scanning");

  try {
    const response = await fetch(`${settings.backendUrl}/api/v1/scan`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url, clientId: settings.clientId })
    });

    if (!response.ok) {
      throw new Error(`The backend answered with status ${response.status}`);
    }

    const record = await response.json();

    await saveTabResult(tabId, { status: "ok", record, scannedAt: Date.now() });
    await setBadge(tabId, "ok", record);
    await addToHistory(record);
    await warnIfDangerous(record);
  } catch (error) {
    console.warn("WebSentry scan failed:", error);
    await saveTabResult(tabId, {
      status: "error",
      url,
      message: "WebSentry could not reach its backend. Please check that it is running.",
      scannedAt: Date.now()
    });
    await setBadge(tabId, "error");
  }
}

// ---------------------------------------------------------------------------
// Browser events
// ---------------------------------------------------------------------------

// Fires when a page has finished loading in a tab.
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (changeInfo.status === "complete" && isScannable(tab.url)) {
    scanUrl(tabId, tab.url);
  }
});

// Fires when the user switches to another tab.
chrome.tabs.onActivated.addListener(async ({ tabId }) => {
  try {
    const tab = await chrome.tabs.get(tabId);
    if (isScannable(tab.url)) {
      scanUrl(tabId, tab.url);
    } else {
      await setBadge(tabId, "off");
    }
  } catch (error) {
    // The tab may already be gone. Nothing to do.
  }
});

// Clean up when a tab is closed.
chrome.tabs.onRemoved.addListener((tabId) => {
  chrome.storage.session.remove(`tab:${tabId}`);
});

// ---------------------------------------------------------------------------
// Messages from the popup and the content script
// ---------------------------------------------------------------------------

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === "GET_RESULT") {
    readTabResult(message.tabId).then(sendResponse);
    return true; // keeps the channel open for the async answer
  }

  if (message.type === "RESCAN") {
    scanUrl(message.tabId, message.url, message.force === true).then(() => {
      readTabResult(message.tabId).then(sendResponse);
    });
    return true;
  }

  if (message.type === "PAGE_FINDINGS") {
    // The content script tells us what it saw in the real page. This is stored
    // next to the scan so the popup can show it.
    const tabId = sender.tab && sender.tab.id;
    if (tabId !== undefined) {
      chrome.storage.session.set({ [`page:${tabId}`]: message.findings });
    }
    sendResponse({ ok: true });
    return true;
  }

  return false;
});
