/** Small shared helpers for showing values on screen. */

/** "LOW" becomes "low", used to pick the colour class. */
export function bandClass(band) {
  return (band || "low").toLowerCase();
}

/** Turns an ISO timestamp into something readable. */
export function formatWhen(isoString) {
  if (!isoString) {
    return "";
  }
  const when = new Date(isoString);
  if (Number.isNaN(when.getTime())) {
    return "";
  }
  return when.toLocaleString(undefined, {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit"
  });
}

/** Shows a percentage with two decimals, or a dash when there is no value. */
export function percent(value) {
  if (typeof value !== "number") {
    return "—";
  }
  return `${(value * 100).toFixed(2)}%`;
}

/** Shortens a long web address so the table stays readable. */
export function shorten(url, limit = 70) {
  if (!url) {
    return "";
  }
  return url.length <= limit ? url : `${url.slice(0, limit)}...`;
}
