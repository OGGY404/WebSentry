"""
Draws the four design diagrams for the WebSentry report.

They are drawn in code rather than in a drawing tool so they can be redrawn at
any size and stay consistent with each other.

Output: WebSentry/docs/diagrams/*.png
"""

from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch, Ellipse, Rectangle

OUT = Path(r"D:\ICBT\Final Project\WebSentry\docs\diagrams")
OUT.mkdir(parents=True, exist_ok=True)

INK = "#12151c"
MUTED = "#5b6475"
LINE = "#9aa3b2"
BLUE = "#3b5bdb"
BLUE_FILL = "#eaeefb"
GREY_FILL = "#f2f4f7"
GREEN_FILL = "#e6f5ec"
GREEN = "#157f3d"
AMBER_FILL = "#fdf1dd"
AMBER = "#9a5b00"

FONT = {"family": "DejaVu Sans"}


def box(ax, x, y, w, h, text, fill=GREY_FILL, edge=LINE, size=9, bold=False, radius=0.02):
    ax.add_patch(FancyBboxPatch(
        (x, y), w, h,
        boxstyle=f"round,pad=0,rounding_size={radius}",
        facecolor=fill, edgecolor=edge, linewidth=1.2, zorder=2))
    ax.text(x + w / 2, y + h / 2, text, ha="center", va="center",
            fontsize=size, color=INK, zorder=3,
            fontweight="bold" if bold else "normal", **FONT)


def arrow(ax, start, end, text=None, style="-|>", dashed=False, offset=0.0, size=7.5):
    ax.add_patch(FancyArrowPatch(
        start, end, arrowstyle=style, mutation_scale=11,
        linewidth=1.1, color=MUTED, zorder=1,
        linestyle="--" if dashed else "-",
        shrinkA=2, shrinkB=2))
    if text:
        mx = (start[0] + end[0]) / 2
        my = (start[1] + end[1]) / 2 + offset
        ax.text(mx, my, text, ha="center", va="center", fontsize=size,
                color=MUTED, zorder=3,
                bbox=dict(boxstyle="round,pad=0.22", fc="white", ec="none"), **FONT)


