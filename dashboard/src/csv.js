/**
 * Turns scan records into a CSV file the browser downloads.
 *
 * The file is built here rather than on the backend, from data the page has
 * already loaded, so there is no new endpoint and nothing extra to go wrong.
 */

/** Wraps one value so commas, quotes and line breaks cannot break the file. */
function cell(value) {
  if (value === null || value === undefined) {
    return "";
  }
  const text = String(value);
  if (/[",\n\r]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`;
  }
  return text;
}

const COLUMNS = [
  ["Website", (s) => s.domain],
  ["Full address", (s) => s.url],
  ["Risk score", (s) => s.riskScore],
  ["Band", (s) => s.riskBand],
  ["Recommendation", (s) => s.recommendation],
  ["Encrypted", (s) => (s.checks ? yesNo(s.checks.https) : "")],
  ["Certificate valid", (s) => (s.checks ? yesNo(s.checks.sslValid) : "")],
  ["Certificate issuer", (s) => (s.checks ? s.checks.sslIssuer : "")],
  ["Mixed content", (s) => (s.checks ? yesNo(s.checks.mixedContent) : "")],
  ["On threat list", (s) => (s.checks ? yesNo(s.checks.inPhishingDb) : "")],
  ["Suspicious address", (s) => (s.checks ? yesNo(s.checks.suspiciousPattern) : "")],
  ["Explanation", (s) => s.aiExplanation],
  ["Explanation written by", (s) => (s.explanationFromAi ? "Claude" : "template")],
  ["Scan time ms", (s) => s.scanDurationMs],
  ["Checked at", (s) => s.timestamp]
];

function yesNo(value) {
  return value ? "yes" : "no";
}

/** Builds the text of the file. */
export function toCsv(scans) {
  const header = COLUMNS.map(([name]) => cell(name)).join(",");
  const rows = scans.map((scan) =>
    COLUMNS.map(([, read]) => cell(read(scan))).join(",")
  );
  return [header, ...rows].join("\r\n");
}

/** Builds the file and asks the browser to save it. */
export function downloadCsv(scans, prefix = "websentry-scans") {
  const text = toCsv(scans);

  // The marker at the start tells Excel the file is UTF-8, otherwise accented
  // characters in a website name come out wrong.
  const blob = new Blob(["﻿" + text], { type: "text/csv;charset=utf-8;" });

  const stamp = new Date().toISOString().slice(0, 10);
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `${prefix}-${stamp}.csv`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(link.href);

  return { rows: scans.length, name: link.download };
}
