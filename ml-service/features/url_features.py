"""Hostname feature extraction for WebSentry."""

from __future__ import annotations

import math
import re
from collections import Counter
from urllib.parse import urlparse

# Order defines the lexical feature vector layout.
FEATURE_NAMES = [
    "hostname_length",
    "registrable_domain_length",
    "num_dots",
    "num_hyphens",
    "num_digits",
    "digit_ratio",
    "has_ip_host",
    "subdomain_count",
    "tld_length",
    "is_abused_tld",
    "is_url_shortener",
    "has_punycode",
    "hostname_entropy",
    "num_suspicious_keywords",
    "brand_outside_domain",
    "longest_token_length",
    "num_tokens",
    "max_consonant_run",
]

# Readable labels, shown when a feature is named to the user.
FEATURE_LABELS = {
    "hostname_length": "unusually long web address",
    "registrable_domain_length": "unusually long domain name",
    "num_dots": "many dots in the address",
    "num_hyphens": "many hyphens in the address",
    "num_digits": "numbers used inside the domain name",
    "digit_ratio": "high proportion of digits in the address",
    "has_ip_host": "uses a raw IP address instead of a domain name",
    "subdomain_count": "unusually many subdomains",
    "tld_length": "unusual domain ending",
    "is_abused_tld": "domain ending commonly used by scam sites",
    "is_url_shortener": "hidden behind a link shortener",
    "has_punycode": "uses lookalike foreign characters in the address",
    "hostname_entropy": "randomly generated looking domain name",
    "num_suspicious_keywords": "contains words often used in scams, such as login or verify",
    "brand_outside_domain": "a well known brand name appears in the wrong part of the address",
    "longest_token_length": "very long unbroken word in the address",
    "num_tokens": "address broken into many parts",
    "max_consonant_run": "unpronounceable run of letters, typical of generated domains",
}

# The smallest value a feature must reach before its label may be shown to a user.
NAMING_THRESHOLDS = {
    "hostname_length": 25,
    "registrable_domain_length": 20,
    "num_dots": 4,
    "num_hyphens": 3,
    "num_digits": 4,
    "digit_ratio": 0.2,
    "has_ip_host": 1,
    "subdomain_count": 3,
    "tld_length": 5,
    "is_abused_tld": 1,
    "is_url_shortener": 1,
    "has_punycode": 1,
    "hostname_entropy": 3.5,
    "num_suspicious_keywords": 1,
    "brand_outside_domain": 1,
    "longest_token_length": 12,
    "num_tokens": 5,
    "max_consonant_run": 5,
}


def is_worth_naming(feature: str, value: float) -> bool:
    """True if the feature is large enough for its label to be honest."""
    return value >= NAMING_THRESHOLDS.get(feature, 1)

# Cheap or frequently abused top level domains.
ABUSED_TLDS = {
    "tk", "ml", "ga", "cf", "gq", "xyz", "top", "work", "click", "link",
    "loan", "download", "racing", "win", "review", "country", "stream",
    "gdn", "mom", "party", "date", "faith", "cricket", "science", "accountant",
    "buzz", "rest", "cyou", "sbs", "icu", "monster", "quest", "cam", "surf",
    "zip", "mov", "kim", "bar", "casa", "fit", "beauty", "hair", "skin",
}

URL_SHORTENERS = {
    "bit.ly", "tinyurl.com", "goo.gl", "t.co", "ow.ly", "is.gd", "buff.ly",
    "adf.ly", "bit.do", "cutt.ly", "rebrand.ly", "shorte.st", "rb.gy",
    "tiny.cc", "shorturl.at", "s.id", "t.ly", "short.io",
}

SUSPICIOUS_KEYWORDS = [
    "login", "signin", "verify", "verification", "secure", "security",
    "account", "update", "confirm", "banking", "bank", "password", "credential",
    "webscr", "recover", "unlock", "suspended", "billing", "invoice", "payment",
    "wallet", "authenticate", "session", "support", "alert", "limited",
]

# Brands, flagged only when they appear outside the real domain.
BRANDS = [
    "paypal", "apple", "microsoft", "google", "amazon", "facebook", "netflix",
    "instagram", "whatsapp", "linkedin", "dropbox", "outlook", "office365",
    "icloud", "chase", "wellsfargo", "hsbc", "barclays", "santander", "dhl",
    "fedex", "ups", "binance", "coinbase", "steam", "roblox", "nationwide",
    "lloyds", "natwest", "revolut", "monzo",
]

