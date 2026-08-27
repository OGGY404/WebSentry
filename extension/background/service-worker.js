/** WebSentry background service worker. */

const DEFAULT_BACKEND = "http://127.0.0.1:8080";

/** How long a result for the same website is reused, in milliseconds. */
const RESULT_TTL_MS = 5 * 60 * 1000;

/** How many past scans we keep on the computer. */
const HISTORY_LIMIT = 50;

/** Every message starts the same way, so it is easy to find in the console. */
function log(...parts) {
  console.log("[WebSentry]", ...parts);
}

function logProblem(...parts) {
  console.error("[WebSentry]", ...parts);
}

/** Websites the user has chosen to accept, so they are not warned again. */
async function trustedSites() {
  const stored = await chrome.storage.local.get("trustedSites");
  return stored.trustedSites || [];
}

async function isTrusted(domain) {
  return (await trustedSites()).includes(domain);
}

async function trustSite(domain) {
  const list = await trustedSites();
  if (domain && !list.includes(domain)) {
    list.push(domain);
    await chrome.storage.local.set({ trustedSites: list });
    log("added to trusted sites:", domain);
  }
}

/** Keeps the last problem so the popup can show what actually went wrong. */
async function rememberProblem(detail) {
  await chrome.storage.local.set({
    lastProblem: { detail, at: new Date().toISOString() }
  });
}

const BAND_COLOURS = {
  LOW: "#22c55e",
  MEDIUM: "#f59e0b",
  HIGH: "#ef4444"
};

// Storage helpers

async function getSettings() {
  const stored = await chrome.storage.local.get(["backendUrl", "clientId", "enabled"]);

  let clientId = stored.clientId;
  if (!clientId) {
    // An anonymous id, made on this computer.
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

// --------------------------------------------------------------------------- Badge.

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

// --------------------------------------------------------------------------- Warning for.

async function warnIfDangerous(record, tabId) {
  if (record.riskBand !== "HIGH") {
    return;
  }

  // A site the user has already chosen to accept is not warned about again.
  if (await isTrusted(record.domain)) {
    log("skipping the warning, site is trusted:", record.domain);
    return;
  }

  // The full page warning is the important part. A notification alone is
  // easy to miss, and research on warnings shows an ignorable warning is
  // usually ignored.
  if (tabId !== undefined) {
    try {
      await chrome.tabs.sendMessage(tabId, { type: "SHOW_WARNING", record });
    } catch (error) {
      // The content script is not present on every page, for example on a
      // PDF or a page that failed to load. The notification below still runs.
      log("could not show the full page warning:", error.message);
    }
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

// --------------------------------------------------------------------------- Scanning.

/** True for addresses we can actually check. */
function isScannable(url) {
  return typeof url === "string" && (url.startsWith("http://") || url.startsWith("https://"));
}

/** force is true when the user pressed Check again, so the saved answer is ignored. */
async function scanUrl(tabId, url, force = false) {
  let backendUrl = DEFAULT_BACKEND;

  try {
    const settings = await getSettings();
    backendUrl = settings.backendUrl;

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
      log("using the saved answer for", url);
      await setBadge(tabId, "ok", existing.record);
      return;
    }

    log("scanning", url, "using", backendUrl);
    await saveTabResult(tabId, { status: "scanning", url, scannedAt: Date.now() });
    await setBadge(tabId, "scanning");

    let response;
    try {
      response = await fetch(`${backendUrl}/api/v1/scan`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url, clientId: settings.clientId })
      });
    } catch (networkError) {
      // The request never arrived. Almost always the backend is not running.
      throw new Error(
        `Could not reach the backend at ${backendUrl}. Check that it is running. ` +
        `(${networkError.message})`
      );
    }

    if (!response.ok) {
      const body = await response.text().catch(() => "");
      throw new Error(
        `The backend answered ${response.status} ${response.statusText}. ` +
        (body ? `It said: ${body.slice(0, 160)}` : "It sent no message.")
      );
    }

    let record;
    try {
      record = await response.json();
    } catch (parseError) {
      throw new Error("The backend replied with something that was not a scan result.");
    }

    if (typeof record.riskScore !== "number") {
      throw new Error("The scan result had no risk score in it.");
    }

    await saveTabResult(tabId, { status: "ok", record, scannedAt: Date.now() });
    await setBadge(tabId, "ok", record);
    await addToHistory(record);

    // The password guard in the page needs to know the verdict, whatever it is.
    try {
      await chrome.tabs.sendMessage(tabId, { type: "PAGE_VERDICT", record });
    } catch (error) {
      // No content script on this page. Not a problem.
    }

    await warnIfDangerous(record, tabId);
    log("scanned", record.domain, "->", record.riskScore, record.riskBand);

  } catch (error) {
    // Every failure ends here, including the ones that used to escape before
    // the try block and leave the badge blank with nothing in the console.
    const detail = error && error.message ? error.message : String(error);
    logProblem("scan failed for", url, "->", detail);
    await rememberProblem(detail);

    try {
      await saveTabResult(tabId, {
        status: "error",
        url,
        message: detail,
        backendUrl,
        scannedAt: Date.now()
      });
      await setBadge(tabId, "error");
    } catch (storageError) {
      // If even storage is failing there is nowhere left to report to.
      logProblem("could not record the failure:", storageError);
    }
  }
}

