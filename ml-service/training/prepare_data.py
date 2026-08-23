"""Build the WebSentry corpus and freeze the evaluation set."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "ml-service"))

from features.url_features import hostname_of  # noqa: E402

RAW = REPO_ROOT / "evaluation" / "datasets" / "raw"
SOURCE_CSV = RAW / "phiusiil" / "PhiUSIIL_Phishing_URL_Dataset.csv"
TOPSITES_CSV = RAW / "umbrella" / "top-1m.csv"
EVAL_CSV = REPO_ROOT / "evaluation" / "datasets" / "eval_set_400.csv"
MANIFEST = REPO_ROOT / "evaluation" / "datasets" / "eval_set_400.manifest.json"
TRAIN_POOL = REPO_ROOT / "ml-service" / "training" / "train_pool.csv"

RANDOM_SEED = 20360306
N_PER_CLASS = 200

# Hostnames ranked this highly are treated as legitimate infrastructure.
TOPSITE_EXCLUDE_N = 200_000
# Domains added to the legitimate class.
LEGIT_AUGMENT_N = 100_000


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="Build corpus and freeze the evaluation set.")
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite an existing frozen evaluation set. Invalidates prior results.",
    )
    args = parser.parse_args()

    if EVAL_CSV.exists() and not args.force:
        print(f"Evaluation set already frozen at {EVAL_CSV}")
        print("Refusing to regenerate. Pass --force only if you intend to invalidate")
        print("every accuracy figure produced so far.")
        return 1

    for path in (SOURCE_CSV, TOPSITES_CSV):
        if not path.exists():
            print(f"Missing input: {path}", file=sys.stderr)
            return 2

    print(f"Reading {SOURCE_CSV.name} ...")
    df = pd.read_csv(SOURCE_CSV, usecols=["URL", "label"])
    # PhiUSIIL encodes 1 = legitimate, 0 = phishing.
    df["is_phishing"] = (df["label"] == 0).astype(int)
    df = df[["URL", "is_phishing"]].rename(columns={"URL": "url"})
    df = df.dropna(subset=["url"]).drop_duplicates(subset="url")
    df["host"] = [hostname_of(u) for u in df.url]
    df = df[df.host != ""]
    print(f"  {len(df):,} rows  ({int(df.is_phishing.sum()):,} phishing, "
          f"{int((1 - df.is_phishing).sum()):,} legitimate)")

    print(f"Reading {TOPSITES_CSV.name} ...")
    top = pd.read_csv(TOPSITES_CSV, names=["rank", "domain"])
    top["domain"] = top.domain.str.lower().str.strip()
    exclude_hosts = set(top.head(TOPSITE_EXCLUDE_N).domain)
    print(f"  top {TOPSITE_EXCLUDE_N:,} hostnames loaded for conflict removal")

    # --- 1.
    conflict = (df.is_phishing == 1) & (df.host.isin(exclude_hosts))
    removed = int(conflict.sum())
    df = df[~conflict]
    print(f"\nCLEAN: removed {removed:,} phishing rows hosted on top-ranked legitimate hostnames")

    # --- 2.
    existing = set(df.host)
    augment = [
        d for d in top.head(LEGIT_AUGMENT_N).domain
        if d and d not in existing and "." in d
    ]
    aug_df = pd.DataFrame(
        {
            "url": [f"https://{d}" for d in augment],
            "is_phishing": 0,
            "host": augment,
        }
    )
    df = pd.concat([df, aug_df], ignore_index=True)
    print(f"AUGMENT: added {len(aug_df):,} top-ranked domains to the legitimate class")

    # Guard: no hostname may carry both labels.
    both = df.groupby("host").is_phishing.nunique()
    ambiguous = set(both[both > 1].index)
    if ambiguous:
        df = df[~df.host.isin(ambiguous)]
        print(f"GUARD:   dropped {len(ambiguous):,} hostnames carrying both labels")

    print(f"\nFinal corpus: {len(df):,} rows "
          f"({int(df.is_phishing.sum()):,} phishing, {int((1 - df.is_phishing).sum()):,} legitimate)")

    # --- 3.
    phishing = df[df.is_phishing == 1]
    legitimate = df[df.is_phishing == 0]
    if len(phishing) < N_PER_CLASS or len(legitimate) < N_PER_CLASS:
        print("Not enough samples in one of the classes.", file=sys.stderr)
        return 3

    eval_set = (
        pd.concat(
            [
                phishing.sample(n=N_PER_CLASS, random_state=RANDOM_SEED),
                legitimate.sample(n=N_PER_CLASS, random_state=RANDOM_SEED),
            ]
        )
        .sample(frac=1.0, random_state=RANDOM_SEED)
        .reset_index(drop=True)
    )
    eval_set["source"] = "PhiUSIIL (UCI ID 967) + Cisco Umbrella top-1m"

    # Hold out by HOSTNAME, not just URL.
    held_out_hosts = set(eval_set["host"])
    train_pool = df[~df.host.isin(held_out_hosts)].reset_index(drop=True)
    print(f"\nHeld out {len(held_out_hosts):,} hostnames from training "
          f"(hostname-level split, not URL-level)")

    EVAL_CSV.parent.mkdir(parents=True, exist_ok=True)
    TRAIN_POOL.parent.mkdir(parents=True, exist_ok=True)
    eval_set[["url", "host", "is_phishing", "source"]].to_csv(EVAL_CSV, index=False)
    train_pool[["url", "host", "is_phishing"]].to_csv(TRAIN_POOL, index=False)

    manifest = {
        "frozen_at": datetime.now(timezone.utc).isoformat(),
        "random_seed": RANDOM_SEED,
        "eval_rows": len(eval_set),
        "eval_phishing": int(eval_set.is_phishing.sum()),
        "eval_legitimate": int((1 - eval_set.is_phishing).sum()),
        "train_pool_rows": len(train_pool),
        "train_pool_phishing": int(train_pool.is_phishing.sum()),
        "train_pool_legitimate": int((1 - train_pool.is_phishing).sum()),
        "cleaning": {
            "phishing_rows_removed_on_topsite_hosts": removed,
            "topsite_exclude_n": TOPSITE_EXCLUDE_N,
            "legit_domains_added": len(aug_df),
            "ambiguous_hostnames_dropped": len(ambiguous),
        },
        "split_strategy": "hostname-level holdout (no host appears in both sets)",
        "sources": [
            {
                "name": "PhiUSIIL Phishing URL Dataset",
                "citation": (
                    "Prasad, A. and Chandra, S. (2024) 'PhiUSIIL: a diverse security "
                    "profile empowered phishing URL detection framework based on "
                    "similarity index and incremental learning', Computers and "
                    "Security, 136, 103545."
                ),
                "sha256": sha256_of(SOURCE_CSV),
            },
            {
                "name": "Cisco Umbrella Popularity List (top 1 million)",
                "citation": (
                    "Cisco Umbrella (2026) Umbrella Popularity List. Available at: "
                    "https://s3-us-west-1.amazonaws.com/umbrella-static/index.html"
                ),
                "sha256": sha256_of(TOPSITES_CSV),
            },
        ],
        "eval_sha256": sha256_of(EVAL_CSV),
    }
    MANIFEST.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    print(f"\nFrozen evaluation set: {EVAL_CSV}")
    print(f"  {manifest['eval_phishing']} phishing / {manifest['eval_legitimate']} legitimate")
    print(f"  sha256 {manifest['eval_sha256'][:32]}...")
    print(f"Training pool:         {TRAIN_POOL}  ({manifest['train_pool_rows']:,} rows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