# Two part endings such as co.uk, so the real domain can be found offline.
MULTI_PART_SUFFIXES = {
    "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk", "sch.uk",
    "com.au", "net.au", "org.au", "edu.au", "gov.au",
    "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
    "co.nz", "net.nz", "org.nz", "govt.nz",
    "co.za", "org.za", "web.za",
    "com.br", "net.br", "org.br", "gov.br",
    "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
    "co.in", "net.in", "org.in", "gov.in", "ac.in",
    "com.mx", "com.ar", "com.tr", "com.sg", "com.hk", "com.tw", "com.my",
    "com.pk", "com.ph", "com.vn", "co.kr", "or.kr", "co.id", "com.ru",
    "co.il", "com.eg", "com.sa", "com.ua", "com.pl", "com.es", "com.pt",
    "com.lk", "org.lk", "gov.lk", "ac.lk",
}

_IPV4_RE = re.compile(r"^\d{1,3}(?:\.\d{1,3}){3}$")
_TOKEN_SPLIT_RE = re.compile(r"[^a-z0-9]+")
_VOWELS = set("aeiou")


def _shannon_entropy(text: str) -> float:
    """Character level Shannon entropy."""
    if not text:
        return 0.0
    counts = Counter(text)
    total = len(text)
    return -sum((c / total) * math.log2(c / total) for c in counts.values())


def _max_consonant_run(text: str) -> int:
    """Longest run of consecutive consonants."""
    best = run = 0
    for ch in text:
        if ch.isalpha() and ch not in _VOWELS:
            run += 1
            best = max(best, run)
        else:
            run = 0
    return best


def _normalise(url: str) -> str:
    """Ensure a scheme is present so urlparse populates netloc."""
    url = (url or "").strip()
    if not url:
        return ""
    if "://" not in url:
        url = "http://" + url
    return url


def _registrable_domain(hostname: str) -> str:
    """Best effort registrable domain (eTLD+1) without a network lookup."""
    parts = hostname.split(".")
    if len(parts) <= 2:
        return hostname
    last_two = ".".join(parts[-2:])
    if last_two in MULTI_PART_SUFFIXES and len(parts) >= 3:
        return ".".join(parts[-3:])
    return last_two


def hostname_of(url: str) -> str:
    """Lowercase hostname for a URL, or an empty string if unparseable."""
    try:
        return (urlparse(_normalise(url)).hostname or "").lower()
    except ValueError:
        return ""


def extract_features(url: str) -> dict[str, float]:
    """Return the lexical feature dictionary for a URL's hostname."""
    host = hostname_of(url)
    if not host:
        return {name: 0.0 for name in FEATURE_NAMES}

    registrable = _registrable_domain(host)
    subdomain_part = (
        host[: -len(registrable)].rstrip(".") if host.endswith(registrable) else ""
    )
    tld = host.rsplit(".", 1)[-1] if "." in host else ""
    digits = sum(ch.isdigit() for ch in host)
    tokens = [t for t in _TOKEN_SPLIT_RE.split(host) if t]

    return {
        "hostname_length": float(len(host)),
        "registrable_domain_length": float(len(registrable)),
        "num_dots": float(host.count(".")),
        "num_hyphens": float(host.count("-")),
        "num_digits": float(digits),
        "digit_ratio": float(digits / len(host)),
        "has_ip_host": 1.0 if _IPV4_RE.match(host) else 0.0,
        "subdomain_count": float(len([p for p in subdomain_part.split(".") if p])),
        "tld_length": float(len(tld)),
        "is_abused_tld": 1.0 if tld in ABUSED_TLDS else 0.0,
        "is_url_shortener": 1.0 if registrable in URL_SHORTENERS else 0.0,
        "has_punycode": 1.0 if "xn--" in host else 0.0,
        "hostname_entropy": _shannon_entropy(host),
        "num_suspicious_keywords": float(sum(kw in host for kw in SUSPICIOUS_KEYWORDS)),
        "brand_outside_domain": 1.0 if any(b in subdomain_part for b in BRANDS) else 0.0,
        "longest_token_length": float(max((len(t) for t in tokens), default=0)),
        "num_tokens": float(len(tokens)),
        "max_consonant_run": float(_max_consonant_run(host)),
    }


def extract_vector(url: str) -> list[float]:
    """Feature values in FEATURE_NAMES order."""
    feats = extract_features(url)
    return [feats[name] for name in FEATURE_NAMES]
