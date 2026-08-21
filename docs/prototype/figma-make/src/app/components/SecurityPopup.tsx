import { useState } from "react";
import { CheckCircle, AlertTriangle, XCircle, Shield, ExternalLink, Sun, Moon } from "lucide-react";

type Status = "safe" | "caution" | "danger";

interface CheckItem {
  label: string;
  passed: boolean;
  detail: string;
}

interface ReportData {
  score: number;
  status: Status;
  domain: string;
  explanation: string;
  checks: CheckItem[];
}

const reports: Record<Status, ReportData> = {
  safe: {
    score: 87,
    status: "safe",
    domain: "mybank.co.uk",
    explanation:
      "This site uses a valid SSL certificate, has a clean reputation, and shows no signs of phishing or malicious content. It's safe to continue.",
    checks: [
      { label: "SSL Certificate", passed: true, detail: "Valid, issued by DigiCert" },
      { label: "Domain Age", passed: true, detail: "Registered 14 years ago" },
      { label: "Phishing Signals", passed: true, detail: "None detected" },
      { label: "Malware Scan", passed: true, detail: "No threats found" },
    ],
  },
  caution: {
    score: 54,
    status: "caution",
    domain: "shop-deals-uk.net",
    explanation:
      "This site has some warning signs — it was registered recently and has limited reputation data. Avoid entering payment details until you've verified it's legitimate.",
    checks: [
      { label: "SSL Certificate", passed: true, detail: "Valid, self-signed cert" },
      { label: "Domain Age", passed: false, detail: "Registered 23 days ago" },
      { label: "Phishing Signals", passed: true, detail: "None detected" },
      { label: "Malware Scan", passed: false, detail: "Flagged by 2 engines" },
    ],
  },
  danger: {
    score: 12,
    status: "danger",
    domain: "paypall-secure.com",
    explanation:
      "This site is impersonating a trusted service and has been flagged for phishing. Do not enter any personal information — leave this page immediately.",
    checks: [
      { label: "SSL Certificate", passed: false, detail: "Expired 3 months ago" },
      { label: "Domain Age", passed: false, detail: "Registered 4 days ago" },
      { label: "Phishing Signals", passed: false, detail: "Active phishing detected" },
      { label: "Malware Scan", passed: false, detail: "Flagged by 11 engines" },
    ],
  },
};

const statusConfig = {
  safe: {
    label: "Stay Safe",
    scoreColor: "#22c55e",
    trackColor: "rgba(34,197,94,0.15)",
    badgeBg: "rgba(34,197,94,0.12)",
    badgeText: "#16a34a",
    badgeBorder: "rgba(34,197,94,0.3)",
    darkBadgeText: "#4ade80",
  },
  caution: {
    label: "Proceed with Caution",
    scoreColor: "#f59e0b",
    trackColor: "rgba(245,158,11,0.15)",
    badgeBg: "rgba(245,158,11,0.12)",
    badgeText: "#b45309",
    badgeBorder: "rgba(245,158,11,0.3)",
    darkBadgeText: "#fbbf24",
  },
  danger: {
    label: "Leave Immediately",
    scoreColor: "#ef4444",
    trackColor: "rgba(239,68,68,0.15)",
    badgeBg: "rgba(239,68,68,0.12)",
    badgeText: "#dc2626",
    badgeBorder: "rgba(239,68,68,0.3)",
    darkBadgeText: "#f87171",
  },
};

function CircularScore({
  score,
  status,
  isDark,
}: {
  score: number;
  status: Status;
  isDark: boolean;
}) {
  const cfg = statusConfig[status];
  const radius = 52;
  const circ = 2 * Math.PI * radius;
  const offset = circ - (score / 100) * circ;

  return (
    <div className="relative flex items-center justify-center" style={{ width: 128, height: 128 }}>
      <svg width="128" height="128" style={{ transform: "rotate(-90deg)" }}>
        {/* track */}
        <circle
          cx="64"
          cy="64"
          r={radius}
          fill="none"
          stroke={cfg.trackColor}
          strokeWidth="8"
        />
        {/* progress */}
        <circle
          cx="64"
          cy="64"
          r={radius}
          fill="none"
          stroke={cfg.scoreColor}
          strokeWidth="8"
          strokeLinecap="round"
          strokeDasharray={circ}
          strokeDashoffset={offset}
          style={{ transition: "stroke-dashoffset 0.6s ease" }}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span
          style={{
            fontSize: 32,
            fontWeight: 700,
            color: cfg.scoreColor,
            lineHeight: 1,
            fontFamily: "DM Mono, monospace",
          }}
        >
          {score}
        </span>
        <span
          style={{
            fontSize: 11,
            fontWeight: 500,
            color: isDark ? "#8b95a1" : "#6b7280",
            letterSpacing: "0.06em",
            textTransform: "uppercase",
          }}
        >
          / 100
        </span>
      </div>
    </div>
  );
}

function StatusBadge({ status, isDark }: { status: Status; isDark: boolean }) {
  const cfg = statusConfig[status];
  const icons = {
    safe: <CheckCircle size={13} />,
    caution: <AlertTriangle size={13} />,
    danger: <XCircle size={13} />,
  };

  return (
    <div
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 5,
        padding: "4px 10px",
        borderRadius: 20,
        background: cfg.badgeBg,
        border: `1px solid ${cfg.badgeBorder}`,
        color: isDark ? cfg.darkBadgeText : cfg.badgeText,
        fontSize: 12,
        fontWeight: 600,
        letterSpacing: "0.01em",
      }}
    >
      {icons[status]}
      {cfg.label}
    </div>
  );
}

