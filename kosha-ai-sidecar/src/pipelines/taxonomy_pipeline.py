"""LLM-native taxonomy pipeline.

Replaces the previous spaCy NER + ``difflib.SequenceMatcher`` approach with
two LLM calls per document:

Stage 1 — extract & classify
    The LLM is given the document text plus the current ACTIVE taxonomy and
    returns three lists at once:
      * ``keywords``       — domain terms surfaced from the document
      * ``classifications``— keywords that map to an existing taxonomy term,
                             with confidence and quoted evidence
      * ``unmatched``      — keywords with no good fit in the existing taxonomy

Stage 2 — propose-new (only if stage 1 produced ``unmatched`` items)
    The LLM defines each unmatched keyword as a candidate taxonomy term.
    Suggested parent/related terms will be added in a follow-up once the
    schema supports them; today candidates land in ``tax.taxonomy_term`` with
    ``status='CANDIDATE'`` for admin review.

Routes through ``providers.llm_provider.get_llm()`` so the configured provider
(Ollama+gemma4 by default, Anthropic, or OpenAI) drives both stages — there is
no spaCy fallback. If the LLM is unreachable a ``LlmUnavailableError`` exception is
raised so the NATS handler can nak the message for re-delivery.
"""

import json
import logging
from typing import Any

import httpx
from langchain_core.messages import HumanMessage, SystemMessage

from config import settings
from providers.llm_provider import get_llm

logger = logging.getLogger(__name__)


class LlmUnavailableError(RuntimeError):
    """Raised when the configured LLM cannot be reached. Triggers NATS nak."""


# ---------------------------------------------------------------------------
# Taxonomy loading
# ---------------------------------------------------------------------------

_taxonomy_cache: list[dict] | None = None


async def load_active_taxonomy() -> list[dict]:
    """Fetch ACTIVE taxonomy terms from the backend.

    CANDIDATE-status terms are deliberately excluded — they're proposals not
    yet approved by an admin, so we don't want them polluting fresh
    classifications and creating the runaway-self-citation effect that bit
    the previous fuzzy-match pipeline.
    """
    global _taxonomy_cache
    if _taxonomy_cache is not None:
        return _taxonomy_cache

    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            # Service-account client_credentials grant. We deliberately do NOT
            # impersonate the seed admin user here — that pattern broke as soon
            # as the bootstrap step rotated the seed password.
            token_res = await client.post(
                f"{settings.keycloak_url.rstrip('/')}/realms/{settings.keycloak_realm}/protocol/openid-connect/token",
                data={
                    "client_id": settings.backend_client_id,
                    "client_secret": settings.backend_client_secret,
                    "grant_type": "client_credentials",
                },
            )
            if token_res.status_code != 200:
                logger.warning(
                    "Could not get service-account token for taxonomy fetch: %s",
                    token_res.status_code,
                )
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
            active = [t for t in data if t.get("status", "ACTIVE") == "ACTIVE"]
            _taxonomy_cache = active
            logger.info("Loaded %d ACTIVE taxonomy terms", len(active))
            return active

    except Exception:
        logger.exception("Failed to load taxonomy")
        return []


def invalidate_cache() -> None:
    """Clear the taxonomy cache. Call after creating new terms."""
    global _taxonomy_cache
    _taxonomy_cache = None


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Documents are summarised at 50K chars upstream; the taxonomy pipeline can
# accept slightly more raw text since it doesn't have to fit a 300-word
# summary in the same response. 60K chars ≈ 15K tokens, which leaves room
# inside the default num_ctx=16384 for the prompt scaffolding plus a
# JSON response of a few hundred tokens.
_MAX_DOC_CHARS = 60_000


def _strip_code_fences(content: str) -> str:
    """Strip ```json ... ``` fences if the LLM emitted them."""
    s = content.strip()
    if s.startswith("```"):
        # Drop the opening fence line and the trailing ``` line.
        first_nl = s.find("\n")
        if first_nl >= 0:
            s = s[first_nl + 1 :]
        last_fence = s.rfind("```")
        if last_fence >= 0:
            s = s[:last_fence]
    return s.strip()


