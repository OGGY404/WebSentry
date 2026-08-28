/** WebSentry content script. */

(function () {
  "use strict";

  // Pages served from this computer or a private network are left alone.
  // Plain http is normal there, so warning about it is wrong, and it would
  // mean the extension warns about its own dashboard.
  const LOCAL_HOSTS =
    /^(localhost|\[::1\])$|^0\.0\.0\.0$|^127(\.\d{1,3}){3}$|^10(\.\d{1,3}){3}$|^192\.168(\.\d{1,3}){2}$|^172\.(1[6-9]|2\d|3[01])(\.\d{1,3}){2}$/i;

  if (LOCAL_HOSTS.test(location.hostname)) {
    return;
  }

  /** Tags that load a file into the page. */
  const RESOURCE_TAGS = ["img", "script", "iframe", "link", "audio", "video", "source", "embed", "object"];

  /** Finds files loaded over http inside an https page. */
  function findMixedContent() {
    if (window.location.protocol !== "https:") {
      return [];
    }

    const found = [];
    for (const tag of RESOURCE_TAGS) {
      for (const element of document.getElementsByTagName(tag)) {
        const address = element.src || element.href || element.data || "";
        if (typeof address === "string" && address.startsWith("http://")) {
          found.push({ tag, url: address });
          if (found.length >= 10) {
            return found; // a few examples are enough
          }
        }
      }
    }
    return found;
  }

  /** True if the page asks for a password without being encrypted. */
  function hasInsecurePasswordField() {
    if (window.location.protocol === "https:") {
      return false;
    }
    return document.querySelector('input[type="password"]') !== null;
  }

  /** Finds login forms that send your details to a different website. */
  function findCrossSiteForms() {
    const found = [];
    const here = window.location.hostname;

    for (const form of document.forms) {
      const action = form.getAttribute("action");
      if (!action || !action.includes("://")) {
        continue;
      }
      const hasPassword = form.querySelector('input[type="password"]') !== null;
      if (!hasPassword) {
        continue;
      }
      try {
        const target = new URL(action, window.location.href).hostname;
        if (target && target !== here) {
          found.push({ sendsTo: target });
        }
      } catch (error) {
        // An address we cannot read is ignored rather than guessed at.
      }
    }
    return found;
  }

  function collectFindings() {
    const mixedContent = findMixedContent();
    const crossSiteForms = findCrossSiteForms();

    return {
      url: window.location.href,
      protocol: window.location.protocol,
      mixedContentCount: mixedContent.length,
      mixedContentExamples: mixedContent.slice(0, 3),
      insecurePasswordField: hasInsecurePasswordField(),
      crossSiteFormCount: crossSiteForms.length,
      crossSiteForms: crossSiteForms.slice(0, 3),
      checkedAt: new Date().toISOString()
    };
  }

  function report() {
    try {
      chrome.runtime.sendMessage({ type: "PAGE_FINDINGS", findings: collectFindings() });
    } catch (error) {
      // This happens if the extension was reloaded while the page was open.
    }
  }

  // Report twice, because many pages load more files after the first paint.
  report();
  setTimeout(report, 2000);

  // ---------------------------------------------------------------------
  // The full page warning
  //
  // A small notification is easy to miss. Research on security warnings
  // shows that a warning the user must act on is followed far more often
  // than one they can ignore, so a dangerous site is covered by this panel
  // until the user makes a choice.
  // ---------------------------------------------------------------------

  const OVERLAY_ID = "websentry-warning-overlay";

  function buildWarning(record) {
    if (document.getElementById(OVERLAY_ID)) {
      return;
    }

    const host = document.createElement("div");
    host.id = OVERLAY_ID;
    host.style.cssText =
      "position:fixed;inset:0;z-index:2147483647;border:0;margin:0;padding:0;";

    // A shadow root keeps the page styles out of the warning, and the
    // warning styles out of the page.
    const shadow = host.attachShadow({ mode: "closed" });

    const reasons = ((record.checks && record.checks.reasons) || []).slice(0, 4);
    const reasonList = reasons.length
      ? `<ul class="reasons">${reasons.map((r) => `<li>${escapeText(r)}</li>`).join("")}</ul>`
      : "";

    shadow.innerHTML = `
      <style>
        :host { all: initial; }
        * { box-sizing: border-box; }
        .sheet {
          position: fixed; inset: 0;
          background: #7f1d1d;
          display: flex; align-items: center; justify-content: center;
          padding: 24px;
          font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
          overflow: auto;
        }
        .card {
          width: 100%; max-width: 560px;
          background: #ffffff; border-radius: 14px;
          padding: 32px 34px;
          box-shadow: 0 24px 70px rgba(0,0,0,.45);
        }
        .mark {
          width: 52px; height: 52px; border-radius: 50%;
          background: #fee2e2; color: #b91c1c;
          display: flex; align-items: center; justify-content: center;
          font-size: 28px; font-weight: 700; margin-bottom: 18px;
        }
        h1 { margin: 0 0 10px; font-size: 25px; line-height: 1.2; color: #111827; }
        .site {
          font-family: ui-monospace, Consolas, monospace;
          font-size: 13px; color: #6b7280;
          word-break: break-all; margin: 0 0 18px;
        }
        .why { margin: 0 0 16px; font-size: 15px; line-height: 1.6; color: #374151; }
        .reasons { margin: 0 0 20px; padding-left: 20px; }
        .reasons li { font-size: 14px; line-height: 1.55; color: #4b5563; margin-bottom: 6px; }
        .score {
          display: inline-flex; align-items: baseline; gap: 7px;
          background: #fef2f2; border: 1px solid #fecaca;
          border-radius: 8px; padding: 7px 13px; margin-bottom: 18px;
        }
        .score b { font-size: 21px; color: #b91c1c; }
        .score span { font-size: 12px; color: #7f1d1d; }
        .buttons { display: flex; gap: 11px; flex-wrap: wrap; }
        button {
          font: inherit; font-size: 14px; font-weight: 600;
          border-radius: 9px; padding: 12px 20px; cursor: pointer; border: 0;
        }
        .leave { background: #15803d; color: #fff; flex: 1; min-width: 190px; }
        .leave:hover { background: #166534; }
        .stay { background: #fff; color: #6b7280; border: 1px solid #d1d5db; }
        .stay:hover { background: #f9fafb; color: #374151; }
        .footnote { margin: 16px 0 0; font-size: 11px; color: #9ca3af; }
      </style>

      <div class="sheet" role="alertdialog" aria-modal="true" aria-labelledby="ws-title">
        <div class="card">
          <div class="mark" aria-hidden="true">!</div>
          <h1 id="ws-title">This website looks dangerous</h1>
          <p class="site">${escapeText(record.domain || location.hostname)}</p>

          <div class="score">
            <b>${Number(record.riskScore) || 0}</b><span>out of 100 risk</span>
          </div>

          <p class="why">${escapeText(record.aiExplanation || "")}</p>
          ${reasonList}

          <div class="buttons">
            <button class="leave" id="ws-leave">Take me back to safety</button>
            <button class="stay" id="ws-stay">Continue anyway</button>
          </div>
          <p class="footnote">
            Checked by WebSentry. If you continue, do not enter passwords or card details.
          </p>
        </div>
      </div>
    `;

    document.documentElement.appendChild(host);

    // Stop the page underneath from scrolling while the warning is up.
    const previousOverflow = document.documentElement.style.overflow;
    document.documentElement.style.overflow = "hidden";

    function close() {
      document.documentElement.style.overflow = previousOverflow;
      host.remove();
    }

    shadow.getElementById("ws-leave").addEventListener("click", () => {
      // Going back is the safe default, so it is the green button.
      if (history.length > 1) {
        history.back();
      } else {
        location.href = "about:blank";
      }
    });

    shadow.getElementById("ws-stay").addEventListener("click", () => {
      // The choice is remembered, so the same warning does not appear again
      // on every page of a site the user has decided to accept.
      try {
        chrome.runtime.sendMessage({
          type: "TRUST_SITE",
          domain: record.domain || location.hostname
        });
      } catch (error) {
        // The warning still closes even if the message cannot be sent.
      }
      close();
    });

    shadow.getElementById("ws-leave").focus();
  }

  /** Makes text safe to put inside the warning. */
  function escapeText(value) {
    const holder = document.createElement("div");
    holder.textContent = String(value == null ? "" : value);
    return holder.innerHTML;
  }

  chrome.runtime.onMessage.addListener((message) => {
    if (message && message.type === "SHOW_WARNING" && message.record) {
      pageVerdict = message.record;
      buildWarning(message.record);
    }
    if (message && message.type === "PAGE_VERDICT" && message.record) {
      // Sent for every result, not only dangerous ones, so the password
      // guard knows what this page scored.
      pageVerdict = message.record;
    }
    return false;
  });


  // ---------------------------------------------------------------------
  // The password field guard
  //
  // The moment that matters most is when the user is about to type a
  // password. This watches for that moment on a site that scored badly and
  // puts a short message beside the box.
  // ---------------------------------------------------------------------

  const GUARD_ID = "websentry-password-guard";
  let pageVerdict = null;

  function guardMessage(band, encrypted) {
    if (!encrypted) {
      return "This page is not encrypted. Anything you type here can be read by others.";
    }
    if (band === "HIGH") {
      return "WebSentry believes this website is dangerous. Do not enter your password.";
    }
    return "WebSentry is not sure about this website. Be careful with your password.";
  }

  function showGuard(field) {
    if (document.getElementById(GUARD_ID)) {
      return;
    }

    const encrypted = location.protocol === "https:";
    const band = pageVerdict ? pageVerdict.riskBand : "MEDIUM";

    const host = document.createElement("div");
    host.id = GUARD_ID;
    const shadow = host.attachShadow({ mode: "closed" });

    shadow.innerHTML = `
      <style>
        :host { all: initial; }
        .tip {
          position: fixed; z-index: 2147483646;
          max-width: 300px;
          background: #7f1d1d; color: #fff;
          border-radius: 9px; padding: 11px 13px;
          font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
          font-size: 13px; line-height: 1.5;
          box-shadow: 0 8px 26px rgba(0,0,0,.35);
        }
        .row { display: flex; gap: 9px; align-items: flex-start; }
        .mark { flex-shrink: 0; font-weight: 700; }
        button {
          margin-top: 9px; font: inherit; font-size: 11px;
          background: rgba(255,255,255,.16); color: #fff;
          border: 0; border-radius: 6px; padding: 5px 10px; cursor: pointer;
        }
        button:hover { background: rgba(255,255,255,.26); }
      </style>
      <div class="tip" role="alert">
        <div class="row">
          <span class="mark" aria-hidden="true">!</span>
          <span>${escapeText(guardMessage(band, encrypted))}</span>
        </div>
        <button id="ws-guard-close">I understand</button>
      </div>
    `;

    document.documentElement.appendChild(host);

    // Sit the message just under the password box, and keep it on screen.
    const tip = shadow.querySelector(".tip");
    const box = field.getBoundingClientRect();
    tip.style.top = `${Math.min(box.bottom + 8, window.innerHeight - 130)}px`;
    tip.style.left = `${Math.min(box.left, window.innerWidth - 320)}px`;

    shadow.getElementById("ws-guard-close").addEventListener("click", () => host.remove());
    setTimeout(() => host.remove(), 12000);
  }

  /** Watches every password box on the page, including ones added later. */
  function watchPasswordFields() {
    function attach(field) {
      if (field.dataset.websentryWatched) {
        return;
      }
      field.dataset.websentryWatched = "1";
      field.addEventListener("focus", () => {
        // Only warn when there is something to warn about.
        const risky = pageVerdict && pageVerdict.riskBand !== "LOW";
        if (risky || location.protocol !== "https:") {
          showGuard(field);
        }
      });
    }

    document.querySelectorAll('input[type="password"]').forEach(attach);

    // Many login boxes appear only after the page has loaded.
    new MutationObserver(() => {
      document.querySelectorAll('input[type="password"]').forEach(attach);
    }).observe(document.documentElement, { childList: true, subtree: true });
  }

  watchPasswordFields();


  // ---------------------------------------------------------------------
  // Checking a link before it is clicked
  //
  // Checking every link on a page would mean dozens of requests for one
  // page, which would be slow and would put needless load on the backend.
  // A link is checked when the user hovers over it instead, which is the
  // moment just before a click and costs one request. Answers are kept for
  // the life of the page so the same link is never checked twice.
  // ---------------------------------------------------------------------

  const linkVerdicts = new Map();
  const BADGE_ID = "websentry-link-badge";
  let hoverTimer = null;
  // The link the mouse is on now. Tracked rather than read from the CSS
  // hover state, so the badge is never shown for a link the mouse has
  // already left while the check was still running.
  let hoveredLink = null;

  const BADGE_TEXT = {
    LOW: "Checked. This link looks safe.",
    MEDIUM: "Be careful. This link looks questionable.",
    HIGH: "Danger. WebSentry advises not to open this link."
  };

  const BADGE_COLOUR = { LOW: "#15803d", MEDIUM: "#a16207", HIGH: "#b91c1c" };

  function showLinkBadge(anchor, band) {
    removeLinkBadge();

    const host = document.createElement("div");
    host.id = BADGE_ID;
    const shadow = host.attachShadow({ mode: "closed" });

    shadow.innerHTML = `
      <style>
        :host { all: initial; }
        .badge {
          position: fixed; z-index: 2147483645;
          max-width: 270px;
          background: ${BADGE_COLOUR[band] || "#374151"}; color: #fff;
          border-radius: 8px; padding: 8px 11px;
          font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
          font-size: 12px; line-height: 1.45;
          box-shadow: 0 6px 20px rgba(0,0,0,.3);
          pointer-events: none;
        }
      </style>
      <div class="badge" role="status">${escapeText(BADGE_TEXT[band] || "Checked.")}</div>
    `;

    document.documentElement.appendChild(host);

    const badge = shadow.querySelector(".badge");
    const box = anchor.getBoundingClientRect();
    badge.style.top = `${Math.max(6, box.top - 42)}px`;
    badge.style.left = `${Math.min(box.left, window.innerWidth - 290)}px`;
  }

  function removeLinkBadge() {
    const existing = document.getElementById(BADGE_ID);
    if (existing) {
      existing.remove();
    }
  }

  /** Only links that leave the current website are worth checking. */
  function isWorthChecking(anchor) {
    if (!anchor || !anchor.href) {
      return false;
    }
    if (!anchor.href.startsWith("http://") && !anchor.href.startsWith("https://")) {
      return false;
    }
    try {
      const host = new URL(anchor.href).hostname;
      // A link to this computer or a private network is not checked, for the
      // same reason the page itself is not.
      if (/^(localhost|\[::1\])$|^0\.0\.0\.0$|^127(\.\d{1,3}){3}$|^10(\.\d{1,3}){3}$|^192\.168(\.\d{1,3}){2}$|^172\.(1[6-9]|2\d|3[01])(\.\d{1,3}){2}$/i.test(host)) {
        return false;
      }
      return host !== location.hostname;
    } catch (error) {
      return false;
    }
  }

  document.addEventListener("mouseover", (event) => {
    const anchor = event.target.closest && event.target.closest("a");
    if (!isWorthChecking(anchor)) {
      return;
    }

    hoveredLink = anchor;

    const known = linkVerdicts.get(anchor.href);
    if (known) {
      showLinkBadge(anchor, known);
      return;
    }

    // Wait a moment, so simply moving the mouse across a page of links does
    // not start a check for every one of them.
    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(async () => {
      try {
        const answer = await chrome.runtime.sendMessage({
          type: "CHECK_LINK",
          url: anchor.href
        });
        if (answer && answer.riskBand) {
          linkVerdicts.set(anchor.href, answer.riskBand);
          if (hoveredLink === anchor) {
            showLinkBadge(anchor, answer.riskBand);
          }
        }
      } catch (error) {
        // A link that cannot be checked is simply not marked.
      }
    }, 450);
  });

  document.addEventListener("mouseout", (event) => {
    if (event.target.closest && event.target.closest("a")) {
      hoveredLink = null;
      clearTimeout(hoverTimer);
      removeLinkBadge();
    }
  });

})();
