"""
Structured metadata extraction via spaCy NER (Pass 5.3.0).

Produces a flat JSON object whose keys follow the published metadata
schema. The output powers conditional workflow step evaluation (Pass 5.3)
via JSON Logic expressions.

## Extracted fields

| Key              | Type       | spaCy source      | Notes                                |
|------------------|------------|-------------------|--------------------------------------|
| amounts          | number[]   | MONEY             | Numeric values only, no currency sym |
| currency         | string     | MONEY             | Most-frequent currency symbol/code   |
| effective_date   | string     | DATE              | ISO 8601 (best effort)               |
| parties          | string[]   | ORG + PERSON       | Deduplicated, title-cased            |
| jurisdiction     | string     | GPE               | Most-frequent geopolitical entity    |
| document_number  | string     | regex             | DOC-\\d+, INV-\\d+, PO-\\d+ patterns  |

## Design decisions

- **Best-effort, not perfect.** NER on business documents is noisy.
  We extract what we can and let missing fields be null — JSON Logic
  treats missing keys as null, which conditions can test for.
- **Deterministic.** Same text in = same metadata out. No LLM calls
  here — pure spaCy + regex so the output is reproducible and fast.
- **Flat structure.** No nesting. The workflow condition builder
  (Pass 5.3 UI) iterates top-level keys; nested structures would
  complicate both the builder and the JSON Logic expressions.
"""

import logging
import re
from collections import Counter
from typing import Any

import spacy

from config import settings

logger = logging.getLogger(__name__)

# Lazy-load the spaCy model — it's ~50MB and we only need it when
# this pipeline actually runs (not on every sidecar startup).
_nlp = None


def _get_nlp():
    global _nlp
    if _nlp is None:
        model = settings.spacy_model
        logger.info("Loading spaCy model '%s' for metadata extraction", model)
        _nlp = spacy.load(model)
    return _nlp


# Regex patterns for document numbers — common enterprise formats.
# Case-insensitive, anchored to word boundaries.
DOC_NUMBER_PATTERNS = [
    re.compile(r"\b(DOC-\d{3,})\b", re.IGNORECASE),
    re.compile(r"\b(INV-\d{3,})\b", re.IGNORECASE),
    re.compile(r"\b(PO-\d{3,})\b", re.IGNORECASE),
    re.compile(r"\b(REF-\d{3,})\b", re.IGNORECASE),
    re.compile(r"\b(CASE-\d{3,})\b", re.IGNORECASE),
    re.compile(r"\b(CONTRACT-\d{3,})\b", re.IGNORECASE),
]

# Common currency symbols/codes to extract from MONEY entities.
CURRENCY_SYMBOLS = {
    "$": "USD", "£": "GBP", "€": "EUR", "¥": "JPY",
    "USD": "USD", "GBP": "GBP", "EUR": "EUR", "JPY": "JPY",
    "CHF": "CHF", "AUD": "AUD", "CAD": "CAD", "NZD": "NZD",
    "SEK": "SEK", "NOK": "NOK", "DKK": "DKK",
}


