"""Document summarization using LangChain + configured LLM."""

import logging

from langchain_core.messages import HumanMessage, SystemMessage

from providers.llm_provider import get_llm

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """You are a document summarization assistant for an enterprise knowledge management system.
Produce a clear, concise summary of the document content.
Focus on the key points, purpose, and conclusions.
Keep the summary under 300 words.
Do not include preamble like "This document..." — start directly with the content."""


async def summarize(text: str, title: str = "") -> tuple[str, float]:
    """Summarize document text. Returns (summary, confidence)."""
    if not text or len(text.strip()) < 50:
        return "", 0.0

    # Truncate very long documents to fit context window.
    # 50K chars ≈ 12.5K tokens, leaves output headroom inside Ollama's default num_ctx=16384.
    max_chars = 50_000
    truncated = text[:max_chars]
    if len(text) > max_chars:
        truncated += "\n\n[Document truncated for processing]"

    try:
        llm = get_llm()
        messages = [
            SystemMessage(content=SYSTEM_PROMPT),
            HumanMessage(content=f"Document title: {title}\n\n{truncated}"),
        ]
        response = await llm.ainvoke(messages)
        summary = response.content.strip()

        # Confidence heuristic: based on summary length relative to input
        confidence = min(0.95, max(0.5, len(summary) / 500))

        logger.info("Generated summary: %d chars, confidence=%.2f", len(summary), confidence)
        return summary, confidence
    except Exception:
        logger.exception("Summarization failed")
        return "", 0.0
