"""Handles AI task messages from NATS — dispatches to processing pipelines."""

import json
import logging

from minio import Minio

from config import settings
from models.messages import AiTaskMessage
from pipelines.metadata_extractor import extract_metadata
from pipelines.ocr import run_ocr
from pipelines.summarizer import summarize
from pipelines.taxonomy_pipeline import (
    LlmUnavailableError,
    analyze_for_taxonomy,
    invalidate_cache,
)

logger = logging.getLogger(__name__)


def _get_minio_client() -> Minio:
    return Minio(
        settings.minio_endpoint,
        access_key=settings.minio_access_key,
        secret_key=settings.minio_secret_key,
        secure=settings.minio_secure,
    )


async def _fetch_document_bytes(task: AiTaskMessage) -> bytes | None:
    """Get raw file bytes from MinIO for OCR processing."""
    if not task.storage_key:
        return None
    try:
        client = _get_minio_client()
        response = client.get_object(settings.minio_bucket, task.storage_key)
        data = response.read()
        response.close()
        response.release_conn()
        return data
    except Exception:
        logger.exception("Failed to fetch bytes from MinIO: %s", task.storage_key)
        return None


async def _fetch_document_text(task: AiTaskMessage) -> str:
    """Get document text — from message payload or by fetching from MinIO."""
    if task.extracted_text:
        return task.extracted_text

    if not task.storage_key:
        logger.warning("No text or storage key for task %s", task.task_id)
        return ""

    try:
        client = _get_minio_client()
        response = client.get_object(settings.minio_bucket, task.storage_key)
        data = response.read()
        response.close()
        response.release_conn()

        # Try to decode as text. For binary docs (PDF, Word), the backend's
        # Tika should extract text and pass it in extracted_text field.
        try:
            return data.decode("utf-8")
        except UnicodeDecodeError:
            logger.info("Binary file for %s — needs Tika text extraction", task.storage_key)
            return f"[Binary document: {task.storage_key}]"
    except Exception:
        logger.exception("Failed to fetch from MinIO: %s", task.storage_key)
        return ""


async def handle_ai_task(payload: dict, js) -> None:
    """Dispatch an AI task through the processing pipelines."""
    task = AiTaskMessage(**payload)
    logger.info(
        "Processing task %s: type=%s doc=%s ver=%s",
        task.task_id, task.task_type, task.document_id, task.version_id,
    )

    # Fetch document text
    text = await _fetch_document_text(task)
    if not text:
        logger.warning("No text available for task %s, skipping", task.task_id)
        return

    # Run summarization
    if task.task_type in ("FULL_ANALYSIS", "SUMMARIZE"):
        summary, confidence = await summarize(text, title=str(task.document_id))
        if summary:
            result = {
                "versionId": str(task.version_id),
                "summary": summary,
                "confidence": confidence,
            }
            await js.publish("ai.summary.completed", json.dumps(result).encode())
            logger.info(
                "Published summary for version %s (confidence=%.2f)",
                task.version_id, confidence,
            )

    # Run unified two-stage LLM taxonomy pipeline (keywords + classifications + candidates).
    # Replaces the legacy spaCy NER + difflib fuzzy-match flow. If the configured LLM is
    # unreachable, ``LlmUnavailableError`` propagates so the NATS handler can nak this message
    # for re-delivery once the provider is back.
    if task.task_type in ("FULL_ANALYSIS", "EXTRACT_KEYWORDS", "CLASSIFY"):
        try:
            keywords, classifications, candidates = await analyze_for_taxonomy(
                text, str(task.document_id),
            )
        except LlmUnavailableError as exc:
            logger.warning(
                "LLM unavailable for task %s — naking for retry. cause=%s",
                task.task_id, exc,
            )
            raise

        if keywords:
            await js.publish(
                "ai.keywords.extracted",
                json.dumps({
                    "versionId": str(task.version_id),
                    "keywords": keywords,
                }).encode(),
            )
            logger.info("Published %d keywords for version %s", len(keywords), task.version_id)

        if classifications:
            await js.publish(
                "ai.classification.completed",
                json.dumps({
                    "documentId": str(task.document_id),
                    "classifications": classifications,
                }).encode(),
            )
            logger.info(
                "Published %d classifications for document %s",
                len(classifications), task.document_id,
            )

        if candidates:
            await js.publish(
                "ai.taxonomy.candidates",
                json.dumps({
                    "documentId": str(task.document_id),
                    "candidates": candidates,
                }).encode(),
            )
            logger.info(
                "Published %d taxonomy candidates for document %s",
                len(candidates), task.document_id,
            )
            invalidate_cache()  # New terms will be created, refresh cache for next doc

    # Run structured metadata extraction (Pass 5.3.0).
    # Uses spaCy NER to produce named fields that the conditional
    # workflow engine (Pass 5.3) evaluates via JSON Logic. Runs on
    # the same extracted text as summarization — no extra fetch.
    # Wrapped in try/except so a metadata-only failure (e.g. missing
    # spaCy model) doesn't nak the entire task and re-deliver the
    # already-published summary/keyword/classification work.
    if task.task_type in ("FULL_ANALYSIS", "EXTRACT_METADATA"):
        try:
            metadata = await extract_metadata(text)
            if metadata:
                result = {
                    "versionId": str(task.version_id),
                    "documentId": str(task.document_id),
                    "extractedMetadata": metadata,
                }
                await js.publish("ai.metadata.extracted", json.dumps(result).encode())
                logger.info(
                    "Published structured metadata for version %s (%d fields)",
                    task.version_id, len(metadata),
                )
        except Exception:
            logger.exception("Metadata extraction failed for task %s; continuing", task.task_id)

    # Run OCR on scanned PDFs (Pass 5.1).
    # Only fires for PDFs — the MIME check is the first gate, the
    # `detect_needs_ocr` inside `run_ocr` is the second. Non-PDF
    # formats are silently skipped. OCR is also triggered by the
    # explicit OCR task type for admin-initiated reprocessing.
    if task.task_type in ("FULL_ANALYSIS", "OCR"):
        mime = (task.mime_type or "").lower()
        if mime == "application/pdf" or task.task_type == "OCR":
            pdf_bytes = await _fetch_document_bytes(task)
            if pdf_bytes:
                language = settings.tesseract_lang  # default from env; per-doc hint comes later
                ocr_result = await run_ocr(
                    pdf_bytes=pdf_bytes,
                    document_id=str(task.document_id),
                    version_id=str(task.version_id),
                    language=language,
                )
                if ocr_result:
                    await js.publish("ai.ocr.completed", json.dumps(ocr_result).encode())
                    logger.info(
                        "Published OCR result for version %s (key=%s)",
                        task.version_id, ocr_result["ocrStorageKey"],
                    )