function CheckRow({ item, isDark }: { item: CheckItem; isDark: boolean }) {
  return (
    <div
      className="flex items-center justify-between py-2"
      style={{
        borderBottom: `1px solid ${isDark ? "rgba(255,255,255,0.06)" : "rgba(0,0,0,0.06)"}`,
      }}
    >
      <div className="flex items-center gap-2.5">
        {item.passed ? (
          <CheckCircle size={15} style={{ color: "#22c55e", flexShrink: 0 }} />
        ) : (
          <AlertTriangle size={15} style={{ color: "#f59e0b", flexShrink: 0 }} />
        )}
        <span
          style={{
            fontSize: 13,
            fontWeight: 500,
            color: isDark ? "#e8ecf0" : "#0f1117",
          }}
        >
          {item.label}
        </span>
      </div>
      <span
        style={{
          fontSize: 11,
          color: isDark ? "#8b95a1" : "#6b7280",
          fontFamily: "DM Mono, monospace",
          maxWidth: 140,
          textAlign: "right",
        }}
      >
        {item.detail}
      </span>
    </div>
  );
}

export function SecurityPopup() {
  const [isDark, setIsDark] = useState(true);
  const [activeStatus, setActiveStatus] = useState<Status>("caution");
  const report = reports[activeStatus];

  const bg = isDark ? "#0d1117" : "#f5f6f8";
  const surface = isDark ? "#161b27" : "#ffffff";
  const border = isDark ? "rgba(255,255,255,0.07)" : "rgba(0,0,0,0.08)";
  const text = isDark ? "#e8ecf0" : "#0f1117";
  const muted = isDark ? "#8b95a1" : "#6b7280";

  return (
    <div
      style={{
        background: bg,
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontFamily: "Inter, system-ui, sans-serif",
      }}
    >
      {/* popup frame */}
      <div
        style={{
          width: 360,
          background: surface,
          borderRadius: 14,
          border: `1px solid ${border}`,
          overflow: "hidden",
          boxShadow: isDark
            ? "0 20px 60px rgba(0,0,0,0.6)"
            : "0 8px 32px rgba(0,0,0,0.12)",
        }}
      >
        {/* header */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: "14px 16px",
            borderBottom: `1px solid ${border}`,
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 7 }}>
            <Shield size={16} style={{ color: isDark ? "#4c6ef5" : "#3b5bdb" }} />
            <span style={{ fontSize: 13, fontWeight: 600, color: text, letterSpacing: "-0.01em" }}>
              Site Guard
            </span>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            {/* demo switcher */}
            <div style={{ display: "flex", gap: 4 }}>
              {(["safe", "caution", "danger"] as Status[]).map((s) => (
                <button
                  key={s}
                  onClick={() => setActiveStatus(s)}
                  style={{
                    width: 8,
                    height: 8,
                    borderRadius: "50%",
                    border: "none",
                    cursor: "pointer",
                    background:
                      activeStatus === s
                        ? s === "safe"
                          ? "#22c55e"
                          : s === "caution"
                          ? "#f59e0b"
                          : "#ef4444"
                        : isDark
                        ? "#2a3347"
                        : "#d1d5db",
                    transition: "background 0.2s",
                  }}
                  title={`Demo: ${statusConfig[s].label}`}
                />
              ))}
            </div>
            {/* theme toggle */}
            <button
              onClick={() => setIsDark(!isDark)}
              style={{
                background: "none",
                border: "none",
                cursor: "pointer",
                color: muted,
                display: "flex",
                padding: 0,
              }}
            >
              {isDark ? <Sun size={15} /> : <Moon size={15} />}
            </button>
          </div>
        </div>

        {/* score section */}
        <div
          style={{
            padding: "20px 16px 16px",
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            gap: 12,
          }}
        >
          <CircularScore score={report.score} status={activeStatus} isDark={isDark} />
          <StatusBadge status={activeStatus} isDark={isDark} />

          {/* domain */}
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 5,
              color: muted,
              fontSize: 11,
              fontFamily: "DM Mono, monospace",
              letterSpacing: "0.02em",
            }}
          >
            <ExternalLink size={11} />
            {report.domain}
          </div>

          {/* explanation */}
          <p
            style={{
              fontSize: 13,
              lineHeight: 1.6,
              color: isDark ? "#b0b9c5" : "#374151",
              textAlign: "center",
              margin: 0,
              padding: "0 4px",
            }}
          >
            {report.explanation}
          </p>
        </div>

        {/* divider */}
        <div style={{ height: 1, background: border, margin: "0 16px" }} />

        {/* checks */}
        <div style={{ padding: "4px 16px 0" }}>
          <div
            style={{
              fontSize: 10,
              fontWeight: 600,
              letterSpacing: "0.1em",
              textTransform: "uppercase",
              color: muted,
              padding: "10px 0 6px",
            }}
          >
            Security Checks
          </div>
          {report.checks.map((item) => (
            <CheckRow key={item.label} item={item} isDark={isDark} />
          ))}
        </div>

        {/* footer */}
        <div style={{ padding: "14px 16px" }}>
          <button
            style={{
              width: "100%",
              padding: "10px 0",
              background: isDark ? "#4c6ef5" : "#3b5bdb",
              color: "#ffffff",
              border: "none",
              borderRadius: 8,
              fontSize: 13,
              fontWeight: 600,
              cursor: "pointer",
              letterSpacing: "-0.01em",
              transition: "opacity 0.15s",
              fontFamily: "Inter, system-ui, sans-serif",
            }}
            onMouseEnter={(e) => (e.currentTarget.style.opacity = "0.88")}
            onMouseLeave={(e) => (e.currentTarget.style.opacity = "1")}
          >
            View Full Report
          </button>
        </div>
      </div>
    </div>
  );
}