async def _call_llm_json(system: str, user: str, *, max_predict_hint: int = 1500) -> Any:
    """Call the configured LLM and parse its response as JSON.

    Raises:
        LlmUnavailableError: connection refused / timeout / 5xx
        ValueError: 200 response but unparseable JSON
    """
    try:
        llm = get_llm()
        response = await llm.ainvoke([
            SystemMessage(content=system),
            HumanMessage(content=user),
        ])
    except (httpx.ConnectError, httpx.ReadTimeout, httpx.RemoteProtocolError) as exc:
        raise LlmUnavailableError(str(exc)) from exc
    except Exception as exc:
        # Provider client (Anthropic/OpenAI/Ollama) may raise its own types;
        # treat anything network-shaped as unavailable so we re-deliver, but
        # propagate genuine programmer errors.
        msg = str(exc).lower()
        if any(term in msg for term in ("connect", "timeout", "unreachable", "network")):
            raise LlmUnavailableError(str(exc)) from exc
        raise

    content = (getattr(response, "content", "") or "").strip()
    if not content:
        # Empty content from a thinking-mode model usually means our
        # ``think: false`` flag wasn't honoured. Treat as unavailable so the
        # task is retried after the operator fixes the provider config.
        raise LlmUnavailableError("LLM returned empty content (thinking-mode trap?)")

    cleaned = _strip_code_fences(content)
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError as exc:
        logger.warning("LLM returned non-JSON content (truncated): %s", cleaned[:300])
        raise ValueError(f"LLM response was not valid JSON: {exc}") from exc


# ---------------------------------------------------------------------------
# Stage 1 — extract + classify against existing taxonomy
# ---------------------------------------------------------------------------

_STAGE1_SYSTEM = (
    "You are a taxonomy assistant for an enterprise knowledge management system "
    "used by NGOs and development organisations.\n"
    "Given a document and a list of existing taxonomy terms (each with optional "
    "synonyms shown in parentheses), do three things:\n"
    "  1. Extract 8-16 domain-specific keywords (subject matter, named entities, "
    "methodologies, concepts). Skip generic words and articles like 'the X'.\n"
    "  2. For each keyword, check if it semantically matches one of the existing "
    "taxonomy terms OR any of that term's listed synonyms. If yes, classify it "
    "under that term's canonical id (NOT a new term).\n"
    "  3. Keywords that do NOT semantically fit any existing term or its synonyms "
    "go to 'unmatched'.\n"
    "Return ONLY valid JSON in this exact shape, no preamble, no code fences:\n"
    "{\n"
    '  "keywords":        [{"keyword": str, "frequency": int, "confidence": float}, ...],\n'
    '  "classifications": '
    '[{"keyword": str, "termId": str, "confidence": float, "evidence": str}, ...],\n'
    '  "unmatched":       [{"keyword": str, "frequency": int, "confidence": float}, ...]\n'
    "}\n"
    "Confidence is a float 0.0-1.0. Evidence is a short quote or paraphrase from the document."
)


async def _stage1_extract_and_classify(
    text: str,
    taxonomy_terms: list[dict],
) -> tuple[list[dict], list[dict], list[dict]]:
    """Returns ``(keywords, classifications, unmatched)``."""
    truncated = text[:_MAX_DOC_CHARS]
    if len(text) > _MAX_DOC_CHARS:
        truncated += "\n\n[Document truncated for processing]"

    if taxonomy_terms:
        def _format_term(t: dict) -> str:
            base = (
                f"- id={t['id']} | label={t.get('label', '')} "
                f"| description={t.get('description', '') or '(no description)'}"
            )
            aliases = t.get("aliases") or []
            if aliases:
                base += f" | synonyms: {', '.join(aliases)}"
            return base

        taxonomy_block = "Existing taxonomy terms:\n" + "\n".join(
            _format_term(t) for t in taxonomy_terms
        )
    else:
        taxonomy_block = "Existing taxonomy terms: (none yet — every keyword will be unmatched)"

    user_prompt = f"{taxonomy_block}\n\n--- DOCUMENT ---\n\n{truncated}"

    parsed = await _call_llm_json(_STAGE1_SYSTEM, user_prompt, max_predict_hint=2500)
    if not isinstance(parsed, dict):
        raise ValueError(f"Stage 1 expected JSON object, got {type(parsed).__name__}")

    keywords = parsed.get("keywords") or []
    classifications = parsed.get("classifications") or []
    unmatched = parsed.get("unmatched") or []

    # Defensive validation: drop entries missing required fields rather than
    # propagating malformed data downstream.
    valid_term_ids = {t["id"] for t in taxonomy_terms}
    classifications = [
        c for c in classifications
        if isinstance(c, dict) and c.get("termId") in valid_term_ids
    ]
    keywords = [k for k in keywords if isinstance(k, dict) and k.get("keyword")]
    unmatched = [u for u in unmatched if isinstance(u, dict) and u.get("keyword")]

    return keywords, classifications, unmatched


# ---------------------------------------------------------------------------
# Stage 2 — propose new candidate terms for unmatched keywords
# ---------------------------------------------------------------------------