async def extract_metadata(text: str) -> dict[str, Any]:
    """
    Extract structured metadata from document text.

    Returns a flat dict whose keys match the published metadata schema.
    Missing fields are omitted (not set to null) so the JSON stays
    compact and JSON Logic's `{"var": "field"}` returns null naturally
    for absent keys.
    """
    if not text or len(text.strip()) < 20:
        return {}

    nlp = _get_nlp()

    # spaCy has a max length guard — truncate very long docs to the
    # first 100k chars. NER quality doesn't improve much past that
    # and the processing time is linear.
    truncated = text[:100_000] if len(text) > 100_000 else text
    doc = nlp(truncated)

    result: dict[str, Any] = {}

    # ── MONEY → amounts + currency ────────────────────────────
    amounts: list[float] = []
    currency_counts: Counter[str] = Counter()
    for ent in doc.ents:
        if ent.label_ == "MONEY":
            parsed = _parse_money(ent.text)
            if parsed:
                amount, currency = parsed
                amounts.append(amount)
                if currency:
                    currency_counts[currency] += 1

    if amounts:
        result["amounts"] = sorted(set(amounts), reverse=True)
    if currency_counts:
        result["currency"] = currency_counts.most_common(1)[0][0]

    # ── DATE → effective_date ─────────────────────────────────
    dates: list[str] = []
    for ent in doc.ents:
        if ent.label_ == "DATE":
            # Try to normalise to ISO 8601. spaCy's DATE entities are
            # free-form text ("January 15, 2026", "next Tuesday", etc.).
            # We keep the first one that looks like a real date.
            normalised = _normalise_date(ent.text)
            if normalised:
                dates.append(normalised)

    if dates:
        result["effective_date"] = dates[0]  # earliest plausible date

    # ── ORG + PERSON → parties ────────────────────────────────
    parties: set[str] = set()
    for ent in doc.ents:
        if ent.label_ in ("ORG", "PERSON"):
            cleaned = ent.text.strip()
            if len(cleaned) > 1 and cleaned not in _NOISE_ENTITIES:
                parties.add(cleaned.title() if cleaned.isupper() else cleaned)

    if parties:
        result["parties"] = sorted(parties)[:20]  # cap at 20

    # ── GPE → jurisdiction ────────────────────────────────────
    gpe_counts: Counter[str] = Counter()
    for ent in doc.ents:
        if ent.label_ == "GPE":
            cleaned = ent.text.strip()
            if len(cleaned) > 1:
                gpe_counts[cleaned] += 1

    if gpe_counts:
        result["jurisdiction"] = gpe_counts.most_common(1)[0][0]

    # ── Regex → document_number ───────────────────────────────
    for pattern in DOC_NUMBER_PATTERNS:
        match = pattern.search(text)
        if match:
            result["document_number"] = match.group(1).upper()
            break

    logger.info(
        "Extracted metadata: %d field(s) (%s)",
        len(result),
        ", ".join(result.keys()) if result else "none",
    )
    return result


# ── Helpers ──────────────────────────────────────────────────

# Entities that spaCy commonly misclassifies from boilerplate text.
# Filter them out to reduce noise in the parties list.
_NOISE_ENTITIES = {
    "The", "This", "That", "Section", "Article", "Clause",
    "Page", "Table", "Figure", "Appendix", "Exhibit",
}


def _parse_money(text: str) -> tuple[float, str | None] | None:
    """
    Extract a numeric amount and optional currency from a MONEY entity.
    Returns (amount, currency_code) or None if unparseable.
    """
    # Strip whitespace and common separators
    cleaned = text.strip()

    # Try to find a currency symbol/code
    currency = None
    for sym, code in CURRENCY_SYMBOLS.items():
        if sym in cleaned:
            currency = code
            cleaned = cleaned.replace(sym, "")
            break

    # Extract the numeric part
    # Handle formats: "10,000.50", "10.000,50" (European), "10000"
    cleaned = cleaned.strip().replace(" ", "")

    # Determine if comma is thousands separator or decimal
    if "," in cleaned and "." in cleaned:
        # Both present — comma before dot = English, dot before comma = European
        if cleaned.rindex(",") < cleaned.rindex("."):
            cleaned = cleaned.replace(",", "")  # English: 10,000.50
        else:
            cleaned = cleaned.replace(".", "").replace(",", ".")  # Euro: 10.000,50
    elif "," in cleaned and "." not in cleaned:
        # Comma only — if 3 digits after comma, it's thousands; else decimal
        parts = cleaned.split(",")
        if len(parts[-1]) == 3:
            cleaned = cleaned.replace(",", "")  # 10,000
        else:
            cleaned = cleaned.replace(",", ".")  # 10,5

    try:
        return (float(cleaned), currency)
    except ValueError:
        return None


def _normalise_date(text: str) -> str | None:
    """
    Best-effort normalisation of a date entity to ISO 8601 (YYYY-MM-DD).
    Returns None for relative dates ("next week", "yesterday") and
    unparseable strings.
    """
    import dateutil.parser as dp

    # Skip relative/vague dates
    lower = text.lower().strip()
    skip_patterns = {"today", "yesterday", "tomorrow", "last", "next", "ago", "recent"}
    if any(p in lower for p in skip_patterns):
        return None

    try:
        parsed = dp.parse(text, fuzzy=True)
        return parsed.strftime("%Y-%m-%d")
    except (ValueError, OverflowError):
        return None
