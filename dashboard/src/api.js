/**
 * All calls to the WebSentry backend live here, so the screens themselves stay
 * simple and there is one place to change if an address changes.
 */

const BASE = "/api/v1";

async function get(path) {
  const response = await fetch(`${BASE}${path}`);
  if (!response.ok) {
    throw new Error(`The backend answered with status ${response.status}`);
  }
  return response.json();
}

export async function login(username, password) {
  const response = await fetch(`${BASE}/admin/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.message || "Wrong username or password.");
  }
  return body;
}

export const getHealth = () => get("/health");
export const getStats = (days = 7) => get(`/admin/stats?days=${days}`);
export const getScans = (page = 0, size = 25) => get(`/admin/scans?page=${page}&size=${size}`);
export const getFlagged = (limit = 20) => get(`/admin/flagged?limit=${limit}`);
export const getModel = () => get("/admin/model");