def finish(fig, ax, name, title):
    ax.set_xlim(0, 10)
    ax.axis("off")
    fig.suptitle(title, fontsize=11, fontweight="bold", color=INK, y=0.97, **FONT)
    fig.savefig(OUT / name, dpi=200, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    print(f"  wrote {name}")


# ---------------------------------------------------------------------------
# 1. Architecture
# ---------------------------------------------------------------------------
def architecture():
    fig, ax = plt.subplots(figsize=(9, 7.2))
    ax.set_ylim(0, 11)

    box(ax, 0.6, 9.3, 4.4, 1.2,
        "BROWSER EXTENSION  (Chrome / Edge, Manifest V3)\n"
        "background worker  ·  content script  ·  popup",
        fill=BLUE_FILL, edge=BLUE, bold=True)

    box(ax, 5.6, 9.3, 3.8, 1.2,
        "ADMIN DASHBOARD  (React)\nstatistics · scans · flagged · model",
        fill=BLUE_FILL, edge=BLUE, bold=True)

    box(ax, 1.4, 4.6, 7.2, 3.5, "", fill="#fbfcfd", edge=BLUE)
    ax.text(5.0, 7.75, "BACKEND API   (Java 17, Spring Boot)", ha="center",
            fontsize=9.5, fontweight="bold", color=BLUE, **FONT)

    steps = [
        "1.  Google Safe Browsing lookup        (optional)",
        "2.  Rule engine: HTTPS, certificate, mixed content, address",
        "3.  Risk scorer  ->  ML service",
        "4.  Risk combiner: rules can only raise the score",
        "5.  Explainer  ->  Claude API, or built in template",
        "6.  Save the scan record",
    ]
    for i, s in enumerate(steps):
        ax.text(1.75, 7.25 - i * 0.44, s, fontsize=8.4, color=INK, va="center", **FONT)

    box(ax, 0.4, 2.5, 2.7, 1.1, "ML SERVICE\nPython, FastAPI\nhybrid classifier",
        fill=GREEN_FILL, edge=GREEN)
    box(ax, 3.65, 2.5, 2.7, 1.1, "DATABASE\nMongoDB\nscans, flagged", fill=GREY_FILL)
    box(ax, 6.9, 2.5, 2.7, 1.1, "EXTERNAL APIs\nGoogle Safe Browsing\nAnthropic Claude",
        fill=AMBER_FILL, edge=AMBER)

    arrow(ax, (2.8, 9.3), (3.4, 8.1), "POST /api/v1/scan", offset=0.18)
    arrow(ax, (7.5, 9.3), (6.8, 8.1), "GET /api/v1/admin/*", offset=0.18)
    arrow(ax, (2.6, 4.6), (1.9, 3.6), "score", offset=0.12)
    arrow(ax, (5.0, 4.6), (5.0, 3.6), "save", offset=0.12)
    arrow(ax, (7.4, 4.6), (8.1, 3.6), "lookup / explain", offset=0.12)

    ax.text(5.0, 1.9,
            "Every external service is optional at run time. If one is missing the\n"
            "system degrades gracefully and the user still receives a complete report.",
            ha="center", fontsize=8, color=MUTED, style="italic", **FONT)

    finish(fig, ax, "01-architecture.png", "Figure 4.1: WebSentry system architecture")


# ---------------------------------------------------------------------------
# 2. Use case
# ---------------------------------------------------------------------------
def use_case():
    fig, ax = plt.subplots(figsize=(9, 6.6))
    ax.set_ylim(0, 10)

    def actor(x, y, label):
        ax.add_patch(Ellipse((x, y + 0.62), 0.28, 0.34, fc="white", ec=INK, lw=1.3, zorder=3))
        ax.plot([x, x], [y + 0.45, y - 0.1], color=INK, lw=1.3, zorder=3)
        ax.plot([x - 0.3, x + 0.3], [y + 0.3, y + 0.3], color=INK, lw=1.3, zorder=3)
        ax.plot([x, x - 0.25], [y - 0.1, y - 0.55], color=INK, lw=1.3, zorder=3)
        ax.plot([x, x + 0.25], [y - 0.1, y - 0.55], color=INK, lw=1.3, zorder=3)
        ax.text(x, y - 0.9, label, ha="center", fontsize=9, fontweight="bold",
                color=INK, **FONT)

    def usecase(x, y, label, fill=BLUE_FILL):
        ax.add_patch(Ellipse((x, y), 3.5, 0.78, fc=fill, ec=BLUE, lw=1.1, zorder=2))
        ax.text(x, y, label, ha="center", va="center", fontsize=8.3, color=INK, **FONT)

    ax.add_patch(Rectangle((2.6, 0.7), 4.9, 8.5, fc="#fbfcfd", ec=LINE, lw=1.2, zorder=1))
    ax.text(5.05, 8.85, "WebSentry", ha="center", fontsize=9.5,
            fontweight="bold", color=MUTED, **FONT)

    actor(1.15, 6.3, "Internet user")
    actor(1.15, 2.6, "Administrator")

    user_cases = [
        (5.05, 8.1, "Have each website checked automatically"),
        (5.05, 7.15, "View the security report"),
        (5.05, 6.20, "Receive a warning about a dangerous site"),
        (5.05, 5.25, "View recently checked websites"),
    ]
    admin_cases = [
        (5.05, 4.05, "Sign in to the dashboard"),
        (5.05, 3.10, "View statistics and all scans"),
        (5.05, 2.15, "View the most flagged websites"),
        (5.05, 1.20, "View model performance"),
    ]
    for x, y, label in user_cases:
        usecase(x, y, label)
        arrow(ax, (1.6, 6.3), (3.35, y), style="-")
    for x, y, label in admin_cases:
        usecase(x, y, label, fill="#eef7f1")
        arrow(ax, (1.6, 2.6), (3.35, y), style="-")

    finish(fig, ax, "02-use-case.png", "Figure 4.2: Use case diagram")


# ---------------------------------------------------------------------------
# 3. Class diagram
# ---------------------------------------------------------------------------
def class_diagram():
    fig, ax = plt.subplots(figsize=(9.5, 7.4))
    ax.set_ylim(0, 11)

    def cls(x, y, w, name, members, stereotype=None, fill="white"):
        header = 0.52
        h = header + 0.32 * len(members) + 0.18
        ax.add_patch(Rectangle((x, y - h), w, h, fc=fill, ec=INK, lw=1.2, zorder=2))
        ax.plot([x, x + w], [y - header, y - header], color=INK, lw=1.0, zorder=3)
        label = f"«{stereotype}»\n{name}" if stereotype else name
        ax.text(x + w / 2, y - header / 2, label, ha="center", va="center",
                fontsize=8.6, fontweight="bold", color=INK, zorder=3, **FONT)
        for i, m in enumerate(members):
            ax.text(x + 0.12, y - header - 0.26 - i * 0.32, m, fontsize=7.4,
                    color=INK, va="center", zorder=3, **FONT)
        return h

    cls(3.5, 10.6, 3.0, "ScanController", ["+ scan()", "+ byId()", "+ history()"], fill=BLUE_FILL)
    cls(3.2, 9.0, 3.6, "ScanService",
        ["- scorer : RiskScorer", "- combiner : RiskCombiner", "+ scan(url, clientId)"], fill=BLUE_FILL)

    cls(0.2, 6.6, 2.7, "RuleEngine",
        ["+ check(url, hit, live)", "- checkAddressPatterns()", "- inspectLiveSite()"])
    cls(3.4, 6.6, 3.2, "RiskScorer", ["+ score(url)", "+ name()"],
        stereotype="interface", fill="#f5f1fb")
    cls(7.1, 6.6, 2.7, "RiskCombiner", ["+ combine(base, checks)", "+ appliedAdjustments()"])

    cls(2.4, 3.9, 2.2, "MlRiskScorer", ["+ score(url)"], fill=GREEN_FILL)
    cls(5.1, 3.9, 2.2, "LlmRiskScorer", ["+ score(url)"], fill=AMBER_FILL)

    cls(0.2, 2.6, 2.7, "Explainer", ["+ explain(...)"], stereotype="interface", fill="#f5f1fb")
    cls(0.2, 1.2, 2.7, "ClaudeExplainer", ["+ explain(...)", "- template(...)"])

    cls(7.1, 3.9, 2.7, "ScanStore", ["+ save()", "+ findAll()", "+ topFlagged()"],
        stereotype="interface", fill="#f5f1fb")
    cls(7.1, 1.9, 2.7, "MongoScanStore", ["+ save()"], fill=GREEN_FILL)
    cls(4.0, 1.4, 2.6, "ScanRecord",
        ["- riskScore : int", "- riskBand : String", "- checks : RuleChecks"])

    arrow(ax, (5.0, 9.55), (5.0, 9.0), style="-|>")
    arrow(ax, (3.6, 8.05), (2.0, 7.15), style="-")
    arrow(ax, (5.0, 8.05), (5.0, 7.15), style="-")
    arrow(ax, (6.5, 8.05), (8.0, 7.15), style="-")
    arrow(ax, (3.5, 5.6), (3.5, 4.5), style="-|>", dashed=True)
    arrow(ax, (6.2, 5.6), (6.2, 4.5), style="-|>", dashed=True)
    arrow(ax, (1.5, 2.15), (1.5, 1.75), style="-|>", dashed=True)
    arrow(ax, (8.4, 3.15), (8.4, 2.45), style="-|>", dashed=True)
    arrow(ax, (4.4, 8.05), (4.8, 2.3), style="-")

    ax.text(5.0, 0.45,
            "A dashed arrow means \"implements\". The two scorers and the two stores are\n"
            "interchangeable, which is what keeps the architecture decision reversible.",
            ha="center", fontsize=7.8, color=MUTED, style="italic", **FONT)

    finish(fig, ax, "03-class-diagram.png", "Figure 4.3: Class diagram of the backend")


# ---------------------------------------------------------------------------
# 4. Sequence diagram
# ---------------------------------------------------------------------------
def sequence():
    fig, ax = plt.subplots(figsize=(10, 7))
    ax.set_ylim(0, 11)

    actors = [
        (0.75, "User"),
        (2.35, "Extension"),
        (4.30, "Backend"),
        (6.10, "Safe\nBrowsing"),
        (7.55, "ML\nservice"),
        (8.85, "Claude"),
        (9.85, "Database"),
    ]
    for x, name in actors:
        box(ax, x - 0.62, 10.1, 1.24, 0.62, name, fill=BLUE_FILL, edge=BLUE, size=7.8, bold=True)
        ax.plot([x, x], [10.1, 0.9], color=LINE, lw=0.9, ls="--", zorder=0)

    X = {n.replace("\n", " "): x for x, n in actors}
    msgs = [
        (9.55, "User", "Extension", "opens a website"),
        (9.05, "Extension", "Backend", "POST /api/v1/scan"),
        (8.55, "Backend", "Safe Browsing", "is this address listed?"),
        (8.05, "Safe Browsing", "Backend", "no match", True),
        (7.55, "Backend", "Backend", "rule checks: HTTPS, certificate, address"),
        (6.85, "Backend", "ML service", "POST /predict"),
        (6.35, "ML service", "Backend", "score 0 to 100", True),
        (5.85, "Backend", "Backend", "combine: rules can only raise the score"),
        (5.15, "Backend", "Claude", "findings + score -> write explanation"),
        (4.65, "Claude", "Backend", "two or three plain sentences", True),
        (4.15, "Backend", "Database", "save the scan record"),
        (3.65, "Backend", "Extension", "full scan result", True),
        (3.15, "Extension", "User", "shows score, verdict, explanation"),
    ]

    for y, a, b, label, *dashed in msgs:
        d = bool(dashed and dashed[0])
        xa, xb = X[a], X[b]
        if a == b:
            ax.plot([xa, xa + 0.7, xa + 0.7, xa], [y, y, y - 0.32, y - 0.32],
                    color=MUTED, lw=1.0)
            ax.add_patch(FancyArrowPatch((xa + 0.15, y - 0.32), (xa, y - 0.32),
                                         arrowstyle="-|>", mutation_scale=10,
                                         color=MUTED, lw=1.0))
            ax.text(xa + 0.85, y - 0.16, label, fontsize=7.4, color=INK, va="center", **FONT)
        else:
            ax.add_patch(FancyArrowPatch((xa, y), (xb, y), arrowstyle="-|>",
                                         mutation_scale=10, color=MUTED, lw=1.0,
                                         linestyle="--" if d else "-"))
            ax.text((xa + xb) / 2, y + 0.14, label, ha="center", fontsize=7.4,
                    color=INK if not d else MUTED, **FONT)

    ax.text(5.0, 2.4,
            "A dashed arrow is a reply. If Safe Browsing, the ML service or Claude does not answer,\n"
            "the backend continues with what it has and the user still receives a complete report.",
            ha="center", fontsize=7.8, color=MUTED, style="italic", **FONT)

    finish(fig, ax, "04-sequence.png", "Figure 4.4: Sequence of one website scan")


architecture()
use_case()
class_diagram()
sequence()
print("\nAll four diagrams written to", OUT)
