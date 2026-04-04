"""Keyword extraction using spaCy NER + noun chunk analysis."""

import logging
from collections import Counter

import spacy

from config import settings

logger = logging.getLogger(__name__)

_nlp = None


def _get_nlp():
    global _nlp
    if _nlp is None:
        model = settings.spacy_model
        try:
            _nlp = spacy.load(model)
            logger.info("Loaded spaCy model: %s", model)
        except OSError:
            logger.warning("spaCy model %s not found, downloading...", model)
            spacy.cli.download(model)
            _nlp = spacy.load(model)
    return _nlp


async def extract_keywords(text: str) -> list[dict]:
    """Extract keywords from text using spaCy.

    Returns list of {"keyword": str, "frequency": int, "confidence": float}.
    """
    if not text or len(text.strip()) < 20:
        return []

    try:
        nlp = _get_nlp()

        # Process text (limit to first 100k chars for performance)
        doc = nlp(text[:100_000])

        # Collect named entities
        entity_counts: Counter = Counter()
        for ent in doc.ents:
            if ent.label_ in ("ORG", "PERSON", "GPE", "PRODUCT", "EVENT", "WORK_OF_ART", "LAW"):
                entity_counts[ent.text.strip()] += 1

        # Collect noun chunks (multi-word terms)
        chunk_counts: Counter = Counter()
        for chunk in doc.noun_chunks:
            # Filter out pronouns and very short chunks
            clean = chunk.text.strip().lower()
            if len(clean) > 2 and chunk.root.pos_ in ("NOUN", "PROPN"):
                chunk_counts[clean] += 1

        # Merge and rank
        all_terms: Counter = Counter()
        for term, count in entity_counts.items():
            all_terms[term] += count * 2  # Boost named entities
        for term, count in chunk_counts.items():
            all_terms[term] += count

        # Take top N
        max_kw = settings.max_keywords
        top_terms = all_terms.most_common(max_kw)

        if not top_terms:
            return []

        max_score = top_terms[0][1]
        results = []
        for term, count in top_terms:
            confidence = round(min(0.99, count / max_score), 2)
            results.append({
                "keyword": term,
                "frequency": count,
                "confidence": confidence,
            })

        logger.info("Extracted %d keywords from %d chars", len(results), len(text))
        return results

    except Exception:
        logger.exception("Keyword extraction failed")
        return []
