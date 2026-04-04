"""Taxonomy classifier — matches keywords against existing taxonomy terms.

For each extracted keyword:
1. Check for exact/fuzzy match against existing taxonomy terms
2. If matched → return classification (document ↔ term link)
3. If no match → create a CANDIDATE term for admin review
"""

import json
import logging
from difflib import SequenceMatcher

import httpx
from langchain_core.messages import HumanMessage, SystemMessage

from config import settings
from providers.llm_provider import get_llm

logger = logging.getLogger(__name__)

_taxonomy_cache: list[dict] | None = None


async def load_taxonomy() -> list[dict]:
    """Fetch all taxonomy terms from the backend API."""
    global _taxonomy_cache
    if _taxonomy_cache is not None:
        return _taxonomy_cache

    try:
        # Get admin token
        async with httpx.AsyncClient() as client:
            token_res = await client.post(
                "http://localhost:8180/realms/kosha/protocol/openid-connect/token",
                data={
                    "client_id": "kosha-web",
                    "username": "admin@kosha.dev",
                    "password": "admin",
                    "grant_type": "password",
                },
            )
            if token_res.status_code != 200:
                logger.warning("Could not get token for taxonomy fetch")
                return []

            token = token_res.json().get("access_token", "")

            terms_res = await client.get(
                f"{settings.kosha_api_url}/api/v1/taxonomy/terms",
                headers={"Authorization": f"Bearer {token}"},
            )
            if terms_res.status_code != 200:
                logger.warning("Could not fetch taxonomy: %s", terms_res.status_code)
                return []

            data = terms_res.json().get("data", [])
            _taxonomy_cache = data
            logger.info("Loaded %d taxonomy terms", len(data))
            return data

    except Exception:
        logger.exception("Failed to load taxonomy")
        return []


def invalidate_cache():
    """Clear the taxonomy cache (call after creating new terms)."""
    global _taxonomy_cache
    _taxonomy_cache = None


def _normalize(text: str) -> str:
    """Normalize text for matching."""
    return text.lower().strip()


def _similarity(a: str, b: str) -> float:
    """Compute similarity ratio between two strings."""
    return SequenceMatcher(None, _normalize(a), _normalize(b)).ratio()


async def classify_keywords(
    keywords: list[dict],
    document_id: str,
) -> tuple[list[dict], list[dict]]:
    """Classify keywords against taxonomy.

    Returns:
        (classifications, candidates)
        - classifications: list of {documentId, termId, confidence, source}
        - candidates: list of {label, description, source} for new CANDIDATE terms
    """
    taxonomy_terms = await load_taxonomy()
    if not keywords:
        return [], []

    # Build lookup of normalized labels → term data
    term_lookup: dict[str, dict] = {}
    for term in taxonomy_terms:
        norm = _normalize(term.get("label", ""))
        if norm:
            term_lookup[norm] = term
        # Also index normalized_label
        norm2 = _normalize(term.get("normalizedLabel", ""))
        if norm2:
            term_lookup[norm2] = term

    classifications: list[dict] = []
    candidates: list[dict] = []
    seen_terms: set[str] = set()
    seen_candidates: set[str] = set()

    # Minimum confidence for keyword to be considered for taxonomy
    min_keyword_confidence = 0.3
    # Minimum similarity for fuzzy matching
    min_similarity = 0.75

    for kw in keywords:
        keyword = kw.get("keyword", "")
        kw_confidence = kw.get("confidence", 0.0)

        if not keyword or kw_confidence < min_keyword_confidence:
            continue

        norm_kw = _normalize(keyword)

        # 1. Exact match
        if norm_kw in term_lookup:
            term = term_lookup[norm_kw]
            term_id = term.get("id", "")
            if term_id and term_id not in seen_terms:
                classifications.append({
                    "documentId": document_id,
                    "termId": term_id,
                    "confidence": round(kw_confidence, 2),
                    "source": "AI",
                })
                seen_terms.add(term_id)
                logger.debug("Exact match: '%s' → term '%s'", keyword, term.get("label"))
            continue

        # 2. Fuzzy match
        best_match: dict | None = None
        best_score = 0.0
        for norm_label, term in term_lookup.items():
            score = _similarity(norm_kw, norm_label)
            if score > best_score:
                best_score = score
                best_match = term

        if best_match and best_score >= min_similarity:
            term_id = best_match.get("id", "")
            if term_id and term_id not in seen_terms:
                classifications.append({
                    "documentId": document_id,
                    "termId": term_id,
                    "confidence": round(best_score * kw_confidence, 2),
                    "source": "AI",
                })
                seen_terms.add(term_id)
                logger.debug(
                    "Fuzzy match: '%s' → '%s' (score=%.2f)",
                    keyword, best_match.get("label"), best_score,
                )
            continue

        # 3. No match → candidate for taxonomy extension
        if norm_kw not in seen_candidates and len(keyword) > 2:
            candidates.append({
                "label": keyword,
                "frequency": kw.get("frequency", 0),
            })
            seen_candidates.add(norm_kw)

    # Generate proper definitions for candidates using the LLM
    if candidates:
        candidates = await _generate_definitions(candidates)

    logger.info(
        "Classification result: %d matches, %d candidates from %d keywords",
        len(classifications), len(candidates), len(keywords),
    )
    return classifications, candidates


async def _generate_definitions(candidates: list[dict]) -> list[dict]:
    """Use the LLM to generate proper definitions for candidate taxonomy terms."""
    terms = [c["label"] for c in candidates]
    labels_str = "\n".join(f"- {t}" for t in terms)

    try:
        llm = get_llm()
        messages = [
            SystemMessage(content=(
                "You are a taxonomy specialist for an enterprise knowledge management system. "
                "For each term below, provide a concise definition (1-2 sentences) suitable for a "
                "taxonomy/glossary entry. For acronyms, expand them first then define. "
                "Return valid JSON: an array of objects with 'label' and 'description' fields. "
                "Return ONLY the JSON array, no other text."
            )),
            HumanMessage(content=f"Define these terms:\n{labels_str}"),
        ]
        response = await llm.ainvoke(messages)
        content = response.content.strip()

        # Parse JSON from response (handle markdown code blocks)
        if content.startswith("```"):
            content = content.split("\n", 1)[1].rsplit("```", 1)[0].strip()

        definitions = json.loads(content)

        # Build lookup
        def_lookup: dict[str, str] = {}
        for d in definitions:
            def_lookup[d.get("label", "").lower().strip()] = d.get("description", "")

        # Merge definitions back into candidates
        result = []
        for c in candidates:
            label = c["label"]
            desc = def_lookup.get(label.lower().strip(), "")
            if not desc:
                desc = f"Term extracted from document analysis (frequency: {c.get('frequency', 0)})."
            result.append({
                "label": label,
                "description": desc,
                "source": "AI_GENERATED",
            })

        logger.info("Generated definitions for %d candidate terms", len(result))
        return result

    except Exception:
        logger.exception("Failed to generate definitions, using fallback descriptions")
        return [
            {
                "label": c["label"],
                "description": f"Term extracted from document analysis (frequency: {c.get('frequency', 0)}).",
                "source": "AI_GENERATED",
            }
            for c in candidates
        ]
