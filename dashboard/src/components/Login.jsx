import { useState } from "react";

import { login } from "../api.js";

/**
 * The single admin sign in screen.
 *
 * There is one hardcoded account, which was agreed when the project scope was
 * reduced. It is written up as a limitation, not as a security design.
 */
export default function Login({ onSignedIn }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function handleSubmit(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const result = await login(username, password);
      onSignedIn(result.username);
    } catch (problem) {
      setError(problem.message || "Could not sign in.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-screen">
      <form className="login-card" onSubmit={handleSubmit}>
        <div style={{ display: "flex", justifyContent: "center" }}>
          <div className="brand-mark" style={{ width: 34, height: 34, fontSize: 17 }}>
            W
          </div>
        </div>
        <h1 className="login-title">WebSentry Admin</h1>
        <p className="login-subtitle">Sign in to see all website scans.</p>

        {error && <div className="message error">{error}</div>}

        <div className="field">
          <label htmlFor="username">Username</label>
          <input
            id="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            autoComplete="username"
            required
          />
        </div>

        <div className="field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            required
          />
        </div>

        <button className="button" style={{ width: "100%", marginTop: 8 }} disabled={busy}>
          {busy ? "Signing in..." : "Sign in"}
        </button>
      </form>
    </div>
  );
}
