/** WebSentry content script. */

(function () {
  "use strict";

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
})();
