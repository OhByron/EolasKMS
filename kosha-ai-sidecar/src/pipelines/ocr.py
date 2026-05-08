"""
OCR pipeline for scanned PDFs (Pass 5.1).

Detects whether a PDF page is text-based or image-based by measuring
the character count of the text layer extracted by pdfminer (which
ocrmypdf uses internally). If below a threshold, runs Tesseract via
ocrmypdf to produce a searchable PDF with an embedded text layer.

## Design decisions

- **Augment, not replace.** The original file stays untouched in MinIO.
  The OCR'd version is stored under a separate key
  (`ocr/{docId}/{versionId}.pdf`) and referenced via a new column
  on the version row. This preserves the original hash for
  signature/integrity purposes.

- **Language detection.** We accept a `language` hint from the backend
  (sourced from the upload form or AI-detected). When no hint is
  given we default to 'eng'. Tesseract multi-language mode works
  when the caller passes `eng+deu` style strings.

- **Performance.** OCR is CPU-heavy (30s–5min per page depending on
  image quality and resolution). This runs ONLY for PDFs detected as
  image-based, which filters out the vast majority of uploads. The
  task handler runs async in the existing NATS consumer, so it
  doesn't block anything.

- **Confidence.** ocrmypdf doesn't report per-page confidence, but we
  can extract it from Tesseract's hOCR output. For v1 we skip this
  and report a boolean "ocr_applied" flag. Confidence scoring is a
  Pass 6+ enhancement.
"""

import logging
import os
import subprocess
import tempfile
from pathlib import Path

import ocrmypdf
from minio import Minio

from config import settings

logger = logging.getLogger(__name__)

# Pages with fewer than this many characters of text-layer content
# are considered "image-based" and eligible for OCR. PDFs with a
# real text layer (born-digital) typically have hundreds of chars
# per page; scanned pages have zero or near-zero.
SCANNED_PAGE_TEXT_THRESHOLD = 50


def detect_needs_ocr(pdf_bytes: bytes) -> bool:
    """
    Quick heuristic: does this PDF have a meaningful text layer?

    Writes the bytes to a temp file and runs pdftotext (ships with
    poppler-utils, which is pulled in by ocrmypdf's dep chain) to
    extract whatever text layer exists. If the extracted text is
    shorter than the threshold, we declare it scanned.

    For v1 this is a whole-document check, not per-page. A mixed
    document (some pages scanned, some text) is treated as needing
    OCR — ocrmypdf is smart enough to skip pages that already have
    text via its `--skip-text` flag.
    """
    try:
        with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
            f.write(pdf_bytes)
            tmp_path = f.name

        result = subprocess.run(
            ["pdftotext", tmp_path, "-"],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        os.unlink(tmp_path)

        text_length = len(result.stdout.strip())
        needs_ocr = text_length < SCANNED_PAGE_TEXT_THRESHOLD
        logger.info(
            "OCR detection: extracted %d chars from text layer → %s",
            text_length,
            "needs OCR" if needs_ocr else "has text, skipping OCR",
        )
        return needs_ocr
    except Exception as ex:
        logger.warning("OCR detection failed, defaulting to skip: %s", ex)
        if "tmp_path" in locals():
            os.unlink(tmp_path)
        return False


async def run_ocr(
    pdf_bytes: bytes,
    document_id: str,
    version_id: str,
    language: str = "eng",
) -> dict | None:
    """
    Run OCR on a PDF and store the result in MinIO.

    Returns a dict with the OCR storage key and metadata, or None if
    OCR was not needed or failed. The caller is responsible for
    publishing the result to NATS so the backend can persist it.

    Blocking I/O (ocrmypdf shells out to Tesseract) runs in the
    calling thread — the NATS consumer is already async but individual
    tasks are processed sequentially, which is correct for CPU-bound
    OCR work. If throughput ever matters, scale horizontally by
    running multiple sidecar instances.
    """
    if not detect_needs_ocr(pdf_bytes):
        return None

    with tempfile.TemporaryDirectory(prefix="eolas-ocr-") as workdir:
        input_path = Path(workdir) / "input.pdf"
        output_path = Path(workdir) / "output.pdf"
        input_path.write_bytes(pdf_bytes)

        logger.info(
            "Running OCR on version %s (language=%s, size=%d bytes)",
            version_id, language, len(pdf_bytes),
        )

        try:
            ocrmypdf.ocr(
                input_file=str(input_path),
                output_file=str(output_path),
                language=language,
                skip_text=True,        # don't re-OCR pages that already have text
                optimize=1,            # light PDF optimization
                progress_bar=False,
                jobs=1,                # one Tesseract process at a time
            )
        except ocrmypdf.exceptions.PriorOcrFoundError:
            logger.info("OCR skipped for version %s — prior OCR already present", version_id)
            return None
        except Exception as ex:
            logger.error("OCR failed for version %s: %s", version_id, ex)
            return None

        ocr_bytes = output_path.read_bytes()
        ocr_key = f"ocr/{document_id}/{version_id}.pdf"

        # Store the OCR'd PDF in MinIO
        try:
            client = Minio(
                settings.minio_endpoint,
                access_key=settings.minio_access_key,
                secret_key=settings.minio_secret_key,
                secure=settings.minio_secure,
            )
            import io
            client.put_object(
                settings.minio_bucket,
                ocr_key,
                io.BytesIO(ocr_bytes),
                len(ocr_bytes),
                content_type="application/pdf",
            )
            logger.info(
                "Stored OCR'd PDF at %s (%d bytes, original was %d bytes)",
                ocr_key, len(ocr_bytes), len(pdf_bytes),
            )
        except Exception as ex:
            logger.error("Failed to store OCR'd PDF at %s: %s", ocr_key, ex)
            return None

        return {
            "versionId": version_id,
            "documentId": document_id,
            "ocrStorageKey": ocr_key,
            "ocrFileSizeBytes": len(ocr_bytes),
            "language": language,
            "ocrApplied": True,
        }