_STAGE2_SYSTEM = (
    "You are a taxonomy specialist for an enterprise knowledge management system "
    "used by NGOs and development organisations.\n"
    "For each candidate term below:\n"
    "  1. Write a concise (1-2 sentence) glossary definition suitable for a "
    "taxonomy entry. For acronyms, expand them first then define.\n"
    "  2. List 0-4 SYNONYMS — alternative surface forms of the SAME concept "
    "(e.g. 'WASH' has synonyms 'Water Sanitation Hygiene', 'WatSan'). "
    "Synonyms are NOT related concepts; they are different ways of saying the "
    "same thing. If a term has no obvious synonyms, return an empty list.\n"
    "Return ONLY a valid JSON array, no preamble, no code fences:\n"
    '  [{"label": str, "description": str, "aliases": [str, ...]}, ...]'
)


async def _stage2_define_candidates(unmatched: list[dict]) -> list[dict]:
    """Define candidate taxonomy terms for the unmatched keywords."""
    if not unmatched:
        return []

    label_block = "\n".join(f"- {u['keyword']}" for u in unmatched)
    user_prompt = f"Define these candidate taxonomy terms:\n{label_block}"

    parsed = await _call_llm_json(_STAGE2_SYSTEM, user_prompt, max_predict_hint=1500)
    if not isinstance(parsed, list):
        raise ValueError(f"Stage 2 expected JSON array, got {type(parsed).__name__}")

    by_label: dict[str, dict] = {}
    for entry in parsed:
        if isinstance(entry, dict) and entry.get("label"):
            key = entry["label"].strip().lower()
            raw_aliases = entry.get("aliases") or []
            aliases = [
                str(a).strip() for a in raw_aliases
                if isinstance(a, (str, int, float)) and str(a).strip()
            ]
            by_label[key] = {
                "description": entry.get("description", "") or "",
                "aliases": aliases,
            }

    candidates = []
    for u in unmatched:
        label = u["keyword"].strip()
        info = by_label.get(label.lower())
        description = (info or {}).get("description") or (
            f"Term extracted from document analysis (frequency: {u.get('frequency', 0)})."
        )
        # Drop aliases that duplicate the label itself (case-insensitive).
        aliases = [
            a for a in (info or {}).get("aliases", [])
            if a.strip().lower() != label.lower()
        ]
        # Carry the original Stage-1 keyword confidence through so the backend can
        # populate the document_classification row that links this document to the
        # new candidate term with a meaningful score (not a synthetic default).
        try:
            confidence = round(float(u.get("confidence", 0.0)), 2)
        except (TypeError, ValueError):
            confidence = 0.0
        candidates.append({
            "label": label,
            "description": description,
            "source": "AI_GENERATED",
            "aliases": aliases,
            "confidence": confidence,
        })
    return candidates


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------


async def analyze_for_taxonomy(
    text: str,
    document_id: str,
) -> tuple[list[dict], list[dict], list[dict]]:
    """Run both stages and return ``(keywords, classifications, candidates)``.

    The shapes of the returned lists are kept compatible with the existing
    NATS-message contracts the backend listens for:
      * keywords        → ``ai.keywords.extracted`` payload
      * classifications → ``ai.classification.completed`` payload
      * candidates      → ``ai.taxonomy.candidates`` payload

    Raises:
        LlmUnavailableError: provider unreachable; NATS handler should nak.
    """
    if not text or len(text.strip()) < 50:
        return [], [], []

    taxonomy_terms = await load_active_taxonomy()

    keywords, raw_classifications, unmatched = await _stage1_extract_and_classify(
        text, taxonomy_terms,
    )

    # The backend's classification topic expects {documentId, termId, confidence, source};
    # rebuild from the LLM output shape.
    classifications = [
        {
            "documentId": document_id,
            "termId": c["termId"],
            "confidence": round(float(c.get("confidence", 0.0)), 2),
            "source": "AI",
        }
        for c in raw_classifications
    ]

    candidates: list[dict] = []
    if unmatched:
        try:
            candidates = await _stage2_define_candidates(unmatched)
        except LlmUnavailableError:
            # Re-raise so the whole task is naked and retried.
            raise
        except Exception:
            logger.exception("Stage 2 failed; falling back to undefined candidates")
            candidates = [
                {
                    "label": u["keyword"],
                    "description": (
                        f"Term extracted from document analysis "
                        f"(frequency: {u.get('frequency', 0)})."
                    ),
                    "source": "AI_GENERATED",
                }
                for u in unmatched
            ]

    logger.info(
        "Pipeline result for doc %s: %d keywords, %d classifications, %d candidates",
        document_id, len(keywords), len(classifications), len(candidates),
    )
    return keywords, classifications, candidates