/** Scores a single link, for the check that runs when a link is hovered. */
async function checkLink(url) {
  const settings = await getSettings();
  if (!settings.enabled) {
    return null;
  }

  try {
    // live=false keeps this quick. A hover must not wait for a website to
    // answer, and the address itself is what is being judged here.
    const response = await fetch(`${settings.backendUrl}/api/v1/scan?live=false`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url, clientId: settings.clientId })
    });
    if (!response.ok) {
      return null;
    }
    const record = await response.json();
    return { riskBand: record.riskBand, riskScore: record.riskScore };
  } catch (error) {
    logProblem("link check failed:", error.message);
    return null;
  }
}

/** Checks one backend address and reports what it answered. */
async function testConnection(backendUrl) {
  const address = (backendUrl || DEFAULT_BACKEND).replace(/\/+$/, "");

  try {
    const response = await fetch(`${address}/api/v1/health`);
    if (!response.ok) {
      return { ok: false, message: `That address answered ${response.status}.` };
    }

    const health = await response.json();
    if (health.status !== "ok") {
      return { ok: false, message: "That address answered, but not as a healthy backend." };
    }

    return {
      ok: true,
      storage: health.storage || "unknown",
      explanations: health.claudeExplanations || "unknown",
      scorer: health.scorer || "unknown"
    };
  } catch (error) {
    return { ok: false, message: `Could not reach ${address}. Check the backend is running.` };
  }
}

// Browser events

// Fires when a page has finished loading in a tab.
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (changeInfo.status === "complete" && isScannable(tab.url)) {
    // The catch matters. Without it a failure here disappears with no badge
    // and no message, which looks exactly like the extension doing nothing.
    scanUrl(tabId, tab.url).catch((error) => logProblem("tab update:", error));
  }
});

// Fires when the user switches to another tab.
chrome.tabs.onActivated.addListener(async ({ tabId }) => {
  try {
    const tab = await chrome.tabs.get(tabId);
    if (isScannable(tab.url)) {
      scanUrl(tabId, tab.url).catch((error) => logProblem("tab switch:", error));
    } else {
      await setBadge(tabId, "off");
    }
  } catch (error) {
    // The tab may already be gone.
  }
});

// Clean up when a tab is closed.
chrome.tabs.onRemoved.addListener((tabId) => {
  chrome.storage.session.remove(`tab:${tabId}`);
});

// Messages from the popup and the content script

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === "GET_RESULT") {
    readTabResult(message.tabId).then(sendResponse);
    return true; // keeps the channel open for the async answer
  }

  if (message.type === "RESCAN") {
    scanUrl(message.tabId, message.url, message.force === true)
      .catch((error) => logProblem("rescan:", error))
      .then(() => readTabResult(message.tabId))
      .then(sendResponse)
      // The popup waits for this reply, so it must always be sent.
      .catch(() => sendResponse({ status: "error", message: "The scan could not be started." }));
    return true;
  }

  if (message.type === "CHECK_LINK") {
    // Scores one link without saving it as a visit or touching the badge.
    checkLink(message.url)
      .then(sendResponse)
      .catch(() => sendResponse(null));
    return true;
  }

  if (message.type === "TRUST_SITE") {
    trustSite(message.domain)
      .then(() => sendResponse({ ok: true }))
      .catch(() => sendResponse({ ok: false }));
    return true;
  }

  if (message.type === "GET_TRUSTED") {
    trustedSites().then((list) => sendResponse({ sites: list })).catch(() => sendResponse({ sites: [] }));
    return true;
  }

  if (message.type === "UNTRUST_SITE") {
    trustedSites()
      .then((list) => chrome.storage.local.set({
        trustedSites: list.filter((d) => d !== message.domain)
      }))
      .then(() => sendResponse({ ok: true }))
      .catch(() => sendResponse({ ok: false }));
    return true;
  }

  if (message.type === "TEST_CONNECTION") {
    // Asks the backend health endpoint whether it is there, so the user can
    // check an address before saving it.
    testConnection(message.backendUrl)
      .then(sendResponse)
      .catch((error) => sendResponse({ ok: false, message: String(error) }));
    return true;
  }

  if (message.type === "GET_DIAGNOSTICS") {
    getSettings()
      .then(async (settings) => {
        const stored = await chrome.storage.local.get("lastProblem");
        sendResponse({ backendUrl: settings.backendUrl, lastProblem: stored.lastProblem || null });
      })
      .catch(() => sendResponse({ backendUrl: DEFAULT_BACKEND, lastProblem: null }));
    return true;
  }

  if (message.type === "PAGE_FINDINGS") {
    // The content script tells us what it saw in the real page.
    const tabId = sender.tab && sender.tab.id;
    if (tabId !== undefined) {
      chrome.storage.session.set({ [`page:${tabId}`]: message.findings });
    }
    sendResponse({ ok: true });
    return true;
  }

  return false;
});

log("service worker started. Backend expected at", DEFAULT_BACKEND);
